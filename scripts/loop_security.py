"""Shared fail-closed policy. No payloads or exception text enter the audit."""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit
from urllib.error import HTTPError
from urllib.request import build_opener, ProxyHandler, HTTPRedirectHandler, Request

POLICY = "loop-disclosure-v2"
LIMIT = 512_000
STAGES = frozenset({"planning", "generation", "correction", "review", "security",
                    "summarization", "title", "tool_result", "retry"})


class Blocked(Exception):
    """Only constant reason codes may be passed here."""


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(encode(value)).hexdigest()


def parse(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise Blocked("duplicate_json_key")
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(Blocked("invalid_json")))
    except (ValueError, UnicodeError, RecursionError):
        raise Blocked("invalid_json") from None


# Deliberately conservative. Detection is defense in depth, not a universal PII oracle.
RULES = {
    "private_key": re.compile(r"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    "provider_token": re.compile(r"\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b"),
    "credential_assignment": re.compile(r'''(?ix)["']?\b(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret)\b["']?\s*[:=]\s*["'][^"'\r\n]{4,}["']'''),
    "bearer": re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]{8,}"),
    "jwt": re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
    "email": re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"),
    "phone": re.compile(r"(?<!\w)(?:\+\d[\d ()-]{8,}\d|\(\d{3}\)\s*\d{3}[- ]\d{4})(?!\w)"),
    "ssn": re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),
    "credential_url": re.compile(r"(?i)https?://[^\s/@]+:[^\s/@]+@"),
}


def scan(value):
    """Scan decoded JSON strings (including keys), never serialized escapes alone."""
    counts = {}
    def visit(item):
        if isinstance(item, dict):
            for key, child in item.items():
                visit(key)
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)
        elif isinstance(item, str):
            for rule, pattern in RULES.items():
                count = len(pattern.findall(item))
                if count:
                    counts[rule] = counts.get(rule, 0) + count
            # Only quoted high-entropy literals; hashes/UUID identifiers stay usable.
            for token in re.findall(r'''["']([A-Za-z0-9+/=_-]{24,})["']''', item):
                entropy = -sum((token.count(c)/len(token)) * math.log2(token.count(c)/len(token)) for c in set(token))
                if entropy >= 4.3 and not re.fullmatch(r"[a-fA-F0-9]+", token):
                    counts["entropy_literal"] = counts.get("entropy_literal", 0) + 1
    visit(value)
    return counts


class Audit:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.sensitive_values = []

    def protect(self, value):
        if isinstance(value, str) and value and value not in self.sensitive_values:
            self.sensitive_values.append(value)

    def event(self, stage, verdict, *, request_id=None, direction="internal",
              rules=None, upstream_sent=False, reason="ok", snapshot=None, release_id=None):
        # No arbitrary exception text, prompt, path or model-generated prose.
        record = dict(time=datetime.now(timezone.utc).isoformat(), policy=POLICY,
                      stage=stage, verdict=verdict, request_id=request_id,
                      direction=direction, rules=rules or {},
                      upstream_sent=upstream_sent, reason=reason, snapshot=snapshot, release_id=release_id)
        try:
            with self.path.open("ab") as stream:
                stream.write(encode(record) + b"\n")
                stream.flush()
                os.fsync(stream.fileno())
        except OSError:
            raise Blocked("audit_unavailable") from None

    def check(self, value, stage, **metadata):
        rules = scan(value)
        decoded = json.dumps(value, ensure_ascii=False)
        for secret in self.sensitive_values:
            if secret in decoded:
                rules["configured_credential"] = rules.get("configured_credential", 0) + 1
        self.event(stage, "BLOCK" if rules else "ALLOW_CLEAN", rules=rules, **metadata)
        if rules:
            raise Blocked("sensitive_data")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Blocked("redirect_denied")


def endpoint(url, *, local=False):
    try:
        parsed = urlsplit(url)
        valid = (parsed.scheme == "http" and parsed.hostname == "127.0.0.1") if local else parsed.scheme == "https"
        if not valid or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError()
        parsed.port
    except ValueError:
        raise Blocked("invalid_endpoint") from None
    return url


def post(url, body, token, *, timeout=60):
    """No ambient proxy, redirect, retry or direct-provider fallback."""
    if not token or any(c in token for c in "\r\n"):
        raise Blocked("missing_credential")
    raw = encode(body)
    if len(raw) > LIMIT:
        raise Blocked("payload_too_large")
    request = Request(url, raw, {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout) as response:
            raw = response.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise Blocked("payload_too_large")
        return parse(raw)
    except Blocked:
        raise
    except HTTPError as error:
        error.close()
        raise Blocked("transport_error") from None
    except Exception:
        raise Blocked("transport_error") from None
