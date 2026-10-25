"""Regressions for escaped opening brackets in inline Markdown links."""

import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "scripts" / "validate_profiles.py"
SPEC = importlib.util.spec_from_file_location("escaping_validator", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)
REQUIRED = (
    "bugfix", "planer", "tech", "unit-test", "smoke_scenario",
    "smoke", "review", "dev", "product",
)


class EscapedOpeningBracketTests(unittest.TestCase):
    def setUp(self):
        fixtures = REPO / "reports" / "LOOP-002" / "unit-fixtures"
        fixtures.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="escaping-", dir=fixtures)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "profiles").mkdir()
        for name in REQUIRED:
            (self.root / "profiles" / f"{name}.md").write_text("# Profile\n", encoding="utf-8")
        self.write_agents("# Instructions\n")

    def write_agents(self, text):
        (self.root / "AGENTS.md").write_text(text, encoding="utf-8")

    def test_odd_backslashes_make_opening_bracket_literal(self):
        for count in (1, 3):
            with self.subTest(backslashes=count):
                self.write_agents("\\" * count + "[literal](missing.md)\n")
                self.assertEqual(validator.validate(self.root), [])

    def test_even_backslashes_preserve_active_link(self):
        for count in (0, 2, 4):
            with self.subTest(backslashes=count):
                self.write_agents("\\" * count + "[active](missing.md)\n")
                self.assertEqual(validator.validate(self.root), ["Broken link in AGENTS.md: missing.md"])

    def test_literal_example_does_not_hide_adjacent_active_link(self):
        self.write_agents("\\[literal](literal.md) and [active](active.md)\n")
        self.assertEqual(validator.validate(self.root), ["Broken link in AGENTS.md: active.md"])

    def test_cli_accepts_literal_link_example(self):
        self.write_agents("\\[literal](missing.md)\n")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root)],
            cwd=REPO, capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS:", result.stdout)
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
