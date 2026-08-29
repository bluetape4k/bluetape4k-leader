package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import javax.tools.ToolProvider

class LeaderLeaseExtensionApiContractTest {

    @Test
    fun `observer facade exposes only the approved public API`() {
        val facade = LeaderLeaseExtensionObservers::class.java

        facade.superclass shouldBeEqualTo Any::class.java

        facade.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet() shouldBeEqualTo setOf("addObserver", "removeObserver", "droppedCount")

        val addObserver = facade.getDeclaredMethod("addObserver", LeaderLeaseExtensionObserver::class.java)
        addObserver.returnType shouldBeEqualTo AutoCloseable::class.java
        Modifier.isStatic(addObserver.modifiers).shouldBeTrue()

        val removeObserver = facade.getDeclaredMethod("removeObserver", LeaderLeaseExtensionObserver::class.java)
        removeObserver.returnType shouldBeEqualTo Boolean::class.javaPrimitiveType
        Modifier.isStatic(removeObserver.modifiers).shouldBeTrue()

        val droppedCount = facade.getDeclaredMethod("droppedCount")
        droppedCount.returnType shouldBeEqualTo Long::class.javaPrimitiveType
        Modifier.isStatic(droppedCount.modifiers).shouldBeTrue()
    }

    @Test
    fun `internal bridges remain synthetic and value constructors retain Java descriptors`() {
        val facade = LeaderLeaseExtensionObservers::class.java
        val bridgeMethods = facade.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) &&
                ((it.name.startsWith("hasObservers") && it.parameterCount == 0) ||
                    (it.name.startsWith("publish") && it.parameterCount == 1))
        }

        bridgeMethods.size shouldBeEqualTo 2
        bridgeMethods.all { it.isSynthetic }.shouldBeTrue()
        bridgeMethods.all { Modifier.isPublic(it.modifiers) }.shouldBeTrue()

        LeaderLeaseExtensionContext::class.java.getDeclaredConstructor(String::class.java, String::class.java)
        LeaderLeaseExtensionEvent::class.java.getDeclaredConstructor(
            LeaderLeaseExtensionSource::class.java,
            LeaderLeaseExtensionExecution::class.java,
            ExtendOutcome::class.java,
            Long::class.javaPrimitiveType,
            LeaderLeaseExtensionContext::class.java,
        )
    }

    @Test
    fun `scoped Kotlin bridges are synthetic and scope has no public ambient accessor`() {
        val facadeMethods = LeaderLeaseExtensionObservers::class.java.declaredMethods
        val scopedBridges = facadeMethods.filter { method ->
            Modifier.isPublic(method.modifiers) &&
                (method.name == "addScopedObserver" ||
                    (method.name == "hasObservers" && method.parameterCount == 1) ||
                    (method.name == "publish" && method.parameterCount == 2))
        }

        scopedBridges.size shouldBeEqualTo 3
        scopedBridges.all { it.isSynthetic }.shouldBeTrue()
        scopedBridges.all { Modifier.isPublic(it.modifiers) }.shouldBeTrue()

        val scopeClass = LeaderLeaseExtensionObservationScope::class.java
        scopeClass.declaredConstructors
            .filterNot { it.isSynthetic }
            .all { Modifier.isPrivate(it.modifiers) }
            .shouldBeTrue()
        scopeClass.declaredMethods.single { it.name == "withScope" && it.parameterCount == 1 }
            .isSynthetic.shouldBeTrue()
        scopeClass.getDeclaredMethod("asContextElement").isSynthetic.shouldBeTrue()
        scopeClass.declaredMethods
            .any { Modifier.isPublic(it.modifiers) && it.name.startsWith("current") }
            .shouldBeFalse()
        LeaderLeaseExtensionObservationScope.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("current") }
            .all { it.isSynthetic }
            .shouldBeTrue()
    }

    @Test
    fun `Java source cannot call scoped Kotlin bridges`() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val sourceDir = Files.createTempDirectory("leader-scope-java-api")
        val source = sourceDir.resolve("ScopedObserverJavaFixture.java")
        Files.writeString(
            source,
            """
            import io.bluetape4k.leader.*;

            final class ScopedObserverJavaFixture {
                void invoke(LeaderLeaseExtensionEvent event) {
                    LeaderLeaseExtensionObservationScope scope =
                        LeaderLeaseExtensionObservers.INSTANCE.addScopedObserver(ignored -> {});
                    scope.withScope(() -> null);
                    scope.asContextElement();
                    LeaderLeaseExtensionObservers.INSTANCE.hasObservers(scope);
                    LeaderLeaseExtensionObservers.INSTANCE.publish(event, scope);
                }
            }
            """.trimIndent(),
        )

        try {
            val exitCode = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                source.toString(),
            )
            (exitCode == 0).shouldBeFalse()
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(sourceDir)
        }
    }
}
