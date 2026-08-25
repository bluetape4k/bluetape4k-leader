#!/usr/bin/env python3
"""Compare published JVM artifacts with the previous release.

The release gate intentionally reports every japicmp incompatibility, then
filters only compiler-generated Kotlin/AspectJ classes, synthetic accessor
methods, JVM class-file format changes, and explicitly retired Kotlin-internal
facades. A new public incompatibility must therefore be classified in the
migration notes or the command fails.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path


# Maven Central path for projectGroup=io.github.bluetape4k.leader.
REPOSITORY = "io/github/bluetape4k/leader"
ARTIFACTS = (
    ("leader-consul", ""),
    ("leader-core", ""),
    ("leader-dynamodb", ""),
    ("leader-etcd", ""),
    ("leader-exposed-core", ""),
    ("leader-exposed-jdbc", ""),
    ("leader-exposed-r2dbc", ""),
    ("leader-hazelcast", ""),
    ("leader-k8s", ""),
    ("leader-ktor", ""),
    ("leader-micrometer", ""),
    ("leader-mongodb", ""),
    ("leader-redis-lettuce", ""),
    ("leader-redis-redisson", ""),
    ("leader-spring-boot", "-plain"),
    ("leader-zookeeper", ""),
)

REPORT_START = re.compile(r"^(?:---!|\*\*!|\*\*\*!)")
CLASS_NAME = re.compile(r"(?:PUBLIC|PROTECTED|PACKAGE|PRIVATE).*?\s([\w.$]+)\s(?:\(|\(|$)")
CLASS_FILE_FORMAT_MARKER = "CLASS FILE FORMAT VERSION:"
LEGACY_INTERNAL_JVM_FACADES = frozenset(
    {
        "io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline",
        "io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline$Companion",
    },
)


def env_path(name: str, default: Path) -> Path:
    return Path(os.environ.get(name, str(default))).expanduser()


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    request = urllib.request.Request(url, headers={"User-Agent": "bluetape4k-leader-abi-gate"})
    with urllib.request.urlopen(request, timeout=60) as source, temporary.open("wb") as destination:
        while chunk := source.read(1024 * 1024):
            destination.write(chunk)
    temporary.replace(target)


def ensure_file(target: Path, url: str) -> Path:
    if target.is_file() and target.stat().st_size > 0:
        return target
    print(f"Downloading {url}")
    download(url, target)
    return target


def report_blocks(output: str) -> list[str]:
    blocks: list[str] = []
    current: list[str] = []
    for line in output.splitlines():
        if REPORT_START.match(line):
            if current:
                blocks.append("\n".join(current))
            current = [line]
        elif current:
            current.append(line)
    if current:
        blocks.append("\n".join(current))
    return blocks


def block_class_name(block: str) -> str:
    first = block.splitlines()[0]
    # japicmp puts the fully qualified class name at the end of the header.
    return first.partition("  (")[0].split()[-1]


def is_intentionally_ignored(block: str) -> str | None:
    name = block_class_name(block)
    header = block.splitlines()[0]
    has_class_file_format_change = any(
        CLASS_FILE_FORMAT_MARKER in line for line in block.splitlines()
    )
    incompatible_members = [
        line.lstrip() for line in block.splitlines()[1:]
        if line.lstrip().startswith(("---!", "***!"))
        and CLASS_FILE_FORMAT_MARKER not in line
    ]
    if "REMOVED CLASS:" in header and name in LEGACY_INTERNAL_JVM_FACADES:
        return "legacy Kotlin-internal JVM facade"
    if incompatible_members and all(
        "SYNTHETIC" in line and "access$" in line
        for line in incompatible_members
    ):
        return "compiler-generated synthetic accessor"
    if not incompatible_members and has_class_file_format_change:
        return "JVM class-file format"
    if ".internal." in name:
        return "Kotlin-internal implementation package"
    if "$AjcClosure" in name or "$$inlined$" in name or "$executeActionAsync$" in name:
        return "compiler-generated implementation class"
    if "$" in name and ("Strategic" in name or "mapNotNull" in name):
        return "compiler-generated implementation class"
    return None


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    base_version = os.environ.get("ABI_BASE_VERSION", "0.4.0")
    current_version = os.environ.get("ABI_CURRENT_VERSION", "0.5.0")
    japicmp_version = os.environ.get("ABI_JAPICMP_VERSION", "0.26.1")
    work = env_path("ABI_WORK_DIR", Path(os.environ.get("TMPDIR", "/tmp")) / "bluetape4k-leader-abi")
    old_dir = env_path("ABI_BASE_DIR", work / f"old-{base_version}")
    japicmp = env_path("ABI_JAPICMP_JAR", work / f"japicmp-{japicmp_version}-jar-with-dependencies.jar")
    japicmp_url = (
        "https://repo.maven.apache.org/maven2/com/github/siom79/japicmp/japicmp/"
        f"{japicmp_version}/japicmp-{japicmp_version}-jar-with-dependencies.jar"
    )
    ensure_file(japicmp, japicmp_url)

    unknown: list[tuple[str, str]] = []
    ignored: list[tuple[str, str, str]] = []
    for artifact, suffix in ARTIFACTS:
        maven_artifact = f"bluetape4k-{artifact}"
        filename = f"{maven_artifact}-{base_version}{suffix}.jar"
        old = ensure_file(
            old_dir / filename,
            f"https://repo.maven.apache.org/maven2/{REPOSITORY}/{maven_artifact}/{base_version}/{filename}",
        )
        current = root / artifact / "build" / "libs" / f"{maven_artifact}-{current_version}{suffix}.jar"
        if not current.is_file():
            print(f"Missing current artifact: {current}", file=sys.stderr)
            return 2
        command = [
            "java",
            "-jar",
            str(japicmp),
            "-o",
            str(old),
            "-n",
            str(current),
            "-b",
            "--ignore-missing-classes",
            "--include-synthetic",
            "-a",
            "public",
        ]
        result = subprocess.run(command, text=True, capture_output=True, check=False)
        if result.returncode != 0:
            print(result.stdout, end="")
            print(result.stderr, end="", file=sys.stderr)
            print(f"japicmp failed for {artifact}: exit {result.returncode}", file=sys.stderr)
            return result.returncode
        print(f"--- {artifact} ({base_version} -> {current_version}) ---")
        print(result.stdout, end="")
        for block in report_blocks(result.stdout):
            reason = is_intentionally_ignored(block)
            if reason:
                ignored.append((artifact, reason, block.splitlines()[0]))
            elif "!" in block.splitlines()[0]:
                unknown.append((artifact, block))

    print(f"ABI inventory: artifacts={len(ARTIFACTS)} ignored={len(ignored)} unknown={len(unknown)}")
    for artifact, reason, header in ignored:
        print(f"classified [{artifact}] {reason}: {header}")
    if unknown:
        print("Unclassified binary incompatibilities:", file=sys.stderr)
        for artifact, block in unknown:
            print(f"[{artifact}]\n{block}", file=sys.stderr)
        return 1
    print("Binary API compatibility gate passed: no unclassified public incompatibilities.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
