import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import execution_loop as loop
from harness_preflight import check_connector, load_connector
from loop_security import Audit, Blocked, STAGES, encode
from context_gate import ContextGate
from context_release import ReleasePolicy, draft_release, request


class HarnessContractTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        folder = root / "reports" / "harness-tests"
        folder.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=folder)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.audit = Audit(self.path / "audit.jsonl")
        self.builtin = [sys.executable, str(root / "scripts" / "builtin_connector.py")]

    def test_builtin_real_http_all_stages(self):
        report = check_connector(self.builtin, self.audit, self.path)
        self.assertEqual(report["cases"], 45)
        self.assertEqual(report["scope"], "tested_connector_only")
        self.assertEqual(set(report["stages"]), STAGES)
        self.assertNotIn("Private Person", self.audit.path.read_text())

    def test_connector_cannot_claim_pass_without_sending(self):
        with self.assertRaisesRegex(Blocked, "connector_release_probe_failed"):
            check_connector([sys.executable, "-c", "raise SystemExit(0)"], self.audit, self.path)

    def test_connector_forwarding_raw_context_fails(self):
        bypass = self.path / "bypass.py"
        bypass.write_text('''import json, sys, urllib.request
p=json.load(open(sys.argv[-1]))
r=urllib.request.Request(p['destination'],json.dumps(p['request']).encode(),{'Content-Type':'application/json'})
urllib.request.build_opener(urllib.request.ProxyHandler({})).open(r).read()
''')
        with self.assertRaisesRegex(Blocked, "connector_release_probe_failed"):
            check_connector([sys.executable, str(bypass)], self.audit, self.path)

    def test_missing_connector_fails(self):
        with self.assertRaisesRegex(Blocked, "connector_execution_failed"):
            check_connector([str(self.path / "missing.exe")], self.audit, self.path)

    def test_connector_config_inside_candidate_refused(self):
        config = self.path / "connector.json"
        config.write_bytes(encode({"command": self.builtin}))
        with self.assertRaisesRegex(Blocked, "connector_config_inside_generated_checkout"):
            load_connector(config, self.path)

    def test_all_stages_require_exact_release(self):
        policy_path = self.path / "policy.json"
        for stage in STAGES:
            raw = request(stage, "Synthetic", {"history": ["private fixture"]})
            release = draft_release(raw, [{"private": "private fixture", "public": "ANON"}])
            release["approved"] = True
            policy_path.write_bytes(encode({"schema_version": 1, "releases": [release]}))
            gate = ContextGate(self.audit, ReleasePolicy(policy_path))
            self.assertEqual(gate.authorize(stage, raw["system"], raw["context"])["context"], {"history": ["ANON"]})
            with self.assertRaises(Blocked):
                gate.authorize(stage, raw["system"], {"history": ["different fixture"]})
        with self.assertRaisesRegex(Blocked, "invalid_release_request"):
            request("unsupported", "Synthetic", {})

    def test_builtin_cannot_be_declared_external_connector(self):
        config = self.path / "connector.json"
        config.write_bytes(encode({"command": self.builtin}))
        with self.assertRaisesRegex(Blocked, "builtin_probe_is_not_external_harness"):
            load_connector(config, self.path / "candidate")

    def test_missing_model_fails_before_any_generation(self):
        from unittest.mock import Mock
        generator = Mock()
        with self.assertRaisesRegex(Blocked, "model_backend_required"):
            loop.run(self.path, {}, None, self.audit)
        generator.complete.assert_not_called()

    def test_missing_hook_fails_then_installed_hook_blocks_commit(self):
        repo = self.path / "repo"
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        with self.assertRaisesRegex(Blocked, "commit_gate_not_installed"):
            loop.require_hook(repo)
        loop.install_hook(repo)
        loop.require_hook(repo)
        result = subprocess.run(["git", "-C", str(repo), "-c", "user.name=Synthetic",
                                 "-c", "user.email=synthetic@localhost", "commit", "--allow-empty", "-m", "probe"],
                                capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"BLOCKED", result.stdout + result.stderr)

    def test_cli_requires_model_config(self):
        with patch.object(loop, "validate_startup"), patch.object(sys, "argv", ["execution_loop", "run", "--repo", str(self.path),
                                        "--config", "task.json", "--audit", "audit.jsonl",
                                        "--release-policy", "release.json"]):
            self.assertEqual(loop.main(), 2)

    def test_external_review_requires_connector(self):
        with patch.object(loop, "validate_startup"), patch.object(sys, "argv", ["execution_loop", "review", "--repo", str(self.path),
                                        "--config", "task.json", "--audit", "audit.jsonl",
                                        "--release-policy", "release.json", "--adapter-config", "generation.json"]):
            self.assertEqual(loop.main(), 2)


if __name__ == "__main__":
    unittest.main()
