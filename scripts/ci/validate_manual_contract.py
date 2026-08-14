#!/usr/bin/env python3
"""Run the reproducible manual, release provenance, and diagram CI contract."""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "build/manual/module-inventory.json"
RELEASE_INVENTORY = ROOT / "build/manual/release-module-inventory.json"
MANIFEST = ROOT / "docs/manual/manifest.yaml"
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
        run(["python3", "scripts/ci/validate_ci_fanout.py", "--static"])
        run(["./gradlew", "exportManualModuleInventory", "--no-daemon", "--no-configuration-cache"])
        run(
            [
                "ruby",
                "scripts/manual/release_inventory.rb",
                release_ref,
                release_commit,
                str(INVENTORY.relative_to(ROOT)),
                str(RELEASE_INVENTORY.relative_to(ROOT)),
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
                "scripts/manual/validate_manuals.rb",
                str(RELEASE_INVENTORY.relative_to(ROOT)),
                str(MANIFEST.relative_to(ROOT)),
            ],
            env=validation_env,
        )
        run(["ruby", "scripts/manual/validate_release_manuals.rb", release_ref, release_commit])
        run(["ruby", "scripts/manual/export_manifest.rb", "--check"])
        run(
            [
                "ruby",
                "-I",
                "scripts/manual",
                "-e",
                'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }',
            ]
        )
        run(["ruby", "scripts/manual/validate_diagrams.rb"])
        run(["ruby", "scripts/manual/readme_jvm25_contract.rb"])
        run(["ruby", "scripts/manual/sync_release_diagrams.rb", "--check"])

        diagram_command = ["python3", "scripts/ci/validate_diagram_contract.py"]
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
