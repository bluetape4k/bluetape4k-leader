package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import kotlin.coroutines.Continuation
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Instant

class LeaderLeaseApiContractTest {

    @Test
    fun `blocking acquirer and handle expose the additive contract`() {
        val acquirer = LeaderLeaseAcquirer::class.java
        acquirer.getMethod("getConfiguredOptions").returnType shouldBeEqualTo LeaderElectionOptions::class.java
        acquirer.getMethod("tryAcquire", String::class.java).returnType shouldBeEqualTo LeaderLeaseHandle::class.java
        acquirer.getMethod("tryAcquire", LeaderSlot::class.java).returnType shouldBeEqualTo LeaderLeaseHandle::class.java

        val handle = LeaderLeaseHandle::class.java
        handle.getMethod("getLockName").returnType shouldBeEqualTo String::class.java
        handle.getMethod("getAuditLeaderId").returnType shouldBeEqualTo String::class.java
        handle.getMethod("getAcquiredAt").returnType shouldBeEqualTo Instant::class.java
        handle.getMethod("ownershipStatus").returnType shouldBeEqualTo LeaseOwnershipStatus::class.java
        handle.getMethod("isStillHeld").returnType shouldBeEqualTo Boolean::class.javaPrimitiveType
        handle.getMethod("release").returnType shouldBeEqualTo Void.TYPE
        handle.getMethod("close").returnType shouldBeEqualTo Void.TYPE
        AutoCloseable::class.java.isAssignableFrom(handle).shouldBeTrue()

        val extend = handle.declaredMethods.single { it.name.startsWith("extend-") && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) }
        extend.returnType shouldBeEqualTo ExtendOutcome::class.java
        Modifier.isPublic(extend.modifiers).shouldBeTrue()
    }

    @Test
    fun `suspend acquirer and handle preserve continuation descriptors`() {
        val continuation = Continuation::class.java
        val acquirer = SuspendLeaderLeaseAcquirer::class.java
        acquirer.getMethod("getConfiguredOptions").returnType shouldBeEqualTo LeaderElectionOptions::class.java
        acquirer.findContinuationMethod("tryAcquire", String::class.java).returnType shouldBeEqualTo Any::class.java
        acquirer.findContinuationMethod("tryAcquire", LeaderSlot::class.java).returnType shouldBeEqualTo Any::class.java

        val handle = SuspendLeaderLeaseHandle::class.java
        handle.getMethod("getLockName").returnType shouldBeEqualTo String::class.java
        handle.getMethod("getAuditLeaderId").returnType shouldBeEqualTo String::class.java
        handle.getMethod("getAcquiredAt").returnType shouldBeEqualTo Instant::class.java
        handle.findContinuationMethod("ownershipStatus").returnType shouldBeEqualTo Any::class.java
        handle.findContinuationMethod("isStillHeld").returnType shouldBeEqualTo Any::class.java
        handle.findContinuationMethod("release").returnType shouldBeEqualTo Any::class.java
        AutoCloseable::class.java.isAssignableFrom(handle).shouldBeFalse()

        continuation shouldBeEqualTo Continuation::class.java
    }

    @Test
    fun `rejected extension is a stable non-throwing outcome`() {
        ExtendOutcome.Rejected.isExtended.shouldBeFalse()
        ExtendOutcome.Rejected.shouldNotBeNull()
    }

    private fun Class<*>.findContinuationMethod(name: String, vararg leading: Class<*>): Method =
        declaredMethods.single {
            it.name == name &&
                it.parameterTypes.size == leading.size + 1 &&
                it.parameterTypes.take(leading.size).toTypedArray().contentEquals(leading) &&
                Continuation::class.java.isAssignableFrom(it.parameterTypes.last())
        }
}
