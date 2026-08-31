#!/usr/bin/env python3
"""Compare published JVM artifacts with the previous release.

The release gate reports every japicmp incompatibility, then filters only
compiler-generated classes, exact historical JVM descriptors, JVM class-file
format changes, and explicitly retired Kotlin-internal facades. A new public
incompatibility must therefore be classified in the migration notes or the
command fails.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import urllib.request
from collections.abc import Mapping
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

# These methods were emitted by Kotlin compiler changes between released
# versions.  Keep the allowlist keyed by the owning class and the complete
# japicmp descriptor: a generic ``SYNTHETIC``/``access$`` rule would hide a
# genuinely linkable public method.
KNOWN_SYNTHETIC_ACCESSORS: dict[str, frozenset[str]] = {
    "io.bluetape4k.leader.consul.ConsulLeaderGroupElectorKt": frozenset(
        {"PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) long access$remainingMillis(long)"}
    ),
    "io.bluetape4k.leader.consul.ConsulSuspendLeaderGroupElectorKt": frozenset(
        {"PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) long access$remainingMillis(long)"}
    ),
    "io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLockKt": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.time.Instant "
            + "access$dbCurrentTimestamp(org.jetbrains.exposed.v1.jdbc.JdbcTransaction)"
        }
    ),
    "io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) "
            + "java.util.concurrent.atomic.AtomicInteger "
            + "access$getCachedActiveCount$p(io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector)"
        }
    ),
    "io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLock": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.Object "
            + "access$currentTime(io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLock, "
            + "org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction, kotlin.coroutines.Continuation)"
        }
    ),
    "io.bluetape4k.leader.ktor.LeaderElectionManagementRouteKt": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.String "
            + "access$toJson(io.bluetape4k.leader.ktor.LeaderElectionManagementRegistry, "
            + "io.bluetape4k.leader.coroutines.SuspendLeaderElector)"
        }
    ),
    "io.bluetape4k.leader.lettuce.LettuceSuspendCandidateRegistry": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.String "
            + "access$indexKey(io.bluetape4k.leader.lettuce.LettuceSuspendCandidateRegistry, "
            + "java.lang.String)",
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.Object "
            + "access$scanKeys(io.bluetape4k.leader.lettuce.LettuceSuspendCandidateRegistry, "
            + "java.lang.String, kotlin.coroutines.Continuation)"
        }
    ),
    "io.bluetape4k.leader.LeaderLeaseAutoExtender": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.Object "
            + "access$start_dWUq8MI$doSuspendTick(java.util.concurrent.atomic.AtomicBoolean, "
            + "java.util.concurrent.atomic.AtomicReference, "
            + "io.bluetape4k.leader.internal.SuspendExtendDelegate, long, long, "
            + "io.bluetape4k.leader.internal.BackendErrorClassifier, kotlin.coroutines.Continuation)"
        }
    ),
    "io.bluetape4k.leader.spring.aop.LeaderElectionAspect": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.util.concurrent.ConcurrentHashMap "
            + "access$getMetadataCache$p(io.bluetape4k.leader.spring.aop.LeaderElectionAspect)",
            "PUBLIC(-) STATIC(-) FINAL(-) "
            + "SYNTHETIC(-) io.bluetape4k.leader.spring.aop.internal.AdviceMetadata "
            + "access$resolveMetadata(io.bluetape4k.leader.spring.aop.LeaderElectionAspect, "
            + "java.lang.reflect.Method, java.lang.Object)",
        }
    ),
    "io.bluetape4k.leader.spring.route.mvc.LeaderMvcRouteGuardFactory": frozenset(
        {
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) "
            + "io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties "
            + "access$getProperties$p(io.bluetape4k.leader.spring.route.mvc.LeaderMvcRouteGuardFactory)",
            "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) "
            + "io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime "
            + "access$getRuntime$p(io.bluetape4k.leader.spring.route.mvc.LeaderMvcRouteGuardFactory)",
        }
    ),
}

KNOWN_REDIS_BRIDGE_METHODS: dict[str, frozenset[str]] = {
    class_name: frozenset(
        {
            "PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<T> "
            + "runAsyncIfLeader(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, "
            + "kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)",
            "PUBLIC(-) BRIDGE(-) "
            + "java.util.concurrent.CompletableFuture<io.bluetape4k.leader.LeaderRunResult<T>> "
            + "runAsyncIfLeaderResult(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, "
            + "kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)",
        }
    )
    for class_name in (
        "io.bluetape4k.leader.lettuce.LettuceLeaderElector",
        "io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector",
        "io.bluetape4k.leader.redisson.RedissonLeaderGroupElector",
    )
}
KNOWN_REDIS_BRIDGE_METHODS["io.bluetape4k.leader.redisson.RedissonLeaderElector"] = frozenset(
    {
        *KNOWN_REDIS_BRIDGE_METHODS["io.bluetape4k.leader.lettuce.LettuceLeaderElector"],
        "PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) void "
        + "access$releaseLockAsync(io.bluetape4k.leader.redisson.RedissonLeaderElector, "
        + "org.redisson.api.RLock, long, long)",
    }
)

VERSION_PATTERN = re.compile(r"^v?(?P<release>\d+(?:\.\d+){1,3})(?:[-+][0-9A-Za-z.-]+)?$")


def env_path(name: str, default: Path) -> Path:
    return Path(os.environ.get(name, str(default))).expanduser()


def _gradle_properties(root: Path) -> dict[str, str]:
    properties_file = root / "gradle.properties"
    if not properties_file.is_file():
        raise ValueError(f"Missing Gradle version properties: {properties_file}")
    properties: dict[str, str] = {}
    for raw_line in properties_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def _release_version(value: str) -> tuple[int, ...] | None:
    match = VERSION_PATTERN.fullmatch(value.strip())
    if match is None:
        return None
    return tuple(int(part) for part in match.group("release").split("."))


def _previous_release_tag(root: Path, current_version: str) -> str:
    current_release = _release_version(current_version)
    if current_release is None:
        raise ValueError(f"Current version is not a release version: {current_version}")
    result = subprocess.run(
        ["git", "tag", "--list", "--sort=-v:refname"],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit {result.returncode}"
        raise ValueError(f"Unable to read release tags for ABI baseline: {detail}")
    candidates: list[tuple[tuple[int, ...], str]] = []
    for raw_tag in result.stdout.splitlines():
        tag = raw_tag.strip()
        release = _release_version(tag)
        if release is None:
            continue
        if release >= current_release:
            continue
        candidates.append((release, tag))
    if not candidates:
        raise ValueError(
            f"Could not resolve a previous release tag for ABI baseline before {current_version}"
        )
    return max(candidates)[1]


def resolve_versions(root: Path, environ: Mapping[str, str] | None = None) -> tuple[str, str]:
    """Resolve the ABI baseline/current versions, failing closed on ambiguity."""

    values = os.environ if environ is None else environ
    properties: dict[str, str] | None = None

    current_version = values.get("ABI_CURRENT_VERSION", "").strip()
    if not current_version:
        properties = _gradle_properties(root)
        base_version = properties.get("baseVersion", "").strip()
        if not base_version:
            raise ValueError("Missing baseVersion in gradle.properties")
        current_version = f"{base_version}{properties.get('snapshotVersion', '').strip()}"

    base_version = values.get("ABI_BASE_VERSION", "").strip()
    if not base_version:
        base_version = _previous_release_tag(root, current_version).removeprefix("v")

    if not base_version or not current_version:
        raise ValueError("Binary compatibility baseline and current versions are required")
    if _release_version(base_version) is None:
        raise ValueError(f"Base version is not a release version: {base_version}")
    if _release_version(current_version) is None:
        raise ValueError(f"Current version is not a release version: {current_version}")
    return base_version, current_version


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


def _member_descriptor(line: str) -> str | None:
    marker = "REMOVED METHOD:"
    if marker not in line:
        return None
    return " ".join(line.partition(marker)[2].strip().split())


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
    member_descriptors = {
        descriptor
        for line in incompatible_members
        if (descriptor := _member_descriptor(line)) is not None
    }
    if (
        member_descriptors
        and len(member_descriptors) == len(incompatible_members)
        and name in KNOWN_REDIS_BRIDGE_METHODS
        and member_descriptors <= KNOWN_REDIS_BRIDGE_METHODS[name]
    ):
        return "known Redis JVM bridge descriptor"
    if (
        member_descriptors
        and len(member_descriptors) == len(incompatible_members)
        and name in KNOWN_SYNTHETIC_ACCESSORS
        and member_descriptors <= KNOWN_SYNTHETIC_ACCESSORS[name]
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
    current_artifact_root = env_path("ABI_CURRENT_DIR", root)
    try:
        base_version, current_version = resolve_versions(root)
    except ValueError as error:
        print(f"Binary compatibility version resolution failed: {error}", file=sys.stderr)
        return 2
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
        current = (
            current_artifact_root
            / artifact
            / "build"
            / "libs"
            / f"{maven_artifact}-{current_version}{suffix}.jar"
        )
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
