"""Behavioral checks for the profile validator's deliberately small contract."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "validate_profiles.py"
SPEC = importlib.util.spec_from_file_location("profile_validator", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)
REQUIRED = (
    "bugfix", "planer", "tech", "unit-test", "smoke_scenario",
    "smoke", "review", "dev", "product",
)


class ProfileValidatorTests(unittest.TestCase):
    def setUp(self):
        fixtures = REPO / "reports" / "LOOP-001" / "unit-fixtures"
        fixtures.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="case-", dir=fixtures)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project with spaces"
        (self.root / "profiles").mkdir(parents=True)
        self.write("AGENTS.md", "# Instructions\n[dev](profiles/dev.md)\n")
        for name in REQUIRED:
            self.write(f"profiles/{name}.md", "# Profile\n")

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def validate(self):
        return validator.validate(self.root)

    def cli(self, root=None, cwd=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(root or self.root)],
            cwd=cwd or REPO, capture_output=True, text=True, timeout=20,
        )

    def test_valid_project_and_existing_link(self):
        self.assertEqual(self.validate(), [])

    def test_all_required_profiles_are_independently_enforced(self):
        for name in REQUIRED:
            with self.subTest(profile=name):
                path = self.root / "profiles" / f"{name}.md"
                path.unlink()
                self.assertIn(f"Missing required file: profiles/{name}.md", self.validate())
                path.write_text("# Restored\n", encoding="utf-8")

    def test_missing_agents_file(self):
        (self.root / "AGENTS.md").unlink()
        self.assertIn("Missing required file: AGENTS.md", self.validate())

    def test_directory_does_not_replace_required_file(self):
        path = self.root / "profiles" / "review.md"
        path.unlink()
        path.mkdir()
        self.assertIn("Missing required file: profiles/review.md", self.validate())

    def test_missing_root(self):
        missing = self.root / "absent"
        self.assertEqual(validator.validate(missing), [f"Root is not a directory: {missing}"])

    def test_file_is_not_root_directory(self):
        path = self.root / "AGENTS.md"
        self.assertEqual(validator.validate(path), [f"Root is not a directory: {path}"])

    def test_broken_link_reports_source_and_target(self):
        self.write("profiles/tech.md", "[missing](not-here.md)")
        self.assertIn("Broken link in profiles/tech.md: not-here.md", self.validate())

    def test_profile_links_resolve_relative_to_the_source(self):
        self.write("profiles/tech.md", "[root](../AGENTS.md)\n[dev](dev.md)")
        self.assertEqual(self.validate(), [])
        self.write("profiles/tech.md", "[wrong](AGENTS.md)")
        self.assertEqual(self.validate(), ["Broken link in profiles/tech.md: AGENTS.md"])

    def test_additional_profile_markdown_is_checked(self):
        self.write("profiles/extra.md", "[missing](lost.md)")
        self.assertEqual(self.validate(), ["Broken link in profiles/extra.md: lost.md"])

    def test_nested_profiles_are_outside_scan_scope(self):
        self.write("profiles/examples/example.md", "[missing](lost.md)")
        self.assertEqual(self.validate(), [])

    def test_external_urls_and_fragments_are_skipped_without_network(self):
        self.write("profiles/tech.md", "\n".join([
            "[https](https://example.invalid/a)", "[relative](//example.invalid/a)",
            "[mail](mailto:test@example.invalid)", "[anchor](#unknown-heading)",
            "[malformed external](https://[invalid)",
        ]))
        with patch("socket.create_connection", side_effect=AssertionError("network used")), \
                patch("urllib.request.urlopen", side_effect=AssertionError("network used")):
            self.assertEqual(self.validate(), [])

    def test_spaces_percent_encoding_query_and_fragment(self):
        self.write("docs/space name.md", "# Target")
        self.write("AGENTS.md", "\n".join([
            "[angle](<docs/space name.md>)",
            "[encoded](docs/space%20name.md?view=short#section)",
            "[title](docs/space%20name.md \"target title\")",
        ]))
        self.assertEqual(self.validate(), [])

    def test_fenced_and_inline_code_are_not_active_links(self):
        self.write("AGENTS.md", "\n".join([
            "```md", "[missing](inside-backticks.md)", "```",
            "~~~md", "[missing](inside-tilde.md)", "~~~",
            "An example: `[missing](inside-inline.md)`.",
        ]))
        self.assertEqual(self.validate(), [])

    def test_inline_triple_backticks_do_not_hide_following_link(self):
        self.write("AGENTS.md", "```example```\n[x](missing.md)\n")
        self.assertEqual(self.validate(), ["Broken link in AGENTS.md: missing.md"])

    def test_links_after_closed_fence_are_checked(self):
        self.write("AGENTS.md", "````md\n[hidden](hidden.md)\n```\n````\n[x](missing.md)")
        self.assertEqual(self.validate(), ["Broken link in AGENTS.md: missing.md"])

    def test_unreadable_markdown_has_diagnostic(self):
        original = Path.read_text

        def read(path, *args, **kwargs):
            if path == self.root / "profiles" / "tech.md":
                raise PermissionError("denied for test")
            return original(path, *args, **kwargs)

        with patch.object(Path, "read_text", read):
            self.assertEqual(self.validate(), ["Cannot read profiles/tech.md: denied for test"])

    def test_invalid_utf8_has_diagnostic(self):
        (self.root / "profiles" / "tech.md").write_bytes(b"\xff\xfe\xfa")
        errors = self.validate()
        self.assertEqual(len(errors), 1)
        self.assertTrue(errors[0].startswith("Cannot read profiles/tech.md:"), errors)

    def test_diagnostics_are_deterministic(self):
        self.write("profiles/tech.md", "[z](z.md)\n[a](a.md)\n[z](z.md)")
        self.assertEqual(self.validate(), [
            "Broken link in profiles/tech.md: a.md", "Broken link in profiles/tech.md: z.md",
        ])

    def test_cli_valid_absolute_root_from_another_cwd(self):
        result = self.cli(cwd=self.root / "profiles")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS:", result.stdout)
        self.assertEqual(result.stderr, "")

    def test_cli_relative_root(self):
        result = self.cli(root=Path("project with spaces"), cwd=Path(self.temp.name))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_cli_invalid_root_and_broken_link_exit_one(self):
        result = self.cli(root=self.root / "absent")
        self.assertEqual(result.returncode, 1)
        self.assertIn("Root is not a directory:", result.stdout)
        self.assertNotIn("Traceback", result.stderr)
        self.write("profiles/tech.md", "[missing](lost.md)")
        result = self.cli()
        self.assertEqual(result.returncode, 1)
        self.assertIn("Broken link in profiles/tech.md: lost.md", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_cli_requires_root_argument(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 2)
        self.assertIn("--root", result.stderr)


if __name__ == "__main__":
    unittest.main()
