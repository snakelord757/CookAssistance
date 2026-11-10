"""Mandatory execution-flow boundary around any model backend."""
from loop_security import Blocked, encode, parse


class GuardedFlow:
    def __init__(self, gate, backend, integrity=None):
        self.gate, self.adapter = gate, backend
        self.audit = gate.audit
        self.integrity = integrity

    def verify_integrity(self):
        if self.integrity is not None:
            try:
                self.integrity.verify()
            except Blocked:
                self.audit.event("integrity", "BLOCK", reason="trusted_runtime_changed")
                raise

    @property
    def releases(self):
        return self.gate.releases

    @releases.setter
    def releases(self, value):
        self.gate.releases = value

    @property
    def capture_dir(self):
        return self.gate.capture_dir

    @capture_dir.setter
    def capture_dir(self, value):
        self.gate.capture_dir = value

    def authorize(self, stage, system, context):
        return self.gate.authorize(stage, system, context)

    def call(self, stage, system, context):
        self.verify_integrity()
        entry, request_id = self.gate._authorize(stage, system, context)
        if self.adapter is None:
            raise Blocked("model_adapter_required_by_loop")
        exported = entry["export"]
        self.verify_integrity()
        # Only the public object crosses this boundary, never private context/rules.
        result = self.adapter.complete(parse(encode(exported)), self.audit, request_id)
        self.verify_integrity()
        self.audit.check(result, stage, request_id=request_id, direction="model_result")
        public_files = exported["context"].get("files", {})
        private_files = context.get("files", {})
        # Only restore file identities, never private values inside generated code.
        # Redacted files remain read-only: no unsafe blind rehydration of secrets.
        from context_release import transform
        if transform(result, entry["replacements"]) != result:
            self.audit.event(stage, "BLOCK", request_id=request_id, direction="model_result", reason="private_value_in_response")
            raise Blocked("private_value_in_response")
        aliases = {transform(name, entry["replacements"]): name for name in private_files}
        if stage == "generation" and isinstance(result, dict) and isinstance(result.get("files"), dict):
            restored = {}
            for name, content in result["files"].items():
                if name not in aliases:
                    raise Blocked("generation_out_of_scope")
                original = aliases[name]
                if public_files.get(name) != private_files[original]:
                    raise Blocked("redacted_source_is_read_only")
                restored[original] = content
            result = dict(result, files=restored)
        elif stage == "security" and isinstance(result, dict) and isinstance(result.get("findings"), list):
            for finding in result["findings"]:
                if isinstance(finding, dict) and finding.get("file") in aliases:
                    finding["file"] = aliases[finding["file"]]
        return result
