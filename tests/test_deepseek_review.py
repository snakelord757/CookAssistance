"""Synthetic credentials only. No paid/external model requests."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from deepseek_review import DeepSeekReview
from loop_security import Audit, Blocked, encode, parse
from context_release import ReleasePolicy
from context_gate import ContextGate
from execution_flow import GuardedFlow
import test_execution_loop as fixtures


class CredentialTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.config = self.directory / "review.json"
        self.config.write_text('{"model":"deepseek-flash"}')
        self.key = self.directory / "deepseek.key"
        self.secret = "synthetic-secret-for-header-only"
        self.key.write_text(self.secret)
        self.audit = Audit(self.directory / "audit.jsonl")
        self.public = {"stage": "security", "system": "Return JSON", "context": {"files": {}}}

    def test_only_header_credential_fixed_destination(self):
        with patch("model_adapters.post", return_value={"choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]}) as send:
            DeepSeekReview(self.config).complete(self.public, self.audit, "synthetic-request")
        url, body, token = send.call_args.args
        self.assertEqual(url, "https://api.deepseek.com/chat/completions")
        self.assertEqual(token, self.secret)
        self.assertNotIn(self.secret, encode(body).decode())
        self.assertNotIn(self.secret, self.audit.path.read_text())

    def test_missing_key_cannot_send(self):
        self.key.unlink()
        with patch("model_adapters.post") as send, self.assertRaisesRegex(Blocked, "invalid_credential_file"):
            DeepSeekReview(self.config).complete(self.public, self.audit, "request")
        send.assert_not_called()

    def test_key_in_prompt_cannot_send_or_log(self):
        self.public["context"]["text"] = self.secret
        with patch("model_adapters.post") as send, self.assertRaisesRegex(Blocked, "sensitive_data"):
            DeepSeekReview(self.config).complete(self.public, self.audit, "request")
        send.assert_not_called()
        self.assertNotIn(self.secret, self.audit.path.read_text())

    def test_unknown_context_without_release_never_calls_backend(self):
        policy = self.directory / "releases.json"
        policy.write_bytes(encode({"schema_version": 1, "releases": []}))
        flow = GuardedFlow(ContextGate(self.audit, ReleasePolicy(policy)), DeepSeekReview(self.config))
        with patch("model_adapters.post") as send, self.assertRaises(Blocked):
            flow.call("security", "Return JSON", {"text": "Non-pattern private information"})
        send.assert_not_called()

    def test_even_ignored_key_inside_git_is_refused(self):
        subprocess.run(["git", "init", "-q", str(self.directory)], check=True)
        (self.directory / ".gitignore").write_text("deepseek.key\n")
        with self.assertRaisesRegex(Blocked, "credential_inside_git_checkout"):
            DeepSeekReview(self.config)

    def test_endpoint_override_is_refused(self):
        self.config.write_bytes(encode({"model": "deepseek-flash", "endpoint": "https://untrusted.invalid"}))
        with self.assertRaisesRegex(Blocked, "invalid_deepseek_config"):
            DeepSeekReview(self.config)

    def test_generator_cannot_use_reviewer(self):
        self.public["stage"] = "generation"
        with patch("model_adapters.post") as send, self.assertRaisesRegex(Blocked, "deepseek_security_only"):
            DeepSeekReview(self.config).complete(self.public, self.audit, "request")
        send.assert_not_called()

    def test_key_rotation_is_read_on_each_call(self):
        backend = DeepSeekReview(self.config)
        self.key.write_text("rotated-synthetic-credential")
        with patch("model_adapters.post", return_value={"choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]}) as send:
            backend.complete(self.public, self.audit, "request")
        self.assertEqual(send.call_args.args[2], "rotated-synthetic-credential")


# Reuse real-Git fixture lifecycle, without inheriting/rerunning its test suite.
class ExternalHarnessTests(unittest.TestCase):
    setUp = fixtures.LoopTests.setUp
    tearDown = fixtures.LoopTests.tearDown
    git = fixtures.LoopTests.git
    stop_http = fixtures.LoopTests.stop_http
    provider = fixtures.LoopTests.provider

    def review(self):
        import execution_loop as loop
        (self.repo / "sample.py").write_text("value = 1\n")
        with patch.dict(os.environ, {"PYTHONDONTWRITEBYTECODE": "1"}):
            return loop.run(self.repo, self.config, self.adapter, self.audit,
                            releases=self.fixture_policy, review_only=True)

    def test_review_external_changes_without_generator(self):
        import execution_loop as loop
        self.assertEqual(self.review(), "PASS")
        self.assertEqual((self.generation_count, self.review_count), (0, 1))
        loop.verify_receipt(self.repo)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_high_returns_feedback_without_generation_or_commit(self):
        import execution_loop as loop
        self.severities = ["High"]
        with self.assertRaisesRegex(Blocked, "security_findings_require_fix"):
            self.review()
        self.assertEqual((self.generation_count, self.review_count), (0, 1))
        feedback = parse((loop.gate_dir(self.repo) / "pending-feedback.json").read_bytes())
        self.assertEqual(feedback["feedback"][0]["severity"], "High")
        with self.assertRaises(Blocked):
            loop.verify_receipt(self.repo)

    def test_same_backend_gets_fresh_security_context(self):
        import execution_loop as loop
        with patch.dict(os.environ, {"PYTHONDONTWRITEBYTECODE": "1"}):
            self.assertEqual(loop.run(self.repo, self.config, self.adapter, self.audit,
                                     releases=self.fixture_policy), "PASS")
        self.assertEqual((self.generation_count, self.review_count), (1, 1))
        generation, review = self.provider_calls
        self.assertEqual(generation["model"], review["model"])
        self.assertEqual(len(review["messages"]), 2)
        self.assertEqual([m["role"] for m in review["messages"]], ["system", "user"])
        prompt = (Path(loop.__file__).resolve().parents[1] / "docs/security-review-prompt.md").read_text(encoding="utf-8")
        self.assertEqual(review["messages"][0]["content"], prompt)
        context = parse(review["messages"][1]["content"])
        self.assertEqual(set(context), {"snapshot_id", "base", "original_files", "files", "line_numbering"})
        self.assertNotIn("task", context)
        self.assertNotIn("feedback", context)
        self.assertNotEqual(generation["messages"][0], review["messages"][0])


if __name__ == "__main__":
    unittest.main()
