"""Local disclosure gate. No model, provider protocol or outbound connection."""
from __future__ import annotations

import argparse
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import os
import sys
from pathlib import Path
import uuid

from loop_security import Audit, Blocked, LIMIT, POLICY, STAGES, encode, parse
from context_release import ReleasePolicy


class Gateway:
    def __init__(self, audit, releases=None, integrity=None):
        self.audit, self.releases = audit, releases
        self.integrity = integrity

    def handle(self, body):
        if self.integrity:
            self.integrity.verify()
        if not isinstance(body, dict) or set(body) != {"request_id", "release_id", "payload"}:
            raise Blocked("invalid_request")
        request_id, public = body["request_id"], body["payload"]
        try:
            if str(uuid.UUID(request_id)) != request_id:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise Blocked("invalid_request_id") from None
        if not isinstance(public, dict) or set(public) != {"stage", "system", "context"}:
            raise Blocked("invalid_payload")
        stage = public["stage"]
        if stage not in STAGES:
            raise Blocked("invalid_stage")
        self.audit.check(public, stage, request_id=request_id, direction="context")
        try:
            if self.releases is None:
                raise Blocked("release_policy_required")
            release = self.releases.verify_payload(body["release_id"], public)
        except Blocked:
            self.audit.event(stage, "BLOCK", request_id=request_id, reason="export_not_authorized")
            raise
        self.audit.event(stage, "RELEASED", request_id=request_id, release_id=release["id"], reason="exact_export_approved")
        if self.integrity:
            self.integrity.verify()
        # Return only the trusted approved object. This process never calls a model.
        return {"request_id": request_id, "policy": POLICY,
                "release_id": release["id"], "payload": release["export"]}


def server(gateway, token, port=8765):
    if not token or len(token) < 24:
        raise Blocked("gateway_token_too_short")
    gateway.audit.protect(token)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Base handler logs request lines; they may contain hostile PII.

        def do_POST(self):
            self.connection.settimeout(15)
            try:
                if self.path != "/v1/authorize" or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                    raise Blocked("unauthorized")
                if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type") != "application/json":
                    raise Blocked("invalid_headers")
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= LIMIT:
                    raise Blocked("invalid_size")
                raw = self.rfile.read(size)
                if len(raw) != size:
                    raise Blocked("incomplete_request")
                response = gateway.handle(parse(raw))
                status = 200
            except Exception:
                try:
                    gateway.audit.event("gateway", "BLOCK", reason="request_rejected")
                except Exception:
                    pass  # Still fail closed when the audit volume is unavailable.
                status, response = 403, {"error": "request_blocked"}
            data = encode(response)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return HTTPServer(("127.0.0.1", port), Handler)


def main():
    try:
        from trusted_runtime import verify_install
        if not sys.flags.isolated or not sys.flags.no_site or not sys.dont_write_bytecode:
            raise ValueError()
        verify_install(Path(__file__).absolute().parents[1])
    except Exception:
        print("BLOCKED: protected_runtime_required")
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", required=True)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--release-policy", required=True, help="Operator-controlled local disclosure approvals")
    args = parser.parse_args()
    try:
        releases = ReleasePolicy(args.release_policy)
        releases.entries()  # Invalid/missing policy prevents server startup.
        from runtime_integrity import RuntimeIntegrity
        integrity = RuntimeIntegrity(installed=True, config_paths=[args.release_policy])
        gateway = Gateway(Audit(args.audit), releases, integrity)
        server(gateway, os.environ.get("LOOP_GATEWAY_TOKEN"), args.port).serve_forever()
    except KeyboardInterrupt:
        return 0
    except Exception:
        print("BLOCKED: gateway_start_or_runtime_error")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
