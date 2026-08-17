package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

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
                (it.name.startsWith("hasObservers") || it.name.startsWith("publish"))
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
}
