import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import bootstrap_gateway as boot
import trusted_runtime as trusted
from package_runtime import package


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.source = Path(__file__).resolve().parents[1]
        folder = self.source / "reports/bootstrap-tests"
        folder.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=folder)
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.target = self.folder / "managed" / "runtime-v1"

    def verifier(self, path):
        # Hash/layout positive path only: no claim of real protected deployment.
        with patch.object(trusted, "assert_protected"), patch.object(trusted, "assert_restricted_identity"):
            return trusted.verify_install(path)

    def test_first_setup_once_then_verify_without_writes(self):
        installer = Mock(side_effect=lambda path: package(self.source, path))
        self.assertEqual(boot.ensure_ready(self.target, installer, self.verifier), "INSTALLED_AND_READY")
        files = [p for p in self.target.rglob("*") if p.is_file()]
        before = {p: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns) for p in files}
        self.assertEqual(boot.ensure_ready(self.target, installer, self.verifier), "READY")
        self.assertEqual(installer.call_count, 1)
        self.assertEqual(before, {p: (hashlib.sha256(p.read_bytes()).hexdigest(), p.stat().st_mtime_ns) for p in files})

    def test_existing_tampered_install_never_repaired(self):
        package(self.source, self.target)
        (self.target / "scripts/context_gate.py").write_text("# changed")
        installer = Mock()
        with self.assertRaisesRegex(trusted.IntegrityError, "runtime_seal_mismatch"):
            boot.ensure_ready(self.target, installer, self.verifier)
        installer.assert_not_called()

    def test_existing_partial_install_never_repaired(self):
        self.target.mkdir(parents=True)
        installer = Mock()
        with self.assertRaises(Exception):
            boot.ensure_ready(self.target, installer, self.verifier)
        installer.assert_not_called()

    def test_failed_elevation_never_checks_or_runs_runtime(self):
        installer = Mock(side_effect=trusted.IntegrityError("operator_install_declined_or_failed"))
        verifier = Mock()
        with self.assertRaisesRegex(trusted.IntegrityError, "declined"):
            boot.ensure_ready(self.target, installer, verifier)
        verifier.assert_not_called()

    def test_created_but_writable_install_never_ready(self):
        installer = Mock(side_effect=lambda path: package(self.source, path))
        with self.assertRaises(trusted.IntegrityError):
            boot.ensure_ready(self.target, installer)

    def test_installer_rejects_nonoperator(self):
        with patch.object(boot, "install_base", return_value=self.target.parent), patch.object(boot, "operator_identity", return_value=False):
            with self.assertRaisesRegex(trusted.IntegrityError, "operator_install_required"):
                boot.install(self.source, self.target, boot.artifact_digest(self.source))
        self.assertFalse(self.target.exists())

    def test_install_protects_new_container_and_staging_before_publish(self):
        observed = []
        def protect(path):
            self.assertFalse(self.target.exists())
            observed.append(path)
        with patch.object(boot, "install_base", return_value=self.target.parent), patch.object(boot, "operator_identity", return_value=True), patch.object(boot, "protect_tree", side_effect=protect):
            boot.install(self.source, self.target, boot.artifact_digest(self.source))
        self.assertEqual(observed, [self.target.parent, self.target.parent / ".runtime-v1.installing"])
        self.verifier(self.target)

    def test_existing_container_cannot_be_overwritten_by_elevated_helper(self):
        self.target.parent.mkdir()
        marker = self.target.parent / "keep"
        marker.write_text("original")
        with patch.object(boot, "install_base", return_value=self.target.parent), patch.object(boot, "operator_identity", return_value=True), patch.object(boot, "protect_tree") as protect:
            with self.assertRaisesRegex(trusted.IntegrityError, "existing_container"):
                boot.install(self.source, self.target, boot.artifact_digest(self.source))
        protect.assert_not_called()
        self.assertEqual(marker.read_text(), "original")

    def test_unmanaged_destination_refused_before_privileged_work(self):
        with patch.object(boot, "install_base", return_value=self.folder / "allowed"):
            with self.assertRaisesRegex(trusted.IntegrityError, "unmanaged_install_destination"):
                boot.install(self.source, self.target, boot.artifact_digest(self.source))

    def test_wrong_artifact_pin_blocks_before_filesystem_changes(self):
        with patch.object(boot, "install_base", return_value=self.target.parent), patch.object(boot, "operator_identity", return_value=True), patch.object(boot, "protect_tree") as protect:
            with self.assertRaisesRegex(trusted.IntegrityError, "unapproved_install_artifact"):
                boot.install(self.source, self.target, "0" * 64)
        protect.assert_not_called()
        self.assertFalse(self.target.parent.exists())

    def test_parent_rechecks_installed_artifact_pin(self):
        def different_artifact(path):
            package(self.source, path)
            (path / "scripts/context_gate.py").write_text("# different release")
        with self.assertRaisesRegex(trusted.IntegrityError, "installed_artifact_mismatch"):
            boot.ensure_ready(self.target, different_artifact, Mock(), expected_digest=boot.artifact_digest(self.source))

    def test_direct_nonisolated_bootstrap_blocked(self):
        result = subprocess.run([sys.executable, str(self.source / "scripts/bootstrap_gateway.py"), "--check"],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"isolated_python_required", result.stdout)

    def test_isolated_check_has_no_install_side_effects(self):
        result = subprocess.run([sys.executable, "-I", "-S", "-B",
                                 str(self.source / "scripts/bootstrap_gateway.py"),
                                 "--check", "--runtime", str(self.target)], capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(self.target.exists())
        self.assertIn(b"unmanaged_install_destination", result.stdout)

    @unittest.skipUnless(os.name == "nt" and boot.operator_identity(), "requires elevated Windows operator")
    def test_windows_acl_on_disposable_tree(self):
        self.target.mkdir(parents=True)
        child = self.target / "sample.py"
        child.write_text("# synthetic")
        boot.protect_tree(self.target)
        script = "$a=Get-Acl -LiteralPath $env:COOK_TEST_ACL; @{owner=$a.GetOwner([System.Security.Principal.SecurityIdentifier]).Value; protected=$a.AreAccessRulesProtected; rules=@($a.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]) | ForEach-Object {@{sid=$_.IdentityReference.Value; rights=[int]$_.FileSystemRights; type=[string]$_.AccessControlType}})} | ConvertTo-Json -Depth 4 -Compress"
        for path in (self.target, child):
            result = subprocess.run([str(boot.windows_system_directory() / "WindowsPowerShell/v1.0/powershell.exe"),
                                     "-NoProfile", "-NonInteractive", "-Command", script],
                                    env=dict(os.environ, COOK_TEST_ACL=str(path)), capture_output=True, check=True)
            acl = json.loads(result.stdout)
            self.assertEqual(acl["owner"], "S-1-5-32-544")
            self.assertTrue(acl["protected"])
            self.assertEqual({r["sid"] for r in acl["rules"]}, {"S-1-5-18", "S-1-5-32-544", "S-1-5-32-545"})
            users = next(r for r in acl["rules"] if r["sid"] == "S-1-5-32-545")
            self.assertEqual(users["type"], "Allow")
            self.assertEqual(users["rights"] & (0x2 | 0x4 | 0x40 | 0x10000 | 0x40000 | 0x80000), 0)


if __name__ == "__main__":
    unittest.main()
