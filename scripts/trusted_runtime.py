"""Isolated entry point. Deploy under a different owner; run with python -I -S -B.

Only stdlib imports are allowed until permissions and the complete runtime seal
have been verified. A seal is not an authority if the agent can rewrite it.
"""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import sys

FILES = (
    "scripts/trusted_runtime.py", "scripts/runtime_integrity.py",
    "scripts/execution_loop.py", "scripts/execution_flow.py", "scripts/context_gate.py",
    "scripts/context_release.py", "scripts/loop_security.py", "scripts/model_adapters.py",
    "scripts/llm_gateway.py", "scripts/harness_preflight.py", "scripts/builtin_connector.py",
    "docs/security-review-prompt.md",
)


class IntegrityError(Exception):
    """Fixed codes only, never raw OS errors or file contents."""


def no_links(path):
    for item in (path, *path.parents):
        if item.is_symlink() or (hasattr(item, "is_junction") and item.is_junction()):
            raise IntegrityError("trusted_path_link")
    if path.is_file() and path.stat().st_nlink != 1:
        raise IntegrityError("trusted_path_hardlink")


def windows_can_modify(path, *, ancestor=False):
    # Ask the kernel about EACH right separately. A combined access mask would
    # miss identities that have only one dangerous right (e.g. WRITE_DAC).
    from ctypes import wintypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    create = kernel.CreateFileW
    create.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
                       wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    create.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    rights = (0x10000, 0x40000, 0x80000) if ancestor else (0x2, 0x4, 0x100, 0x10000, 0x40000, 0x80000)
    if path.is_dir():
        rights += (0x40,)  # FILE_DELETE_CHILD permits replacing a protected child.
    for right in rights:
        handle = create(str(path), right, 7, None, 3, 0x02000000, None)
        if handle != ctypes.c_void_p(-1).value:
            kernel.CloseHandle(handle)
            return True
        if ctypes.get_last_error() != 5:  # Anything but ACCESS_DENIED is inconclusive.
            raise IntegrityError("trusted_permissions_unverifiable")
    return False


def assert_restricted_identity():
    if os.name != "nt":
        if os.geteuid() == 0:
            raise IntegrityError("unrestricted_execution_identity")
        status = Path("/proc/self/status")
        if status.exists():
            for line in status.read_text().splitlines():
                if line.startswith(("CapPrm:", "CapEff:")) and int(line.split()[1], 16):
                    raise IntegrityError("unrestricted_execution_identity")
        return
    from ctypes import wintypes
    class Luid(ctypes.Structure):
        _fields_ = [("low", wintypes.DWORD), ("high", wintypes.LONG)]
    class Entry(ctypes.Structure):
        _fields_ = [("luid", Luid), ("attributes", wintypes.DWORD)]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    advapi = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    advapi.OpenProcessToken.argtypes = [wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE)]
    advapi.GetTokenInformation.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    advapi.LookupPrivilegeValueW.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, ctypes.POINTER(Luid)]
    token = wintypes.HANDLE()
    if not advapi.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)):
        raise IntegrityError("trusted_permissions_unverifiable")
    try:
        size = wintypes.DWORD()
        advapi.GetTokenInformation(token, 3, None, 0, ctypes.byref(size))
        buffer = ctypes.create_string_buffer(size.value)
        if not advapi.GetTokenInformation(token, 3, buffer, size, ctypes.byref(size)):
            raise IntegrityError("trusted_permissions_unverifiable")
        count = wintypes.DWORD.from_buffer(buffer).value
        held = (Entry * count).from_buffer(buffer, ctypes.sizeof(wintypes.DWORD))
        for name in ("SeTakeOwnershipPrivilege", "SeRestorePrivilege", "SeDebugPrivilege",
                     "SeTcbPrivilege", "SeImpersonatePrivilege", "SeAssignPrimaryTokenPrivilege"):
            luid = Luid()
            if not advapi.LookupPrivilegeValueW(None, name, ctypes.byref(luid)):
                raise IntegrityError("trusted_permissions_unverifiable")
            if any(e.luid.low == luid.low and e.luid.high == luid.high and not e.attributes & 4 for e in held):
                # Disabled privileges can be re-enabled; checking enabled only is unsafe.
                raise IntegrityError("unrestricted_execution_identity")
    finally:
        kernel.CloseHandle(token)


def assert_protected(path):
    path = Path(path).absolute()
    no_links(path)
    # Protect against replacement of an ancestor, not just writes to a file.
    for item in (path, *path.parents):
        info = item.stat()
        if os.name == "nt":
            writable = windows_can_modify(item, ancestor=item != path)
        else:
            # Ownership is enough to chmod a read-only file. Root is not a
            # restricted execution identity. Also account for ACL via access().
            writable = (os.geteuid() == 0 or info.st_uid == os.geteuid()
                        or os.access(item, os.W_OK))
        if writable:
            raise IntegrityError("trusted_path_mutable_by_agent")


def asset_hashes(root):
    root = Path(root).absolute()
    result = {}
    for name in FILES:
        path = root / name
        no_links(path)
        if not path.is_file():
            raise IntegrityError("trusted_asset_missing")
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def check_layout(root):
    # No extra importable files/packages, pycache or alternate .pyc/.pyd modules.
    allowed = {Path(name).name for name in FILES if name.startswith("scripts/")}
    if {p.name for p in (root / "scripts").iterdir()} != allowed:
        raise IntegrityError("unexpected_runtime_entry")
    if {p.name for p in root.iterdir()} != {"scripts", "docs", "runtime-seal.json"}:
        raise IntegrityError("unexpected_runtime_entry")
    if {p.name for p in (root / "docs").iterdir()} != {"security-review-prompt.md"}:
        raise IntegrityError("unexpected_runtime_entry")


def verify_install(root):
    root = Path(root).absolute()
    check_layout(root)
    assert_restricted_identity()
    # Verify OS boundary before trusting hashes read from this installation.
    for name in (*FILES, "runtime-seal.json", "scripts", "docs", "."):
        assert_protected(root / name)
    raw = (root / "runtime-seal.json").read_bytes()
    if len(raw) > 16384:
        raise IntegrityError("invalid_runtime_seal")
    seal = json.loads(raw)
    if (not isinstance(seal, dict) or set(seal) != {"version", "files"}
            or seal["version"] != 1 or seal["files"] != asset_hashes(root)):
        raise IntegrityError("runtime_seal_mismatch")
    return seal["files"]


def main():
    try:
        if not sys.flags.isolated or not sys.flags.no_site or not sys.dont_write_bytecode:
            raise IntegrityError("isolated_python_required")
        root = Path(__file__).absolute().parents[1]
        verify_install(root)
        # Safe only after OS protection + seal check. No checkout/PYTHONPATH imports.
        sys.path.insert(0, str(root / "scripts"))
        if sys.argv[1:2] == ["--probe"]:
            import builtin_connector
            return builtin_connector.main()
        if sys.argv[1:2] == ["serve-gateway"]:
            del sys.argv[1]
            import llm_gateway
            return llm_gateway.main()
        import execution_loop
        return execution_loop.main()
    except Exception:
        print("BLOCKED: trusted_runtime_unavailable_or_modified")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
