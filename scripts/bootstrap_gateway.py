"""First-run operator setup, then verify-only startup of the protected loop.

Run this reviewed bootstrap locally, before giving private context to an agent.
Existing installations are NEVER repaired, resealed or chmod'ed by normal startup.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

# Bootstrap itself is a reviewed operator artifact. In isolated Python this is
# the only explicitly added import directory, never cwd or PYTHONPATH.
sys.path.insert(0, str(Path(__file__).absolute().parent))
from package_runtime import package
from trusted_runtime import IntegrityError, no_links, verify_install, assert_restricted_identity, assert_protected, asset_hashes


def artifact_digest(root):
    return hashlib.sha256(json.dumps(asset_hashes(root), sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def install_base():
    if os.name == "nt":
        buffer = ctypes.create_unicode_buffer(32768)
        if ctypes.windll.shell32.SHGetFolderPathW(None, 0x26, None, 0, buffer) != 0:
            raise IntegrityError("program_files_unavailable")
        return Path(buffer.value) / "CookSecurity"
    return Path("/opt/cook-security")


def managed_path(path):
    path = Path(path).absolute()
    no_links(path)
    if path.parent != install_base() or not re.fullmatch(r"runtime-[A-Za-z0-9_-]{1,48}", path.name):
        raise IntegrityError("unmanaged_install_destination")
    return path


def operator_identity():
    return bool(ctypes.windll.shell32.IsUserAnAdmin()) if os.name == "nt" else os.geteuid() == 0


def windows_system_directory():
    buffer = ctypes.create_unicode_buffer(32768)
    size = ctypes.windll.kernel32.GetSystemDirectoryW(buffer, len(buffer))
    if not 0 < size < len(buffer):
        raise IntegrityError("system_directory_unavailable")
    return Path(buffer.value)


def protect_tree(path):
    if os.name == "nt":
        # Absolute OS utility, not a checkout/PATH executable. New installer-owned
        # objects only; no operation here is allowed on an existing installation.
        tool = windows_system_directory() / "icacls.exe"
        commands = [
            [str(tool), str(path), "/reset", "/T", "/Q"],
            [str(tool), str(path), "/inheritance:r", "/grant:r",
             "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F",
             "*S-1-5-32-545:(OI)(CI)RX", "/T", "/Q"],
            [str(tool), str(path), "/setowner", "*S-1-5-32-544", "/T", "/Q"],
        ]
        for command in commands:
            if subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=60).returncode:
                raise IntegrityError("install_acl_failed")
    else:
        for item in [path, *path.rglob("*")]:
            no_links(item)
            os.chown(item, 0, 0)
            item.chmod(0o755 if item.is_dir() else 0o644)


def install(source, destination, expected_digest):
    """Privileged one-time operator action. Never called for an existing target."""
    destination = managed_path(destination)
    if not operator_identity():
        raise IntegrityError("operator_install_required")
    if not re.fullmatch(r"[a-f0-9]{64}", expected_digest or "") or artifact_digest(source) != expected_digest:
        raise IntegrityError("unapproved_install_artifact")
    if destination.exists():
        raise IntegrityError("existing_install_not_modified")
    base = destination.parent
    if base.exists():
        raise IntegrityError("existing_container_requires_operator")
    base.mkdir()  # Program Files or /opt must already exist.
    protect_tree(base)
    # No privileged writes through a preexisting, potentially agent-owned base.
    no_links(base)
    staging = base / ("." + destination.name + ".installing")
    if staging.exists():
        raise IntegrityError("incomplete_install_requires_operator")
    package(Path(source), staging)
    if artifact_digest(staging) != expected_digest:
        raise IntegrityError("install_artifact_changed")
    protect_tree(staging)
    if destination.exists():
        raise IntegrityError("install_race")
    staging.rename(destination)
    # A success here means installed, not ready: privileged identity cannot
    # attest that the restricted agent lacks modification rights.


def request_install(destination, expected_digest):
    if destination.parent.exists():
        raise IntegrityError("incomplete_install_requires_operator")
    if os.name != "nt":
        raise IntegrityError("operator_install_required_use_install_mode")
    interpreter = Path(sys.executable).resolve()
    assert_protected(interpreter)
    # Windows UAC is an explicit operator approval. The agent gets no persistent
    # privilege and the parent continues under its original restricted identity.
    environment = dict(os.environ)
    environment["COOK_BOOTSTRAP_PYTHON"] = str(interpreter)
    environment["COOK_BOOTSTRAP_ARGS"] = subprocess.list2cmdline([
        "-I", "-S", "-B", str(Path(__file__).absolute()), "--install", "--runtime", str(destination),
        "--expected-digest", expected_digest])
    script = "$ErrorActionPreference='Stop'; try { $p = Start-Process -FilePath $env:COOK_BOOTSTRAP_PYTHON -ArgumentList $env:COOK_BOOTSTRAP_ARGS -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode } catch { exit 2 }"
    shell = windows_system_directory() / "WindowsPowerShell/v1.0/powershell.exe"
    result = subprocess.run([str(shell), "-NoProfile", "-NonInteractive", "-Command", script],
                            env=environment, stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
    if result.returncode:
        raise IntegrityError("operator_install_declined_or_failed")


def ensure_ready(destination, installer, verifier=verify_install, expected_digest=None):
    """No persistent ready flag. Existing invalid installations never trigger setup."""
    no_links(destination)
    created = not destination.exists()
    if created:
        installer(destination)
    verifier(destination)
    if created and expected_digest is not None and artifact_digest(destination) != expected_digest:
        raise IntegrityError("installed_artifact_mismatch")
    return "INSTALLED_AND_READY" if created else "READY"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--install", action="store_true", help="Operator-only initial setup; never starts the loop")
    parser.add_argument("--expected-digest", help="Reviewed artifact pin, used only by the operator installer")
    parser.add_argument("--artifact-digest", action="store_true", help="Print local artifact digest without installing")
    parser.add_argument("--check", action="store_true", help="Verify only; never install or execute the loop")
    parser.add_argument("loop_args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        if not sys.flags.isolated or not sys.flags.no_site or not sys.dont_write_bytecode:
            raise IntegrityError("isolated_python_required")
        destination = managed_path(args.runtime or install_base() / "runtime-v1")
        forwarded = args.loop_args[1:] if args.loop_args[:1] == ["--"] else args.loop_args
        if args.artifact_digest:
            if args.install or args.check or args.expected_digest or forwarded:
                raise IntegrityError("invalid_digest_arguments")
            print(artifact_digest(Path(__file__).absolute().parents[1]))
            return 0
        if args.install:
            if args.check or forwarded:
                raise IntegrityError("invalid_install_arguments")
            install(Path(__file__).absolute().parents[1], destination, args.expected_digest)
            print("INSTALLED: readiness must be checked as the restricted agent")
            return 0
        assert_restricted_identity()
        if args.expected_digest:
            raise IntegrityError("pin_only_allowed_for_installer")
        if args.check:
            if forwarded:
                raise IntegrityError("check_cannot_execute_loop")
            verify_install(destination)
            print("READY")
            return 0
        if not forwarded or forwarded[0] not in {"run", "review", "verify", "install-hook", "serve-gateway"}:
            raise IntegrityError("loop_action_required")
        pin = artifact_digest(Path(__file__).absolute().parents[1]) if not destination.exists() else None
        status = ensure_ready(destination, lambda path: request_install(path, pin), expected_digest=pin)
        print(status, flush=True)
        return subprocess.run([sys.executable, "-I", "-S", "-B",
                               str(destination / "scripts/trusted_runtime.py"), *forwarded], check=False).returncode
    except Exception as exc:
        reason = str(exc) if isinstance(exc, IntegrityError) else "bootstrap_failed"
        print("BLOCKED: " + reason)
        return 2


if __name__ == "__main__":
    # -I omits the script directory; operator mode needs only the reviewed local
    # packaging/verification modules, before any application/model context.
    raise SystemExit(main())
