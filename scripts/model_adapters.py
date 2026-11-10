"""Model integration for the execution loop, deliberately outside the Gateway.

An adapter receives only the already authorized public payload. Custom local
models, SDKs or other APIs implement complete(public, audit, request_id) -> JSON.
No adapter is selected or instantiated by the local disclosure service.
"""
from __future__ import annotations

import os
from loop_security import Blocked, endpoint, parse, post, encode


class JsonHttpAdapter:
    """Generic endpoint: accepts the public JSON object and returns result JSON."""
    def __init__(self, url, token, *, local=False):
        self.url = endpoint(url, local=local)
        self.token = token

    def payload(self, public):
        return public

    def result(self, response):
        return response

    def complete(self, public, audit, request_id):
        stage = public["stage"]
        audit.protect(self.token)
        payload = self.payload(public)
        audit.check(payload, stage, request_id=request_id, direction="adapter_request")
        audit.event(stage, "SEND", request_id=request_id, upstream_sent=True)
        try:
            response = post(self.url, payload, self.token)
            audit.check(response, stage, request_id=request_id, direction="adapter_response", upstream_sent=True)
            return self.result(response)
        except Blocked:
            raise
        except Exception:
            raise Blocked("invalid_adapter_response") from None


class ChatCompletionsAdapter(JsonHttpAdapter):
    """Optional protocol adapter, not a requirement of the disclosure Gateway."""
    def __init__(self, url, token, model, *, local=False):
        super().__init__(url, token, local=local)
        if not isinstance(model, str) or not model:
            raise Blocked("missing_model")
        self.model = model

    def payload(self, public):
        return {"model": self.model,
                "messages": [{"role": "system", "content": public["system"]},
                             {"role": "user", "content": encode(public["context"]).decode()}],
                "stream": False, "response_format": {"type": "json_object"}}

    def result(self, response):
        choices = response["choices"]
        if len(choices) != 1 or choices[0]["finish_reason"] != "stop":
            raise Blocked("incomplete_response")
        return parse(choices[0]["message"]["content"])


def load_adapter(path):
    config = parse(path.read_bytes())
    if not isinstance(config, dict) or set(config) - {"type", "endpoint", "model", "local"}:
        raise Blocked("invalid_adapter_config")
    local = config.get("local", False)
    if type(local) is not bool:
        raise Blocked("invalid_adapter_config")
    token = os.environ.get("LLM_UPSTREAM_TOKEN")
    if config.get("type") == "json-http":
        return JsonHttpAdapter(config["endpoint"], token, local=local)
    if config.get("type") == "chat-completions":
        return ChatCompletionsAdapter(config["endpoint"], token, config.get("model"), local=local)
    raise Blocked("unknown_adapter")
