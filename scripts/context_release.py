"""Explicit local declassification. A detector's silence is never permission.

The operator-controlled policy lives outside the generated checkout. Every
release binds one exact private input to one reviewed public export. No wildcard,
path-only, regex, model-produced or automatically inherited approval is accepted.
"""
from __future__ import annotations

import argparse
import copy
from pathlib import Path
import re
import uuid

from loop_security import Blocked, LIMIT, STAGES, digest, encode, parse, scan


def request(stage, system, context):
    if stage not in STAGES or not isinstance(system, str) or not isinstance(context, dict):
        raise Blocked("invalid_release_request")
    return {"stage": stage, "system": system, "context": context}


def transform(value, replacements):
    """Simultaneous literal substitutions, including keys; no chained redaction."""
    if not isinstance(replacements, list):
        raise Blocked("invalid_redactions")
    mapping = {}
    for item in replacements:
        if not isinstance(item, dict) or set(item) != {"private", "public"}:
            raise Blocked("invalid_redactions")
        old, new = item["private"], item["public"]
        if not isinstance(old, str) or not old or not isinstance(new, str) or not new or old == new or old in mapping or "\n" in old or "\n" in new or "\r" in old or "\r" in new:
            raise Blocked("invalid_redactions")
        mapping[old] = new
    if any(old in new for old in mapping for new in mapping.values()):
        raise Blocked("redaction_contains_private_value")
    pattern = re.compile("|".join(re.escape(s) for s in sorted(mapping, key=len, reverse=True))) if mapping else None

    def visit(item):
        if isinstance(item, str):
            return pattern.sub(lambda match: mapping[match[0]], item) if pattern else item
        if isinstance(item, dict):
            out = {}
            for key, child in item.items():
                public_key = visit(key)
                if public_key in out:
                    raise Blocked("redaction_key_collision")
                out[public_key] = visit(child)
            return out
        if isinstance(item, list):
            return [visit(child) for child in item]
        if item is None or type(item) in {bool, int}:
            return item
        raise Blocked("unsupported_context_type")
    exported = visit(value)
    # Overlapping replacements must not leave any declared private value behind.
    def strings(item):
        if isinstance(item, str):
            yield item
        elif isinstance(item, dict):
            for k, v in item.items():
                yield k
                yield from strings(v)
        elif isinstance(item, list):
            for v in item:
                yield from strings(v)
    if any(old in text for text in strings(exported) for old in mapping):
        raise Blocked("incomplete_redaction")
    return exported


def draft_release(private_request, replacements):
    """Offline only: no network, no approval. Output remains quarantine material."""
    public = transform(private_request, replacements)
    if scan(public):
        raise Blocked("sensitive_export")
    return {"id": str(uuid.uuid4()), "approved": False,
            "input_sha256": digest(private_request), "export_sha256": digest(public),
            "replacements": replacements, "export": public}


class ReleasePolicy:
    def __init__(self, path):
        self.path = Path(path).resolve()

    def entries(self):
        try:
            if self.path.is_symlink() or self.path.stat().st_size > LIMIT * 4:
                raise Blocked("invalid_release_policy")
            data = parse(self.path.read_bytes())
            if not isinstance(data, dict) or set(data) != {"schema_version", "releases"} or data["schema_version"] != 1 or not isinstance(data["releases"], list):
                raise Blocked("invalid_release_policy")
            seen = set()
            for entry in data["releases"]:
                if not isinstance(entry, dict) or set(entry) != {"id", "approved", "input_sha256", "export_sha256", "replacements", "export"}:
                    raise Blocked("invalid_release_policy")
                if str(uuid.UUID(entry["id"])) != entry["id"] or entry["id"] in seen or type(entry["approved"]) is not bool:
                    raise Blocked("invalid_release_policy")
                seen.add(entry["id"])
                if not re.fullmatch(r"[a-f0-9]{64}", entry["input_sha256"]) or digest(entry["export"]) != entry["export_sha256"]:
                    raise Blocked("invalid_release_policy")
            return data["releases"]
        except Blocked:
            raise
        except Exception:
            raise Blocked("release_policy_unavailable") from None

    def release(self, stage, system, context):
        private = request(stage, system, context)
        matching = [e for e in self.entries() if e["approved"] and e["input_sha256"] == digest(private)]
        if len(matching) != 1:
            raise Blocked("context_not_explicitly_released")
        entry = matching[0]
        exported = transform(private, entry["replacements"])
        if exported != entry["export"] or digest(exported) != entry["export_sha256"]:
            raise Blocked("export_mismatch")
        if scan(exported):
            raise Blocked("sensitive_export")
        return copy.deepcopy(entry)

    def verify_payload(self, release_id, exported):
        matching = [e for e in self.entries() if e["approved"] and e["id"] == release_id]
        if len(matching) != 1:
            raise Blocked("release_not_authorized")
        entry = matching[0]
        if exported != entry["export"] or digest(exported) != entry["export_sha256"]:
            raise Blocked("export_mismatch")
        # Prevent accidental approval of an export still containing declared PII.
        if transform(exported, entry["replacements"]) != exported or scan(exported):
            raise Blocked("sensitive_export")
        return copy.deepcopy(entry)


def main():
    parser = argparse.ArgumentParser(description="Prepare an UNAPPROVED local disclosure draft; never sends data")
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--redactions", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        entry = draft_release(parse(args.request.read_bytes()), parse(args.redactions.read_bytes()))
        # Exclusive creation avoids overwriting an approved policy by accident.
        with args.out.open("xb") as stream:
            stream.write(encode({"schema_version": 1, "releases": [entry]}))
        args.out.chmod(0o600)
        print("UNAPPROVED: inspect every exported field locally before enabling this release")
        return 0
    except Exception:
        print("BLOCKED: could_not_prepare_release")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
