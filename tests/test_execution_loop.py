"""Synthetic integration: real HTTP gateway and real Git, fake provider only."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import execution_loop as loop
import llm_gateway as gateway
import model_adapters as adapters
from loop_security import Audit, Blocked, encode, parse, post, scan
from context_gate import ContextGate, GatewayClient
from execution_flow import GuardedFlow
from context_release import ReleasePolicy, draft_release, request


def seed_fixture_release(policy, stage, system, context):
    """TEST ONLY trusted operator: releases known synthetic regression fixtures."""
    entry = draft_release(request(stage, system, context), [])
    entry["approved"] = True
    data = parse(policy.path.read_bytes())
    data["releases"] = [e for e in data["releases"] if e["input_sha256"] != entry["input_sha256"]] + [entry]
    policy.path.write_bytes(encode(data))


class FixtureClient(GuardedFlow):
    def call(self, stage, system, context):
        # Existing mechanics tests use synthetic declassified inputs. Separate
        # disclosure tests below use the unmodified production client exclusively.
        self.audit.check(context, stage, direction="fixture_context")
        seed_fixture_release(self.releases, stage, system, context)
        return super().call(stage, system, context)


class FixturePolicy(ReleasePolicy):
    """Test-only authority for known synthetic regression inputs."""
    def __init__(self, path, audit):
        super().__init__(path)
        self.audit = audit

    def release(self, stage, system, context):
        self.audit.check(context, stage, direction="fixture_context")
        seed_fixture_release(self, stage, system, context)
        return super().release(stage, system, context)


def clean_review(snapshot, severity=None):
    return {
        "schema_version": 1, "snapshot_id": snapshot, "coverage_complete": True,
        "missing_context": [],
        "checks": [{"category": c, "status": "checked", "reason": "Checked supplied source"} for c in sorted(loop.CATEGORIES)],
        "findings": [] if severity is None else [{
            "id": "SEC-001", "severity": severity, "file": "sample.py", "line": 1,
            "category": "input_validation", "condition": "Unchecked input reaches operation",
            "impact": "Untrusted value accepted", "fix": "Validate input before use"}],
    }


class LoopTests(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[1] / "reports" / "security-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.repo = self.directory / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic Tester")
        self.git("config", "user.email", "synthetic@localhost")
        (self.repo / "sample.py").write_text("value = 0\n", encoding="utf-8")
        self.git("add", "sample.py")
        self.git("commit", "-qm", "baseline")
        self.base = self.git("rev-parse", "HEAD")
        self.audit = Audit(self.directory / "loop.jsonl")
        self.gateway_audit = Audit(self.directory / "gateway.jsonl")
        self.provider_calls = []
        self.generation_count = 0
        self.review_count = 0
        self.severities = [None]
        self.mutate_review = lambda result: result
        self.generated = None
        self.config = {"task": "Update the sample implementation", "files": ["sample.py"],
                       "tests": [[sys.executable, "-c", "import sample; assert sample.value > 0"]],
                       "max_attempts": 3, "test_timeout": 5}
        self.policy = ReleasePolicy(self.directory / "releases.json")
        self.policy.path.write_bytes(encode({"schema_version": 1, "releases": []}))
        self.adapter = adapters.ChatCompletionsAdapter("https://provider.invalid/v1/chat/completions", "synthetic-provider-credential", "synthetic-model")
        service = gateway.Gateway(self.gateway_audit, self.policy)
        self.service = service
        self.http = gateway.server(service, "synthetic-gateway-credential-only", 0)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_http)
        self.url = f"http://127.0.0.1:{self.http.server_port}/v1/authorize"
        self.client = FixtureClient(GatewayClient(self.url, "synthetic-gateway-credential-only", self.audit, self.policy), self.adapter)
        self.fixture_policy = FixturePolicy(self.policy.path, self.audit)
        self.provider_patch = patch.object(adapters, "post", self.provider)
        self.provider_patch.start()
        self.addCleanup(self.provider_patch.stop)

    def tearDown(self):
        # Retain actual synthetic HTTP audit evidence, not hand-written verdicts.
        evidence = Path(__file__).resolve().parents[1] / "reports/security-tests/evidence" / self._testMethodName
        evidence.mkdir(parents=True, exist_ok=True)
        for audit in (self.audit, self.gateway_audit):
            if audit.path.exists():
                (evidence / audit.path.name).write_bytes(audit.path.read_bytes())

    def stop_http(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=5)

    def git(self, *args):
        return loop.git(self.repo, *args)

    def provider(self, url, body, token):
        self.provider_calls.append(body)
        context = parse(body["messages"][1]["content"])
        if "snapshot_id" in context:
            self.review_count += 1
            severity = self.severities[min(self.review_count - 1, len(self.severities) - 1)]
            result = self.mutate_review(clean_review(context["snapshot_id"], severity))
        else:
            self.generation_count += 1
            result = self.generated or {"files": {"sample.py": f"value = {self.generation_count}\n"}}
        return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(result)}}]}

    def run_loop(self, commit=True):
        # Prevent test-created bytecode becoming an out-of-scope source file.
        with patch.dict(os.environ, {"PYTHONDONTWRITEBYTECODE": "1"}):
            return loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.fixture_policy, commit=commit)

    def assert_blocked(self, reason=None):
        with self.assertRaises(Blocked) as caught:
            self.run_loop()
        if reason:
            self.assertEqual(str(caught.exception), reason)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
        return caught

    def test_clean_commits_after_two_distinct_gateway_requests(self):
        self.assertEqual(self.run_loop(), "PASS")
        self.assertNotEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual((self.generation_count, self.review_count), (1, 1))
        events = [json.loads(line) for line in self.audit.path.read_text().splitlines()]
        sent = [e for e in events if e["verdict"] == "SEND"]
        self.assertEqual([e["stage"] for e in sent], ["generation", "security"])
        self.assertNotEqual(sent[0]["request_id"], sent[1]["request_id"])
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_source_cli_cannot_bless_mutable_runtime(self):
        result = subprocess.run([sys.executable, str(Path(loop.__file__)), "run", "--repo", str(self.repo)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"protected_runtime_required", result.stdout)
        self.assertEqual(self.provider_calls, [])
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_http_authentication_blocks_before_provider(self):
        with self.assertRaises(Blocked):
            post(self.url, {}, "wrong-synthetic-credential")
        self.assertEqual(self.provider_calls, [])
        self.assertIn("request_rejected", self.gateway_audit.path.read_text())

    def test_high_returns_feedback_then_reruns_tests_and_review(self):
        self.severities = ["High", None]
        self.assertEqual(self.run_loop(), "PASS")
        self.assertEqual((self.generation_count, self.review_count), (2, 2))
        context = parse(self.provider_calls[2]["messages"][1]["content"])
        self.assertEqual(context["feedback"][0]["severity"], "High")
        events = [json.loads(line) for line in self.audit.path.read_text().splitlines()]
        self.assertEqual(sum(e["stage"] == "tests" for e in events), 2)
        self.assertEqual([e["stage"] for e in events if e["verdict"] == "RELEASED"],
                         ["generation", "security", "generation", "security"])
        for index, event in enumerate(events):
            if event["verdict"] == "SEND":
                self.assertTrue(any(e["verdict"] == "RELEASED" and e["request_id"] == event["request_id"]
                                    for e in events[:index]))

    def test_critical_exhaustion_never_commits(self):
        self.severities = ["Critical"]
        self.assert_blocked("attempts_exhausted")
        self.assertEqual(self.review_count, 3)
        self.assertFalse((loop.gate_dir(self.repo) / "approval.json").exists())

    def test_medium_and_low_warn_and_allow(self):
        for severity in ("Medium", "Low"):
            with self.subTest(severity=severity):
                self.severities = [severity]
                # Run without committing; reset only this isolated synthetic repo.
                self.assertEqual(self.run_loop(commit=False), "PASS_WITH_WARNINGS")
                self.assertIn("security_warning", self.audit.path.read_text())
                loop.verify_receipt(self.repo)
                self.git("reset", "--hard", self.base)

    def test_tests_fail_before_review(self):
        self.config["tests"] = [[sys.executable, "-c", "raise SystemExit(1)"]]
        self.assert_blocked("attempts_exhausted")
        self.assertEqual(self.review_count, 0)

    def test_unknown_severity_blocked(self):
        self.severities = ["Info"]
        self.assert_blocked("invalid_finding")

    def test_incomplete_review_blocked(self):
        self.mutate_review = lambda r: dict(r, coverage_complete=False)
        self.assert_blocked("incomplete_review")

    def test_wrong_location_blocked(self):
        self.severities = ["High"]
        def modify(result):
            result["findings"][0]["line"] = 42
            return result
        self.mutate_review = modify
        self.assert_blocked("invalid_finding_location")

    def test_missing_category_blocked(self):
        self.mutate_review = lambda r: dict(r, checks=[])
        self.assert_blocked("invalid_checks")

    def test_sensitive_task_never_reaches_gateway_or_provider(self):
        self.config["task"] = "Contact synthetic.person@example.test"
        self.assert_blocked("sensitive_data")
        self.assertEqual(self.provider_calls, [])
        self.assertNotIn("synthetic.person", self.audit.path.read_text())
        self.assertIn("email", self.audit.path.read_text())

    def test_sensitive_generation_never_written(self):
        self.generated = {"files": {"sample.py": 'password = "synthetic-do-not-forward"\n'}}
        self.assert_blocked("sensitive_data")
        self.assertEqual((self.repo / "sample.py").read_text(), "value = 0\n")
        self.assertNotIn("synthetic-do-not-forward", self.audit.path.read_text())
        self.assertIn("credential_assignment", self.audit.path.read_text())

    def test_gateway_blocks_direct_escaped_pii_before_provider(self):
        import uuid
        canary = "private.person@example.test"
        content = json.dumps({"files": {"sample.py": canary}}).replace("@", "\\u0040")
        body = {"request_id": str(uuid.uuid4()), "release_id": str(uuid.uuid4()),
                "payload": request("generation", "Synthetic system", parse(content))}
        with self.assertRaises(Blocked):
            post(self.url, body, "synthetic-gateway-credential-only")
        self.assertEqual(self.provider_calls, [])
        self.assertNotIn(canary, self.gateway_audit.path.read_text())
        self.assertIn('"upstream_sent":false', self.gateway_audit.path.read_text())

    def test_path_escape_and_unconfigured_file_rejected(self):
        self.generated = {"files": {"../escaped.py": "value = 1"}}
        self.assert_blocked("generation_out_of_scope")
        self.assertFalse((self.directory / "escaped.py").exists())

    def test_candidate_changed_during_review(self):
        def modify(result):
            (self.repo / "sample.py").write_text("value = 999\n")
            return result
        self.mutate_review = modify
        self.assert_blocked("candidate_changed")

    def test_approval_is_bound_to_staged_tree_and_head(self):
        self.run_loop(commit=False)
        loop.verify_receipt(self.repo)
        (self.repo / "sample.py").write_text("value = 999\n")
        self.git("add", "sample.py")
        with self.assertRaisesRegex(Blocked, "approval_stale"):
            loop.verify_receipt(self.repo)

    def test_pre_commit_hook_blocks_manual_commit_without_approval(self):
        loop.install_hook(self.repo)
        (self.repo / "sample.py").write_text("value = 1\n")
        self.git("add", "sample.py")
        result = subprocess.run(["git", "-C", str(self.repo), "commit", "-m", "must block"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_hook_rejects_mutable_runtime_even_with_approved_candidate(self):
        loop.install_hook(self.repo)
        self.run_loop(commit=False)
        loop.verify_receipt(self.repo)  # A valid tree receipt alone is insufficient.
        result = subprocess.run(["git", "-C", str(self.repo), "commit", "-m", "must block mutable runtime"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"trusted_runtime_unavailable_or_modified", result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_existing_hook_not_overwritten(self):
        hook = self.repo / ".git/hooks/pre-commit"
        hook.write_text("original")
        with self.assertRaisesRegex(Blocked, "existing_pre_commit_hook"):
            loop.install_hook(self.repo)
        self.assertEqual(hook.read_text(), "original")

    def test_auto_commit_does_not_bypass_foreign_hooks(self):
        (self.repo / ".git/hooks/pre-commit").write_text("#!/bin/sh\nexit 1\n")
        self.assert_blocked("existing_hooks_use_manual_commit")
        self.assertEqual(self.provider_calls, [])

    def test_tampered_receipt_is_rejected(self):
        self.run_loop(commit=False)
        path = loop.gate_dir(self.repo) / "approval.json"
        value = json.loads(path.read_text())
        value["payload"]["tree"] = "0" * 40
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(Blocked, "approval_invalid"):
            loop.verify_receipt(self.repo)

    def test_known_gateway_credential_cannot_enter_prompt(self):
        self.config["task"] = "Echo synthetic-gateway-credential-only"
        self.assert_blocked("sensitive_data")
        self.assertEqual(self.provider_calls, [])
        self.assertNotIn("synthetic-gateway-credential-only", self.audit.path.read_text())

    def test_feedback_with_pii_is_blocked(self):
        self.severities = ["High"]
        def modify(result):
            result["findings"][0]["fix"] = "Contact synthetic.person@example.test"
            return result
        self.mutate_review = modify
        self.assert_blocked("sensitive_data")
        self.assertEqual(self.generation_count, 1)

    def test_test_timeout_blocks_without_review(self):
        self.config["test_timeout"] = 1
        self.config["tests"] = [[sys.executable, "-c", "import time; time.sleep(5)"]]
        self.assert_blocked("test_execution_error")
        self.assertEqual(self.review_count, 0)

    def test_audit_failure_prevents_upstream(self):
        with patch.object(self.audit, "event", side_effect=Blocked("audit_unavailable")):
            self.assert_blocked("audit_unavailable")
        self.assertEqual(self.provider_calls, [])

    def test_provider_timeout_no_fallback_no_commit(self):
        with patch.object(adapters, "post", side_effect=Blocked("transport_error")):
            self.assert_blocked("transport_error")
        self.assertEqual(self.review_count, 0)

    def test_truncated_response_blocks(self):
        with patch.object(adapters, "post", return_value={"choices": [{"finish_reason": "length", "message": {"content": "{}"}}]}):
            self.assert_blocked("incomplete_response")

    def test_dirty_checkout_is_preserved(self):
        (self.repo / "foreign.txt").write_text("keep me")
        self.assert_blocked("checkout_not_clean")
        self.assertEqual((self.repo / "foreign.txt").read_text(), "keep me")
        self.assertEqual(self.provider_calls, [])


class DisclosureTests(unittest.TestCase):
    """No fixture auto-approval: exercise the production disclosure boundary."""
    tearDown = LoopTests.tearDown
    stop_http = LoopTests.stop_http
    git = LoopTests.git
    provider = LoopTests.provider

    def setUp(self):
        LoopTests.setUp(self)
        self.client = GuardedFlow(GatewayClient(self.url, "synthetic-gateway-credential-only", self.audit, self.policy), self.adapter)
        self.system = "Implement only the supplied task"
        self.context = {"task": "Improve validation", "files": {"sample.py": "value = 0\n"}, "feedback": []}

    def approve(self, stage="generation", context=None, replacements=None, approved=True):
        entry = draft_release(request(stage, self.system, context or self.context), replacements or [])
        entry["approved"] = approved
        self.policy.path.write_bytes(encode({"schema_version": 1, "releases": [entry]}))
        return entry

    def assert_not_sent(self):
        self.assertEqual(self.provider_calls, [])
        if self.gateway_audit.path.exists():
            self.assertNotIn('"verdict":"SEND"', self.gateway_audit.path.read_text())

    def test_clean_regex_result_is_not_authorization(self):
        self.assertEqual(scan(self.context), {})
        with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
            self.client.call("generation", self.system, self.context)
        self.assert_not_sent()

    def test_loop_constructs_gate_even_for_an_unguarded_backend(self):
        from unittest.mock import Mock
        backend = Mock()
        with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
            loop.run(self.repo, self.config, backend, self.audit, releases=self.policy, commit=True)
        backend.complete.assert_not_called()
        backend.call.assert_not_called()
        self.assertEqual(self.git("rev-parse", "HEAD"), self.base)

    def test_no_policy_cannot_disable_built_in_gate(self):
        from unittest.mock import Mock
        backend = Mock()
        with self.assertRaisesRegex(Blocked, "release_policy_required"):
            loop.run(self.repo, self.config, backend, self.audit)
        backend.complete.assert_not_called()

    def test_model_switch_preserves_same_embedded_policy(self):
        self.approve()
        before = self.policy.path.read_bytes()
        seen = []
        class ModelA:
            def complete(self, public, audit, request_id):
                seen.append(("A", public))
                return {"files": {"sample.py": "value = 1\n"}}
        class ModelB:
            def complete(self, public, audit, request_id):
                seen.append(("B", public))
                return {"files": {"sample.py": "value = 2\n"}}
        gate = ContextGate(self.audit, self.policy)
        for model in (ModelA(), ModelB()):
            GuardedFlow(gate, model).call("generation", self.system, self.context)
        self.assertEqual(self.policy.path.read_bytes(), before)
        self.assertEqual([name for name, _ in seen], ["A", "B"])
        self.assertEqual(seen[0][1], seen[1][1])
        self.assert_not_sent()

    def test_embedded_gate_rechecks_revocation_and_logs_block(self):
        from unittest.mock import Mock
        self.approve()
        real_release = self.policy.release
        def revoke_after_release(*args):
            entry = real_release(*args)
            self.policy.path.write_bytes(encode({"schema_version": 1, "releases": []}))
            return entry
        backend = Mock()
        with patch.object(self.policy, "release", revoke_after_release), self.assertRaises(Blocked):
            GuardedFlow(ContextGate(self.audit, self.policy), backend).call("generation", self.system, self.context)
        backend.complete.assert_not_called()
        self.assertIn("export_not_authorized", self.audit.path.read_text())

    def test_gateway_authorizes_without_model_or_provider(self):
        self.approve()
        self.client.adapter = None
        public = self.client.authorize("generation", self.system, self.context)
        self.assertEqual(public, request("generation", self.system, self.context))
        self.assertFalse(hasattr(self.service, "model"))
        self.assertFalse(hasattr(self.service, "upstream"))
        self.assert_not_sent()

    def test_same_gate_supports_json_protocol_without_chat_fields(self):
        self.context["task"] = "Fix for Ivan Petrov"
        self.approve(replacements=[{"private": "Ivan Petrov", "public": "REDACTED_PERSON_1"}])
        self.client.adapter = adapters.JsonHttpAdapter("https://provider.invalid/custom", "synthetic-provider-credential")
        captured = []
        def generic_provider(url, body, token):
            captured.append(body)
            return {"files": {"sample.py": "value = 1\n"}}
        with patch.object(adapters, "post", generic_provider):
            self.client.call("generation", self.system, self.context)
        self.assertEqual(set(captured[0]), {"stage", "system", "context"})
        self.assertNotIn("Ivan Petrov", encode(captured).decode())
        self.assertIn("REDACTED_PERSON_1", encode(captured).decode())
        self.assertNotIn('"verdict":"SEND"', self.gateway_audit.path.read_text())

    def test_custom_local_model_needs_no_network_protocol(self):
        self.approve()
        received = []
        class LocalModel:
            def complete(self, public, audit, request_id):
                received.append(public)
                return {"files": {"sample.py": "value = 1\n"}}
        self.client.adapter = LocalModel()
        self.client.call("generation", self.system, self.context)
        self.assertEqual(received, [request("generation", self.system, self.context)])
        self.assertNotIn('"upstream_sent":true', self.audit.path.read_text())
        self.assert_not_sent()

    def test_denial_prevents_any_model_adapter_call(self):
        from unittest.mock import Mock
        self.client.adapter = Mock()
        with self.assertRaises(Blocked):
            self.client.call("generation", self.system, self.context)
        self.client.adapter.complete.assert_not_called()
        self.assert_not_sent()

    def test_unknown_pii_comments_encoded_secrets_and_paths_block(self):
        samples = ["Ivan Petrov lives at 12 Maple Lane", "customer_4829017",
                   "custom credential orchid river twelve", "aXZhbiBwZXRyb3Y=",
                   "// customer Ivan Petrov", "team/IvanPetrov/private_notes.py"]
        for value in samples:
            with self.subTest(value=value):
                ctx = dict(self.context, task=value)
                self.assertEqual(scan(ctx), {})
                with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
                    self.client.call("generation", self.system, ctx)
        self.assert_not_sent()

    def test_pending_draft_never_authorizes(self):
        self.approve(approved=False)
        with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
            self.client.call("generation", self.system, self.context)
        self.assert_not_sent()

    def test_exact_release_allows_only_sanitized_task(self):
        private = "Ivan Petrov lives at 12 Maple Lane"
        self.context["task"] = "Improve validation for " + private
        self.approve(replacements=[{"private": private, "public": "REDACTED_PERSON_1"}])
        self.client.call("generation", self.system, self.context)
        sent = encode(self.provider_calls).decode()
        self.assertIn("REDACTED_PERSON_1", sent)
        for forbidden in (private, "Ivan Petrov", "Maple Lane"):
            self.assertNotIn(forbidden, sent)
            self.assertNotIn(forbidden, self.audit.path.read_text())
            self.assertNotIn(forbidden, self.gateway_audit.path.read_text())

    def test_redacts_system_code_comments_and_file_identity(self):
        private_path = "team/IvanPetrov/private_notes.py"
        self.system = "Review data for Ivan Petrov"
        context = {"snapshot_id": "synthetic-snapshot", "files": {private_path: '# Ivan Petrov\nname = "Ivan Petrov"\n'}}
        self.approve("security", context, [{"private": private_path, "public": "sample.py"},
                                           {"private": "Ivan Petrov", "public": "REDACTED_PERSON_1"}])
        self.client.call("security", self.system, context)
        sent = encode(self.provider_calls).decode()
        self.assertNotIn("Ivan", sent)
        self.assertNotIn("private_notes", sent)
        self.assertIn("REDACTED_PERSON_1", sent)

    def test_every_changed_channel_invalidates_approval(self):
        import copy
        self.approve()
        variants = []
        ctx = copy.deepcopy(self.context); ctx["task"] += " Ivan Petrov"; variants.append((self.system, ctx))
        ctx = copy.deepcopy(self.context); ctx["files"]["sample.py"] += "# private address"; variants.append((self.system, ctx))
        ctx = copy.deepcopy(self.context); ctx["feedback"] = ["Ivan Petrov"]; variants.append((self.system, ctx))
        ctx = copy.deepcopy(self.context); ctx["tool_output"] = "private data"; variants.append((self.system, ctx))
        ctx = copy.deepcopy(self.context); ctx["files"]["private.py"] = "hidden"; variants.append((self.system, ctx))
        variants.append((self.system + " Ivan Petrov", self.context))
        for system, context in variants:
            with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
                self.client.call("generation", system, context)
        self.assert_not_sent()

    def test_direct_gateway_cannot_bypass_with_release_id(self):
        import uuid
        entry = self.approve()
        body = {"request_id": str(uuid.uuid4()), "release_id": entry["id"],
                "payload": request("generation", self.system, dict(self.context, task="Ivan Petrov"))}
        with self.assertRaises(Blocked):
            post(self.url, body, "synthetic-gateway-credential-only")
        self.assert_not_sent()
        self.assertIn("export_not_authorized", self.gateway_audit.path.read_text())

    def test_gateway_denies_missing_server_policy(self):
        self.approve()
        self.service.releases = None
        with self.assertRaises(Blocked):
            self.client.call("generation", self.system, self.context)
        self.assert_not_sent()

    def test_gateway_rechecks_revocation_before_upstream(self):
        import loop_security
        self.approve()
        def revoke_then_post(*args, **kwargs):
            self.policy.path.write_bytes(encode({"schema_version": 1, "releases": []}))
            return post(*args, **kwargs)
        with patch("context_gate.post", revoke_then_post), self.assertRaises(Blocked):
            self.client.call("generation", self.system, self.context)
        self.assert_not_sent()

    def test_redacted_source_cannot_be_blindly_rehydrated(self):
        self.context["files"]["sample.py"] = 'owner = "Ivan Petrov"\n'
        self.approve(replacements=[{"private": "Ivan Petrov", "public": "REDACTED_PERSON_1"}])
        with self.assertRaisesRegex(Blocked, "redacted_source_is_read_only"):
            self.client.call("generation", self.system, self.context)
        self.assertEqual((self.repo / "sample.py").read_text(), "value = 0\n")
        self.assertNotIn("Ivan Petrov", encode(self.provider_calls).decode())

    def test_aliases_return_to_local_names_only(self):
        original = "team/IvanPetrov/module.py"
        self.context["files"] = {original: "value = 0\n"}
        self.approve(replacements=[{"private": original, "public": "sample.py"}])
        result = self.client.call("generation", self.system, self.context)
        self.assertIn(original, result["files"])
        self.assertNotIn(original, encode(self.provider_calls).decode())

    def test_forged_export_hash_and_ambiguous_redactions_block(self):
        entry = self.approve()
        entry["export"]["context"]["task"] = "different"
        self.policy.path.write_bytes(encode({"schema_version": 1, "releases": [entry]}))
        with self.assertRaisesRegex(Blocked, "invalid_release_policy"):
            self.client.call("generation", self.system, self.context)
        from context_release import transform
        with self.assertRaisesRegex(Blocked, "redaction_key_collision"):
            transform({"A": "first", "B": "second"}, [{"private": "A", "public": "X"}, {"private": "B", "public": "X"}])
        self.assert_not_sent()

    def test_default_does_not_write_private_context_to_disk(self):
        self.context["task"] = "Ivan Petrov"
        with self.assertRaises(Blocked):
            self.client.call("generation", self.system, self.context)
        self.assertNotIn("Ivan Petrov", self.audit.path.read_text())
        self.assertFalse(list(self.directory.glob("*-*-*-*-*.json")))
        self.assert_not_sent()

    def test_security_resume_requires_its_own_reviewed_release(self):
        self.system = loop.GENERATION_PROMPT
        self.context = {"task": self.config["task"], "files": {"sample.py": "value = 0\n"}, "feedback": []}
        self.approve()
        self.client.capture_dir = self.directory / "private-quarantine"
        with patch.dict(os.environ, {"PYTHONDONTWRITEBYTECODE": "1"}):
            with self.assertRaisesRegex(Blocked, "context_not_explicitly_released"):
                loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.client.releases, capture_dir=self.client.capture_dir, commit=True)
            self.assertEqual(self.git("rev-parse", "HEAD"), self.base)
            self.assertEqual((self.generation_count, self.review_count), (1, 0))
            captured = list(self.client.capture_dir.glob("*.json"))
            self.assertEqual(len(captured), 1)
            pending = parse(captured[0].read_bytes())
            self.assertEqual(pending["stage"], "security")
            entry = draft_release(pending, [])
            entry["approved"] = True  # Explicit synthetic operator review, outside product code.
            data = parse(self.policy.path.read_bytes())
            data["releases"].append(entry)
            self.policy.path.write_bytes(encode(data))
            self.assertEqual(loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.client.releases, capture_dir=self.client.capture_dir, commit=True, resume_stage="security"), "PASS")
        self.assertNotEqual(self.git("rev-parse", "HEAD"), self.base)
        self.assertEqual((self.generation_count, self.review_count), (1, 1))

    def test_policy_inside_checkout_is_forbidden(self):
        self.client.releases = ReleasePolicy(self.repo / ".git/releases.json")
        self.client.releases.path.write_bytes(encode({"schema_version": 1, "releases": []}))
        with self.assertRaisesRegex(Blocked, "release_policy_inside_generated_checkout"):
            loop.run(self.repo, self.config, self.adapter, self.audit, releases=self.client.releases)
        self.assert_not_sent()

    def test_declared_private_value_echo_in_response_is_blocked(self):
        self.context["task"] = "Update for Ivan Petrov"
        self.approve(replacements=[{"private": "Ivan Petrov", "public": "REDACTED_PERSON_1"}])
        self.generated = {"files": {"sample.py": '# Ivan Petrov\nvalue = 1\n'}}
        with self.assertRaisesRegex(Blocked, "private_value_in_response"):
            self.client.call("generation", self.system, self.context)
        self.assertNotIn("Ivan Petrov", self.audit.path.read_text())
        self.assertIn("private_value_in_response", self.audit.path.read_text())

    def test_offline_preparation_cannot_grant_approval(self):
        import context_release
        raw = self.directory / "private-request.json"
        rules = self.directory / "redactions.json"
        draft = self.directory / "draft.json"
        raw.write_bytes(encode(request("generation", self.system, self.context)))
        rules.write_bytes(encode([]))
        result = subprocess.run([sys.executable, str(Path(context_release.__file__)),
                                 "--request", str(raw), "--redactions", str(rules), "--out", str(draft)], capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertFalse(parse(draft.read_bytes())["releases"][0]["approved"])
        self.assert_not_sent()

    def test_missing_corrupt_and_revoked_policy_fail_closed(self):
        self.approve()
        for content in (b"not json", encode({"schema_version": 1, "releases": []})):
            self.policy.path.write_bytes(content)
            with self.assertRaises(Blocked):
                self.client.call("generation", self.system, self.context)
        self.policy.path.unlink()
        with self.assertRaises(Blocked):
            self.client.call("generation", self.system, self.context)
        self.assert_not_sent()


class PolicyTests(unittest.TestCase):
    def test_detectors(self):
        samples = {
            "email": "synthetic.person@example.test", "phone": "+1 (202) 555-0199",
            "private_key": "-----BEGIN PRIVATE KEY-----", "ssn": "123-45-6789",
            "provider_token": "sk-" + "syntheticCanary12345678",
            "credential_assignment": 'api_key = "syntheticvalue"',
            "bearer": "Bearer syntheticcredential", "jwt": "eyJabcdefgh.abcdefgh.abcdefgh",
            "credential_url": "https://user:password@example.invalid/",
        }
        for rule, value in samples.items():
            with self.subTest(rule=rule):
                self.assertIn(rule, scan({"nested": [value]}))

    def test_duplicates_and_nonfinite_json_rejected(self):
        for raw in ('{"severity":"High","severity":"Low"}', '{"value":NaN}'):
            with self.assertRaises(Blocked):
                parse(raw)

    def test_remote_or_credentialled_gateway_rejected(self):
        for url in ("https://provider.invalid/v1/authorize", "http://user:pass@127.0.0.1/v1/authorize"):
            with self.assertRaises(Blocked):
                GatewayClient(url, "unused", None)

    def test_windows_aliases_and_git_pathspec_are_rejected(self):
        root = Path.cwd().resolve()
        for name in (".git./config", "src/file. ", "src/*.py", "src/CON.txt", "../file.py", "src/../file.py"):
            with self.subTest(name=name), self.assertRaises(Blocked):
                loop.source_path(root, name)


if __name__ == "__main__":
    unittest.main()
