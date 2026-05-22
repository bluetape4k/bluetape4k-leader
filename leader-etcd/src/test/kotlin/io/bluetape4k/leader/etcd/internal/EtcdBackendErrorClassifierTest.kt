package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.internal.BackendErrorKind
import io.etcd.jetcd.lease.NoSuchLeaseException
import io.grpc.Status
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdBackendErrorClassifierTest {

    @Test
    fun `lease not found errors are expected cleanup`() {
        val leaseException = NoSuchLeaseException(42L)
        val statusException = Status.UNKNOWN
            .withDescription("etcdserver: requested lease not found")
            .asRuntimeException()

        EtcdBackendErrorClassifier.classifyEtcd(leaseException) shouldBeEqualTo EtcdBackendErrorKind.EXPECTED_CLEANUP
        EtcdBackendErrorClassifier.classifyEtcd(statusException) shouldBeEqualTo EtcdBackendErrorKind.EXPECTED_CLEANUP
        EtcdBackendErrorClassifier.isExpectedCleanup(statusException).shouldBeTrue()
        EtcdBackendErrorClassifier.classify(statusException) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `key not found cleanup is expected`() {
        val cause = Status.UNKNOWN
            .withDescription("etcdserver: key not found")
            .asRuntimeException()

        EtcdBackendErrorClassifier.classifyEtcd(cause) shouldBeEqualTo EtcdBackendErrorKind.EXPECTED_CLEANUP
    }

    @Test
    fun `transient grpc errors are classified as transient`() {
        listOf(
            Status.UNAVAILABLE,
            Status.DEADLINE_EXCEEDED,
            Status.CANCELLED,
        ).forEach { status ->
            val cause = status.asRuntimeException()

            EtcdBackendErrorClassifier.classifyEtcd(cause) shouldBeEqualTo EtcdBackendErrorKind.TRANSIENT
            EtcdBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.TRANSIENT
        }
    }

    @Test
    fun `auth grpc errors are non transient`() {
        listOf(
            Status.UNAUTHENTICATED,
            Status.PERMISSION_DENIED,
        ).forEach { status ->
            val cause = status.asRuntimeException()

            EtcdBackendErrorClassifier.classifyEtcd(cause) shouldBeEqualTo EtcdBackendErrorKind.NON_TRANSIENT
            EtcdBackendErrorClassifier.classify(cause) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
        }
    }

    @Test
    fun `closed channel is transient and unknown runtime exception delegates`() {
        EtcdBackendErrorClassifier.classify(ClosedChannelException()) shouldBeEqualTo BackendErrorKind.TRANSIENT
        EtcdBackendErrorClassifier.classify(RuntimeException("other")).shouldBeNull()
    }

    @Test
    fun `future wrappers are unwrapped before classification`() {
        val transient = CompletionException(Status.UNAVAILABLE.asRuntimeException())
        val expectedCleanup = ExecutionException(
            Status.UNKNOWN
                .withDescription("etcdserver: lease not found")
                .asRuntimeException(),
        )

        EtcdBackendErrorClassifier.classify(transient) shouldBeEqualTo BackendErrorKind.TRANSIENT
        EtcdBackendErrorClassifier.classifyEtcd(expectedCleanup) shouldBeEqualTo EtcdBackendErrorKind.EXPECTED_CLEANUP
    }
}
