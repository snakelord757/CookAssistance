"""Behavioral connector conformance check with synthetic data and a real local sink.

A trusted connector command receives --probe <JSON path>. It must exercise its
normal outbound boundary, using the supplied policy and synthetic destination.
This certifies that command/configuration, never unrelated processes or routes.
"""
import argparse
import copy
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading

from context_release import draft_release, request
from loop_security import Audit, Blocked, LIMIT, POLICY, STAGES, digest, encode, parse


def check_connector(command, audit, directory):
    if not isinstance(command, list) or not command or any(not isinstance(a, str) or not a for a in command):
        raise Blocked("invalid_connector_command")
    received = []
    class Sink(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            self.connection.settimeout(3)
            # Preserve even malformed/oversized arrivals: a forbidden send is FAIL.
            try:
                size = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(min(max(size, 0), LIMIT + 1))
            except Exception:
                raw = b"invalid"
            received.append(raw)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")

    server = HTTPServer(("127.0.0.1", 0), Sink)
    server.timeout = 5
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    cases = 0
    try:
        with tempfile.TemporaryDirectory(dir=directory) as temp:
            folder = Path(temp)
            nonce = secrets.token_hex(16)
            for stage in sorted(STAGES):
                private = request(stage, "Synthetic preflight only", {
                    "messages": [{"text": "Private Person " + nonce}],
                    "tools": [{"output": "Private Person " + nonce}],
                    "history": ["Private Person " + nonce],
                    "attachments": [{"text": "Private Person " + nonce}],
                })
                release = draft_release(private, [{"private": "Private Person " + nonce, "public": "ANON_PERSON"}])
                release["approved"] = True  # ONLY this newly generated synthetic fixture.
                for case in ("released", "unreleased", "changed", "revoked", "audit_failure"):
                    policy = {"schema_version": 1, "releases": [release] if case != "unreleased" else []}
                    policy = copy.deepcopy(policy)
                    if case == "revoked":
                        policy["releases"][0]["approved"] = False
                    payload = copy.deepcopy(private)
                    if case == "changed":
                        payload["context"]["tools"].append({"output": "hidden " + nonce})
                    policy_path = folder / "policy.json"
                    policy_path.write_bytes(encode(policy))
                    audit_path = folder if case == "audit_failure" else folder / "probe-audit.jsonl"
                    probe = {"request": payload, "policy": str(policy_path), "audit": str(audit_path),
                             "destination": f"http://127.0.0.1:{server.server_port}/sink",
                             "token": "synthetic-preflight-credential"}
                    probe_path = folder / "probe.json"
                    probe_path.write_bytes(encode(probe))
                    start = len(received)
                    try:
                        result = subprocess.run([*command, "--probe", str(probe_path)],
                                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                                stderr=subprocess.DEVNULL, timeout=20, check=False)
                    except (OSError, subprocess.TimeoutExpired):
                        raise Blocked("connector_execution_failed") from None
                    arrivals = received[start:]
                    if case == "released":
                        if result.returncode != 0 or arrivals != [encode(release["export"])]:
                            raise Blocked("connector_release_probe_failed")
                    elif result.returncode != 2 or arrivals:
                        raise Blocked("connector_deny_probe_failed")
                    cases += 1
            audit.event("preflight", "PASS", reason="connector_synthetic_http_verified")
            return {"schema_version": 1, "policy": POLICY, "result": "PASS",
                    "scope": "tested_connector_only", "cases": cases,
                    "stages": sorted(STAGES), "command_sha256": digest(command)}
    except Exception:
        audit.event("preflight", "BLOCK", reason="connector_probe_failed")
        raise
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def load_connector(path, repo):
    path = Path(path).resolve()
    if path.is_relative_to(Path(repo).resolve()):
        raise Blocked("connector_config_inside_generated_checkout")
    try:
        config = parse(path.read_bytes())
    except OSError:
        raise Blocked("connector_config_unavailable") from None
    if not isinstance(config, dict) or set(config) != {"command"}:
        raise Blocked("invalid_connector_config")
    command = config["command"]
    if not isinstance(command, list) or not command or any(not isinstance(a, str) or not a for a in command):
        raise Blocked("invalid_connector_command")
    builtins = {Path(__file__).with_name(name).resolve() for name in ("builtin_connector.py", "trusted_runtime.py")}
    if any(Path(arg).resolve() in builtins for arg in command):
        raise Blocked("builtin_probe_is_not_external_harness")
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connector-config", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--audit", type=Path, required=True)
    args = parser.parse_args()
    try:
        audit = Audit(args.audit)
        report = check_connector(load_connector(args.connector_config, args.repo), audit, args.audit.parent)
        print(encode(report).decode())
        return 0
    except Exception:
        print("BLOCKED: connector_preflight_failed")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
