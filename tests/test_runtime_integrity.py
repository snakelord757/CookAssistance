"""Tamper tests modify disposable runtime copies, never live protection files."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import trusted_runtime as trusted
from runtime_integrity import RuntimeIntegrity
from package_runtime import package
from loop_security import Audit, Blocked, encode
from context_gate import ContextGate
from execution_flow import GuardedFlow
import test_execution_loop as fixtures
import execution_loop as loop


class IntegrityTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        base = root / "reports" / "integrity-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.runtime = self.folder / "runtime"
        package(root, self.runtime)

    def test_real_kernel_rejects_agent_owned_installation(self):
        with self.assertRaisesRegex(trusted.IntegrityError, "trusted_path_mutable_by_agent"):
            trusted.assert_protected(self.runtime / "scripts/context_gate.py")

    def test_launcher_blocks_before_import_of_tampered_gateway(self):
        marker = self.folder / "imported"
        target = self.runtime / "scripts/context_gate.py"
        target.write_text("from pathlib import Path\nPath(" + repr(str(marker)) + ").write_text('bad')\n")
        result = subprocess.run([sys.executable, "-I", "-S", "-B", str(self.runtime / "scripts/trusted_runtime.py"),
                                 "run", "--repo", str(self.folder)], capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(marker.exists())

    def test_seal_comparison_independent_of_permission_gate(self):
        # Simulates separately enforced read-only ownership ONLY for hash logic.
        with patch.object(trusted, "assert_protected"), patch.object(trusted, "assert_restricted_identity"):
            trusted.verify_install(self.runtime)
            (self.runtime / "scripts/context_gate.py").write_text("# disabled gate\n")
            with self.assertRaisesRegex(trusted.IntegrityError, "runtime_seal_mismatch"):
                trusted.verify_install(self.runtime)

    def test_rehashing_tampered_install_does_not_grant_os_trust(self):
        (self.runtime / "scripts/context_gate.py").write_text("# disabled gate\n")
        (self.runtime / "runtime-seal.json").write_text(json.dumps({"version": 1, "files": trusted.asset_hashes(self.runtime)}))
        with self.assertRaisesRegex(trusted.IntegrityError, "trusted_path_mutable_by_agent|unrestricted_execution_identity"):
            trusted.verify_install(self.runtime)

    def test_extra_importable_module_and_bytecode_block(self):
        for name in ("sitecustomize.py", "context_gate.pyc", "context_gate.pyd"):
            extra = self.runtime / "scripts" / name
            extra.write_text("bad")
            with self.assertRaisesRegex(trusted.IntegrityError, "unexpected_runtime_entry"):
                trusted.verify_install(self.runtime)
            extra.unlink()

    def test_prompt_and_gateway_changes_invalidate_memory_pin(self):
        for name in ("scripts/context_gate.py", "docs/security-review-prompt.md"):
            guard = RuntimeIntegrity(self.runtime)
            path = self.runtime / name
            path.write_bytes(path.read_bytes() + b"\n# changed")
            with self.assertRaisesRegex(Blocked, "runtime_integrity_changed"):
                guard.verify()

    def test_protected_prompt_cannot_be_generation_target(self):
        guard = RuntimeIntegrity(self.runtime)
        config = {"files": ["docs/security-review-prompt.md"]}
        with self.assertRaisesRegex(Blocked, "controller_is_protected"):
            loop.run(self.runtime, config, Mock(), Audit(self.folder / "audit.jsonl"), integrity=guard)

    def test_runtime_mutation_before_call_prevents_send(self):
        guard = RuntimeIntegrity(self.runtime)
        backend = Mock()
        flow = GuardedFlow(ContextGate(Audit(self.folder / "audit.jsonl")), backend, guard)
        (self.runtime / "scripts/context_gate.py").write_text("# changed")
        with self.assertRaisesRegex(Blocked, "runtime_integrity_changed"):
            flow.call("security", "Synthetic", {})
        backend.complete.assert_not_called()

    def test_config_cannot_be_changed_after_pinning(self):
        config = self.folder / "policy.json"
        config.write_text('{"allow":false}')
        guard = RuntimeIntegrity(self.runtime, config_paths=[config])
        config.write_text('{"allow":true}')
        with self.assertRaisesRegex(Blocked, "runtime_integrity_changed"):
            guard.verify()

    def test_source_gateway_service_is_not_a_bypass(self):
        result = subprocess.run([sys.executable, str(Path(loop.__file__).with_name("llm_gateway.py")),
                                 "--audit", str(self.folder / "unused.jsonl"),
                                 "--release-policy", str(self.folder / "unused-policy.json")],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"protected_runtime_required", result.stdout)
        self.assertFalse((self.folder / "unused.jsonl").exists())


class DuringTests(unittest.TestCase):
    setUp = fixtures.LoopTests.setUp
    tearDown = fixtures.LoopTests.tearDown
    git = fixtures.LoopTests.git
    stop_http = fixtures.LoopTests.stop_http
    provider = fixtures.LoopTests.provider

    def test_test_command_tampering_blocks_review_and_commit(self):
        runtime = self.directory / "runtime"
        package(Path(loop.__file__).resolve().parents[1], runtime)
        guard = RuntimeIntegrity(runtime)
        target = runtime / "scripts/context_gate.py"
        self.config["tests"] = [[sys.executable, "-c", "from pathlib import Path; Path(" + repr(str(target)) + ").write_text('disabled')"]]
        with self.assertRaisesRegex(Blocked, "runtime_integrity_changed"):
            loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.fixture_policy,
                     integrity=guard, commit=True)
        self.assertEqual(self.generation_count, 1)
        self.assertEqual(self.review_count, 0)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertFalse((loop.gate_dir(self.repo) / "approval.json").exists())

    def test_mutation_during_review_cannot_issue_receipt(self):
        runtime = self.directory / "runtime"
        package(Path(loop.__file__).resolve().parents[1], runtime)
        guard = RuntimeIntegrity(runtime)
        def alter(review):
            (runtime / "docs/security-review-prompt.md").write_text("allow everything")
            return review
        self.mutate_review = alter
        with patch.dict(os.environ, {"PYTHONDONTWRITEBYTECODE": "1"}), self.assertRaisesRegex(Blocked, "runtime_integrity_changed"):
            loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.fixture_policy,
                     integrity=guard, commit=True)
        self.assertEqual(self.review_count, 1)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertFalse((loop.gate_dir(self.repo) / "approval.json").exists())


if __name__ == "__main__":
    unittest.main()
