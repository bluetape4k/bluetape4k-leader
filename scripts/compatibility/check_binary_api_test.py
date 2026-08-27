"""Regression tests for binary-compatibility report classification."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import check_binary_api
from check_binary_api import is_intentionally_ignored


class BinaryApiClassificationTest(unittest.TestCase):
    def test_class_format_and_synthetic_accessor_are_ignored_together(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLockKt  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
\t---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.time.Instant access$dbCurrentTimestamp(org.jetbrains.exposed.v1.jdbc.JdbcTransaction)
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "compiler-generated synthetic accessor",
        )

    def test_class_file_format_only_is_ignored(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
"""

        self.assertEqual(is_intentionally_ignored(block), "JVM class-file format")

    def test_legacy_internal_facade_removal_is_allowlisted(self) -> None:
        block = """---! REMOVED CLASS: PUBLIC(-) FINAL(-) io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline  (not serializable)
\t---  CLASS FILE FORMAT VERSION: n.a. <- 65.0
\t---! REMOVED SUPERCLASS: java.lang.Object
\t---! REMOVED FIELD: PUBLIC(-) STATIC(-) FINAL(-) io.bluetape4k.leader.exposed.jdbc.lock.MonotonicDeadline$Companion Companion
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "legacy Kotlin-internal JVM facade",
        )

    def test_real_public_member_removal_with_class_format_stays_unclassified(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t***! CLASS FILE FORMAT VERSION: 69.0 <- 65.0
\t---! REMOVED METHOD: PUBLIC(-) FINAL(-) java.lang.String removedPublicMethod()
"""

        self.assertIsNone(is_intentionally_ignored(block))

    def test_public_bridge_member_removal_stays_unclassified(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t---! REMOVED METHOD: PUBLIC(-) STATIC(-) BRIDGE(-) java.lang.String removedBridge(java.lang.String)
"""

        self.assertIsNone(is_intentionally_ignored(block))

    def test_known_redis_bridge_descriptor_change_is_allowlisted(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.bluetape4k.leader.lettuce.LettuceLeaderElector  (not serializable)
\t===  CLASS FILE FORMAT VERSION: 65.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<T> runAsyncIfLeader(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)
\t\tGENERIC TEMPLATES: --- T:java.lang.Object
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<io.bluetape4k.leader.LeaderRunResult<T>> runAsyncIfLeaderResult(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)
\t\tGENERIC TEMPLATES: --- T:java.lang.Object
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "known Redis JVM bridge descriptor",
        )

    def test_known_redisson_accessor_and_bridge_descriptors_are_allowlisted_together(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.bluetape4k.leader.redisson.RedissonLeaderElector  (not serializable)
\t===  CLASS FILE FORMAT VERSION: 65.0 <- 65.0
\t===  UNCHANGED SUPERCLASS: java.lang.Object (<- java.lang.Object)
\t---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) void access$releaseLockAsync(io.bluetape4k.leader.redisson.RedissonLeaderElector, org.redisson.api.RLock, long, long)
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<T> runAsyncIfLeader(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)
\t\tGENERIC TEMPLATES: --- T:java.lang.Object
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<io.bluetape4k.leader.LeaderRunResult<T>> runAsyncIfLeaderResult(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)
\t\tGENERIC TEMPLATES: --- T:java.lang.Object
"""

        self.assertEqual(
            is_intentionally_ignored(block),
            "known Redis JVM bridge descriptor",
        )

    def test_known_bridge_class_with_an_extra_member_stays_unclassified(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.bluetape4k.leader.lettuce.LettuceLeaderElector  (not serializable)
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.util.concurrent.CompletableFuture<T> runAsyncIfLeader(io.bluetape4k.leader.LeaderSlot, java.util.concurrent.Executor, kotlin.jvm.functions.Function0<? extends java.util.concurrent.CompletableFuture<? extends T>>)
\t---! REMOVED METHOD: PUBLIC(-) BRIDGE(-) java.lang.String unrelated(java.lang.String)
"""

        self.assertIsNone(is_intentionally_ignored(block))

    def test_unrelated_synthetic_accessor_stays_unclassified(self) -> None:
        block = """***! MODIFIED CLASS: PUBLIC FINAL io.example.PublicApi  (not serializable)
\t---! REMOVED METHOD: PUBLIC(-) STATIC(-) FINAL(-) SYNTHETIC(-) java.lang.String access$unrelated(java.lang.String)
"""

        self.assertIsNone(is_intentionally_ignored(block))

    def test_version_resolution_uses_current_gradle_version_and_previous_release_tag(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "gradle.properties").write_text(
                "baseVersion=1.0.0\nsnapshotVersion=\n",
                encoding="utf-8",
            )
            git_tags = subprocess.CompletedProcess(
                ["git"],
                0,
                stdout="0.5.0\n0.4.0\n",
                stderr="",
            )
            with patch("check_binary_api.subprocess.run", return_value=git_tags):
                self.assertEqual(
                    self._resolve_versions(root, environ={}),
                    ("0.5.0", "1.0.0"),
                )

    def test_version_resolution_preserves_explicit_environment_overrides(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "gradle.properties").write_text(
                "baseVersion=1.0.0\nsnapshotVersion=\n",
                encoding="utf-8",
            )
            with patch("check_binary_api.subprocess.run") as git_run:
                self.assertEqual(
                    self._resolve_versions(
                        root,
                        environ={
                            "ABI_BASE_VERSION": "0.4.0",
                            "ABI_CURRENT_VERSION": "0.5.0",
                        },
                    ),
                    ("0.4.0", "0.5.0"),
                )
                git_run.assert_not_called()

    def test_version_resolution_fails_closed_without_previous_release_tag(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "gradle.properties").write_text(
                "baseVersion=1.0.0\nsnapshotVersion=\n",
                encoding="utf-8",
            )
            git_tags = subprocess.CompletedProcess(
                ["git"],
                0,
                stdout="",
                stderr="",
            )
            with patch("check_binary_api.subprocess.run", return_value=git_tags), self.assertRaisesRegex(
                ValueError,
                "baseline",
            ):
                self._resolve_versions(root, environ={})

    def test_version_resolution_fails_closed_for_invalid_current_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "gradle.properties").write_text(
                "baseVersion=not-a-release\nsnapshotVersion=\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "release version"):
                self._resolve_versions(root, environ={})

    def _resolve_versions(self, root: Path, environ: dict[str, str]) -> tuple[str, str]:
        self.assertTrue(
            hasattr(check_binary_api, "resolve_versions"),
            "version resolution helper must be implemented before this regression can pass",
        )
        return check_binary_api.resolve_versions(root, environ=environ)

    def test_public_bridge_is_linkable_by_an_existing_consumer(self) -> None:
        javac = shutil.which("javac")
        java = shutil.which("java")
        javap = shutil.which("javap")
        self.assertIsNotNone(javac, "JDK javac is required for the linkage fixture")
        self.assertIsNotNone(java, "JDK java is required for the linkage fixture")
        self.assertIsNotNone(javap, "JDK javap is required for the linkage fixture")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_sources = root / "old-sources" / "fixture"
            new_sources = root / "new-sources" / "fixture"
            consumer_sources = root / "consumer-sources" / "fixture"
            old_classes = root / "old-classes"
            new_classes = root / "new-classes"
            consumer_classes = root / "consumer-classes"
            for directory in (old_sources, new_sources, consumer_sources):
                directory.mkdir(parents=True)

            (old_sources / "GenericApi.java").write_text(
                """package fixture;

public interface GenericApi<T> {
    T value();
}
""",
                encoding="utf-8",
            )
            (old_sources / "PublicApi.java").write_text(
                """package fixture;

public final class PublicApi implements GenericApi<String> {
    @Override
    public String value() {
        return "old";
    }
}
""",
                encoding="utf-8",
            )
            (new_sources / "PublicApi.java").write_text(
                """package fixture;

public final class PublicApi {
    public String value() {
        return "new";
    }
}
""",
                encoding="utf-8",
            )
            (consumer_sources / "Consumer.java").write_text(
                """package fixture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class Consumer {
    public static void main(String[] args) throws Throwable {
        MethodHandle value = MethodHandles.lookup().findVirtual(
            PublicApi.class,
            "value",
            MethodType.methodType(Object.class)
        );
        System.out.print((Object) value.invoke(new PublicApi()));
    }
}
""",
                encoding="utf-8",
            )

            subprocess.run(
                [
                    javac,
                    "-d",
                    old_classes,
                    old_sources / "GenericApi.java",
                    old_sources / "PublicApi.java",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [javac, "-d", new_classes, new_sources / "PublicApi.java"],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    javac,
                    "-cp",
                    old_classes,
                    "-d",
                    consumer_classes,
                    consumer_sources / "Consumer.java",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            bytecode = subprocess.run(
                [javap, "-v", "-classpath", old_classes, "fixture.PublicApi"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            self.assertIn("ACC_BRIDGE", bytecode)

            old_run = subprocess.run(
                [
                    java,
                    "-cp",
                    f"{old_classes}{os.pathsep}{consumer_classes}",
                    "fixture.Consumer",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(old_run.stdout, "old")

            new_run = subprocess.run(
                [
                    java,
                    "-cp",
                    f"{new_classes}{os.pathsep}{consumer_classes}",
                    "fixture.Consumer",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(new_run.returncode, 0)
            self.assertIn("NoSuchMethodException", new_run.stderr)

    def test_public_bridge_flag_change_keeps_an_existing_consumer_linkable(self) -> None:
        javac = shutil.which("javac")
        java = shutil.which("java")
        javap = shutil.which("javap")
        self.assertIsNotNone(javac, "JDK javac is required for the linkage fixture")
        self.assertIsNotNone(java, "JDK java is required for the linkage fixture")
        self.assertIsNotNone(javap, "JDK javap is required for the linkage fixture")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_sources = root / "old-sources" / "fixture"
            new_sources = root / "new-sources" / "fixture"
            consumer_sources = root / "consumer-sources" / "fixture"
            old_classes = root / "old-classes"
            new_classes = root / "new-classes"
            consumer_classes = root / "consumer-classes"
            for directory in (old_sources, new_sources, consumer_sources):
                directory.mkdir(parents=True)

            (old_sources / "GenericApi.java").write_text(
                """package fixture;

public interface GenericApi<T> {
    T value();
}
""",
                encoding="utf-8",
            )
            (old_sources / "PublicApi.java").write_text(
                """package fixture;

public final class PublicApi implements GenericApi<String> {
    @Override
    public String value() {
        return "old";
    }
}
""",
                encoding="utf-8",
            )
            (new_sources / "PublicApi.java").write_text(
                """package fixture;

public final class PublicApi {
    public Object value() {
        return "new";
    }
}
""",
                encoding="utf-8",
            )
            (consumer_sources / "Consumer.java").write_text(
                """package fixture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class Consumer {
    public static void main(String[] args) throws Throwable {
        MethodHandle value = MethodHandles.lookup().findVirtual(
            PublicApi.class,
            "value",
            MethodType.methodType(Object.class)
        );
        System.out.print((Object) value.invoke(new PublicApi()));
    }
}
""",
                encoding="utf-8",
            )

            subprocess.run(
                [
                    javac,
                    "-d",
                    old_classes,
                    old_sources / "GenericApi.java",
                    old_sources / "PublicApi.java",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [javac, "-d", new_classes, new_sources / "PublicApi.java"],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    javac,
                    "-cp",
                    old_classes,
                    "-d",
                    consumer_classes,
                    consumer_sources / "Consumer.java",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            old_bytecode = subprocess.run(
                [javap, "-v", "-classpath", old_classes, "fixture.PublicApi"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            new_bytecode = subprocess.run(
                [javap, "-v", "-classpath", new_classes, "fixture.PublicApi"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            self.assertIn("ACC_BRIDGE", old_bytecode)
            self.assertNotIn("ACC_BRIDGE", new_bytecode)

            old_run = subprocess.run(
                [
                    java,
                    "-cp",
                    f"{old_classes}{os.pathsep}{consumer_classes}",
                    "fixture.Consumer",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(old_run.stdout, "old")

            new_run = subprocess.run(
                [
                    java,
                    "-cp",
                    f"{new_classes}{os.pathsep}{consumer_classes}",
                    "fixture.Consumer",
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(new_run.stdout, "new")

    def test_unrelated_public_class_removal_stays_unclassified(self) -> None:
        block = """---! REMOVED CLASS: PUBLIC(-) FINAL(-) io.example.RemovedPublicApi  (not serializable)
\t---! REMOVED SUPERCLASS: java.lang.Object
"""

        self.assertIsNone(is_intentionally_ignored(block))


if __name__ == "__main__":
    unittest.main()
