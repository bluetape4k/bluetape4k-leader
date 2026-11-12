#!/usr/bin/env python3
"""Gitleaks 계약과 설치/history 실패 모드를 재현하는 테스트."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTALLER = ROOT / ".github/scripts/install-gitleaks.sh"
VALIDATOR = ROOT / "scripts/ci/validate_gitleaks_contract.py"


def run_command(*command: str, cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=cwd,
        env=env,
        check=False,
        text=True,
        capture_output=True,
    )


class GitleaksContractTest(unittest.TestCase):
    def test_repository_contract_is_static_and_pinned(self) -> None:
        result = run_command(sys.executable, str(VALIDATOR), "--static", cwd=ROOT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_installer_retries_then_verifies_and_installs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self._write_fixture_archive(root)
            counter = root / "curl-count"
            fake_bin = root / "bin"
            fake_bin.mkdir()
            self._write_fake_curl(fake_bin / "curl", archive, counter, failures=2)
            install_dir = root / "install"
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{fake_bin}:{env['PATH']}",
                    "GITLEAKS_VERSION": "9.9.9",
                    "GITLEAKS_SHA256": digest,
                    "GITLEAKS_DOWNLOAD_URL": "https://example.invalid/gitleaks.tar.gz",
                    "GITLEAKS_INSTALL_DIR": str(install_dir),
                    "GITLEAKS_RETRY_ATTEMPTS": "3",
                    "GITLEAKS_RETRY_DELAY_SECONDS": "0",
                }
            )

            result = run_command("bash", str(INSTALLER), cwd=ROOT, env=env)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(counter.read_text(encoding="utf-8"), "3")
            self.assertTrue((install_dir / "gitleaks").is_file())

    def test_installer_fails_after_bounded_retry_budget(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self._write_fixture_archive(root)
            counter = root / "curl-count"
            fake_bin = root / "bin"
            fake_bin.mkdir()
            self._write_fake_curl(fake_bin / "curl", archive, counter, failures=99)
            install_dir = root / "install"
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{fake_bin}:{env['PATH']}",
                    "GITLEAKS_VERSION": "9.9.9",
                    "GITLEAKS_SHA256": digest,
                    "GITLEAKS_DOWNLOAD_URL": "https://example.invalid/gitleaks.tar.gz",
                    "GITLEAKS_INSTALL_DIR": str(install_dir),
                    "GITLEAKS_RETRY_ATTEMPTS": "3",
                    "GITLEAKS_RETRY_DELAY_SECONDS": "0",
                }
            )

            result = run_command("bash", str(INSTALLER), cwd=ROOT, env=env)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(counter.read_text(encoding="utf-8"), "3")
            self.assertIn("after 3 attempts", result.stderr)
            self.assertFalse((install_dir / "gitleaks").exists())

    @unittest.skipUnless(shutil.which("gitleaks"), "gitleaks는 CI 설치 단계 이후 실행")
    def test_history_scan_detects_secret_removed_by_later_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            run_command("git", "init", cwd=repo)
            run_command("git", "config", "user.email", "ci@example.invalid", cwd=repo)
            run_command("git", "config", "user.name", "CI", cwd=repo)
            (repo / "tracked.txt").write_text("safe\n", encoding="utf-8")
            run_command("git", "add", ".", cwd=repo)
            run_command("git", "commit", "-m", "initial", cwd=repo)

            historical_secret = "-".join(("tok", "secret", "123456"))
            (repo / "tracked.txt").write_text(f"token={historical_secret}\n", encoding="utf-8")
            run_command("git", "add", ".", cwd=repo)
            run_command("git", "commit", "-m", "add token", cwd=repo)
            (repo / "tracked.txt").write_text("safe again\n", encoding="utf-8")
            run_command("git", "add", ".", cwd=repo)
            run_command("git", "commit", "-m", "remove token", cwd=repo)

            config = repo / ".gitleaks.toml"
            config.write_text("[extend]\nuseDefault = true\n", encoding="utf-8")
            report = repo / "report.json"
            result = run_command(
                "gitleaks",
                "git",
                "--redact",
                "--config",
                str(config),
                "--log-opts=--all",
                "--report-format",
                "json",
                "--report-path",
                str(report),
                ".",
                cwd=repo,
            )

            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
            findings = json.loads(report.read_text(encoding="utf-8"))
            self.assertTrue(findings)

    @staticmethod
    def _write_fixture_archive(root: Path) -> Path:
        payload = root / "payload"
        payload.mkdir()
        binary = payload / "gitleaks"
        binary.write_text("#!/usr/bin/env bash\necho 'gitleaks 9.9.9'\n", encoding="utf-8")
        binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
        archive = root / "gitleaks_9.9.9_linux_x64.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(binary, arcname="gitleaks")
        return archive

    @staticmethod
    def _write_fake_curl(path: Path, archive: Path, counter: Path, failures: int) -> None:
        script = f"""#!/usr/bin/env python3
import pathlib
import shutil
import sys

counter = pathlib.Path({str(counter)!r})
count = int(counter.read_text() or '0') if counter.exists() else 0
count += 1
counter.write_text(str(count))
if count <= {failures}:
    raise SystemExit(22)
arguments = sys.argv[1:]
output = pathlib.Path(arguments[arguments.index('--output') + 1])
shutil.copyfile({str(archive)!r}, output)
"""
        path.write_text(script, encoding="utf-8")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)


if __name__ == "__main__":
    unittest.main()
