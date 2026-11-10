"""Local data gate: no model binding, network or credentials in the default path."""
from pathlib import Path
import uuid
from loop_security import Blocked, POLICY, encode, endpoint, post


class ContextGate:
    def __init__(self, audit, releases=None, capture_dir=None):
        self.audit, self.releases, self.capture_dir = audit, releases, capture_dir

    def _verify(self, entry, request_id):
        # Same model-independent checks used by the optional HTTP service.
        self.releases.verify_payload(entry["id"], entry["export"])

    def _authorize(self, stage, system, context):
        request_id = str(uuid.uuid4())
        try:
            if self.releases is None:
                raise Blocked("release_policy_required")
            entry = self.releases.release(stage, system, context)
        except Blocked:
            self.audit.event(stage, "BLOCK", request_id=request_id, reason="context_not_released")
            if self.capture_dir is not None:
                # Explicit opt-in local quarantine, NOT an audit log or LLM prompt.
                folder = Path(self.capture_dir)
                folder.mkdir(parents=True, exist_ok=True)
                path = folder / (request_id + ".json")
                with path.open("xb") as stream:
                    stream.write(encode({"stage": stage, "system": system, "context": context}))
                path.chmod(0o600)
            raise
        exported = entry["export"]
        self.audit.check(exported, stage, request_id=request_id, direction="context")
        try:
            self._verify(entry, request_id)
        except Blocked:
            self.audit.event(stage, "BLOCK", request_id=request_id, reason="export_not_authorized")
            raise
        self.audit.event(stage, "RELEASED", request_id=request_id, release_id=entry["id"], reason="exact_export_approved")
        return entry, request_id

    def authorize(self, stage, system, context):
        """Use the local data gate without any model or adapter configured."""
        entry, _ = self._authorize(stage, system, context)
        return entry["export"]

class GatewayClient(ContextGate):
    """Optional HTTP boundary for integrations outside the built-in loop."""
    def __init__(self, url, token, audit, releases=None, capture_dir=None):
        super().__init__(audit, releases, capture_dir)
        self.url, self.token = endpoint(url, local=True), token
        self.audit.protect(token)

    def _verify(self, entry, request_id):
        exported = entry["export"]
        body = {"request_id": request_id, "release_id": entry["id"], "payload": exported}
        self.audit.check(body, exported["stage"], request_id=request_id, direction="request")
        reply = post(self.url, body, self.token)
        if (not isinstance(reply, dict) or set(reply) != {"request_id", "policy", "release_id", "payload"}
                or reply["request_id"] != request_id or reply["policy"] != POLICY
                or reply["release_id"] != entry["id"] or reply["payload"] != exported):
            raise Blocked("invalid_gateway_reply")
        self.audit.check(reply, exported["stage"], request_id=request_id, direction="local_gate_response")
