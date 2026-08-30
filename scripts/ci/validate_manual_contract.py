#!/usr/bin/env python3
"""Run the reproducible manual, release provenance, and diagram CI contract."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(os.environ.get("MANUAL_CODE_ROOT", Path(__file__).resolve().parents[2])).resolve()
MANUAL_SITE_ROOT = Path(os.environ.get("MANUAL_SITE_ROOT", ROOT)).resolve()
MANUAL_ROOT = Path(
    os.environ.get("MANUAL_ROOT", MANUAL_SITE_ROOT / "docs/manual/bluetape4k-leader")
).resolve()
TOOL_ROOT = MANUAL_SITE_ROOT / "scripts/manual/repositories/bluetape4k-leader"
INVENTORY = ROOT / "build/manual/module-inventory.json"
RELEASE_INVENTORY = ROOT / "build/manual/release-module-inventory.json"
MANIFEST = MANUAL_ROOT / "manifest.yaml"
RELEASE_COUNT = int(os.environ.get("MANUAL_RELEASE_EXPECTED_COUNT", "35"))


def manifest_value(name: str) -> str:
    pattern = re.compile(rf"^{re.escape(name)}:\s*([^#\s]+)\s*$", re.MULTILINE)
    match = pattern.search(MANIFEST.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"manual manifest is missing {name}")
    return match.group(1)


def run(command: list[str], *, env: dict[str, str] | None = None) -> None:
    rendered = " ".join(command)
    print(f"[manual-contract] RUN {rendered}")
    process = subprocess.run(
        command,
        cwd=ROOT,
        env=env,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if process.stdout:
        print(process.stdout, end="")
    if process.returncode != 0:
        raise RuntimeError(f"command failed ({process.returncode}): {rendered}")


def main() -> int:
    try:
        release_ref = manifest_value("releaseRef")
        release_commit = manifest_value("releaseCommit")
        print(
            "[manual-contract] release provenance target: "
            f"{release_ref} ({release_commit})"
        )
        run(["python3", str(ROOT / "scripts/ci/validate_ci_fanout.py"), "--static"])
        run(["./gradlew", "exportManualModuleInventory", "--no-daemon", "--no-configuration-cache"])
        run(
            [
                "ruby",
                str(TOOL_ROOT / "release_inventory.rb"),
                release_ref,
                release_commit,
                str(INVENTORY),
                str(RELEASE_INVENTORY),
                str(RELEASE_COUNT),
            ]
        )
        validation_env = os.environ.copy()
        validation_env.update(
            {
                "MANUAL_RELEASE_REF": release_ref,
                "MANUAL_RELEASE_COMMIT": release_commit,
            }
        )
        run(
            [
                "ruby",
                str(TOOL_ROOT / "validate_manuals.rb"),
                str(RELEASE_INVENTORY),
                str(MANIFEST),
            ],
            env=validation_env,
        )
        run(
            [
                "ruby",
                str(TOOL_ROOT / "validate_release_manuals.rb"),
                "--code-root",
                str(ROOT),
                "--manual-root",
                str(MANUAL_ROOT),
                "--manifest",
                str(MANIFEST),
                "--inventory",
                str(RELEASE_INVENTORY),
                "--tag",
                release_ref,
                "--sha",
                release_commit,
            ]
        )
        run(["ruby", str(TOOL_ROOT / "export_manifest.rb"), "--check", str(MANIFEST), str(MANUAL_ROOT / "generated/manifest.json")])
        run(
            [
                "ruby",
                "-I",
                str(TOOL_ROOT),
                "-e",
                'Dir[File.join(ARGV.fetch(0), "*_test.rb")].sort.each { |file| require File.expand_path(file) }',
                str(TOOL_ROOT),
            ]
        )
        run(
            [
                "ruby",
                str(TOOL_ROOT / "validate_diagrams.rb"),
                "--root",
                str(MANUAL_SITE_ROOT),
                "--manual-root",
                str(MANUAL_ROOT),
            ]
        )
        run(
            [
                "ruby",
                str(TOOL_ROOT / "readme_jvm25_contract.rb"),
                "--root",
                str(ROOT),
                "--manual-root",
                str(MANUAL_ROOT),
            ]
        )
        run(
            [
                "ruby",
                str(TOOL_ROOT / "sync_release_diagrams.rb"),
                "--check",
                "--root",
                str(ROOT),
                "--manual-root",
                str(MANUAL_ROOT),
            ]
        )

        diagram_command = ["python3", str(ROOT / "scripts/ci/validate_diagram_contract.py")]
        base_ref = os.environ.get("MANUAL_CONTRACT_BASE_REF", "").strip()
        head_ref = os.environ.get("MANUAL_CONTRACT_HEAD_REF", "").strip()
        if base_ref and head_ref and not re.fullmatch(r"0+", base_ref):
            diagram_command.extend(["--base-ref", base_ref, "--head-ref", head_ref])
        run(diagram_command)
        diff_check = ["git", "diff", "--check"]
        if base_ref and head_ref and not re.fullmatch(r"0+", base_ref):
            diff_check.append(f"{base_ref}...{head_ref}")
        run(diff_check)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Manual/release contract FAILED: {error}", file=sys.stderr)
        return 1
    print("Manual/release/diagram contract OK: change detection, provenance, and validation complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
