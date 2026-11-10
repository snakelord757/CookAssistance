"""Trusted security-review backend; no dependency on the generating harness."""
from pathlib import Path
import re
import subprocess

from loop_security import Blocked, parse
from model_adapters import ChatCompletionsAdapter


def outside_git(path):
    """Never accept credentials inside ANY checkout, including ignored paths."""
    for parent in (path, *path.parents):
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise Blocked("credential_link_denied")
    result = subprocess.run(["git", "-C", str(path.parent), "rev-parse", "--git-dir"],
                            capture_output=True)
    if result.returncode == 0:
        raise Blocked("credential_inside_git_checkout")
    if result.returncode != 128:
        raise Blocked("credential_location_unverified")


class DeepSeekReview:
    """Reads the adjacent key only for an authorized security request.

    Credential goes exclusively in HTTPS Authorization, never in model context.
    No configurable endpoint, redirects or generator fallback.
    """
    def __init__(self, config_path):
        self.config_path = Path(config_path).absolute()
        self.key_path = self.config_path.parent / "deepseek.key"
        outside_git(self.key_path)
        try:
            config = parse(self.config_path.read_bytes())
        except OSError:
            raise Blocked("review_config_unavailable") from None
        if (not isinstance(config, dict) or set(config) != {"model"}
                or not isinstance(config["model"], str)
                or not re.fullmatch(r"deepseek-[a-z0-9.-]{1,64}", config["model"])):
            raise Blocked("invalid_deepseek_config")
        self.model = config["model"]

    def complete(self, public, audit, request_id):
        if public.get("stage") != "security":
            raise Blocked("deepseek_security_only")
        outside_git(self.key_path)
        try:
            if not self.key_path.is_file() or self.key_path.stat().st_nlink != 1:
                raise Blocked("invalid_credential_file")
            with self.key_path.open("rb") as stream:
                raw = stream.read(513)
            token = raw.decode("ascii").strip()
        except (OSError, UnicodeError):
            raise Blocked("deepseek_key_unavailable") from None
        if len(raw) > 512 or not re.fullmatch(r"[A-Za-z0-9_-]{16,256}", token):
            raise Blocked("invalid_deepseek_key")
        audit.protect(token)
        adapter = ChatCompletionsAdapter("https://api.deepseek.com/chat/completions", token, self.model)
        return adapter.complete(public, audit, request_id)
