"""In-process tamper detection, in addition to the OS-protected launcher."""
from pathlib import Path
import hashlib
from trusted_runtime import FILES, asset_hashes, assert_protected, verify_install
from loop_security import Blocked


class RuntimeIntegrity:
    def __init__(self, root=None, *, installed=False, config_paths=()):
        self.root = Path(root or Path(__file__).absolute().parents[1])
        self.installed = installed
        self.config_paths = tuple(Path(p).absolute() for p in config_paths)
        try:
            if installed:
                verify_install(self.root)
                for path in self.config_paths:
                    assert_protected(path)
            self.expected = asset_hashes(self.root)
            self.config_hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.config_paths}
        except Exception:
            raise Blocked("runtime_integrity_unavailable") from None

    def verify(self):
        try:
            if self.installed:
                verify_install(self.root)
                for path in self.config_paths:
                    assert_protected(path)
            if asset_hashes(self.root) != self.expected:
                raise ValueError()
            if any(hashlib.sha256(Path(p).read_bytes()).hexdigest() != value for p, value in self.config_hashes.items()):
                raise ValueError()
        except Exception:
            raise Blocked("runtime_integrity_changed") from None

    def protected(self, path):
        resolved = Path(path).resolve()
        return any(resolved == (self.root / name).resolve() for name in FILES)
