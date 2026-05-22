package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.etcd.jetcd.lease.NoSuchLeaseException
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal enum class EtcdBackendErrorKind {
    EXPECTED_CLEANUP,
    TRANSIENT,
    NON_TRANSIENT,
}

/**
 * Classifies jetcd and gRPC errors for retry and cleanup decisions.
 */
internal object EtcdBackendErrorClassifier : BackendErrorClassifier {

    private val expectedCleanupMessages = listOf(
        "etcdserver: requested lease not found",
        "etcdserver: lease not found",
        "etcdserver: key not found",
    )

    fun classifyEtcd(cause: Throwable): EtcdBackendErrorKind? {
        val root = cause.unwrapFutureFailure()
        return when {
            isExpectedCleanup(root) -> EtcdBackendErrorKind.EXPECTED_CLEANUP
            root is ClosedChannelException -> EtcdBackendErrorKind.TRANSIENT
            root is StatusRuntimeException -> classifyStatus(root.status)
            root is StatusException -> classifyStatus(root.status)
            else -> null
        }
    }

    override fun classify(cause: Throwable): BackendErrorKind? =
        when (classifyEtcd(cause)) {
            EtcdBackendErrorKind.EXPECTED_CLEANUP -> BackendErrorKind.NON_TRANSIENT
            EtcdBackendErrorKind.TRANSIENT -> BackendErrorKind.TRANSIENT
            EtcdBackendErrorKind.NON_TRANSIENT -> BackendErrorKind.NON_TRANSIENT
            null -> null
        }

    fun isExpectedCleanup(cause: Throwable): Boolean {
        val root = cause.unwrapFutureFailure()
        return when {
            root is NoSuchLeaseException -> true
            else -> root.messageChain().any { message ->
                expectedCleanupMessages.any { expected -> message.contains(expected) }
            }
        }
    }

    private tailrec fun Throwable.unwrapFutureFailure(): Throwable {
        val nested = cause
        return when {
            this is CompletionException && nested != null -> nested.unwrapFutureFailure()
            this is ExecutionException && nested != null -> nested.unwrapFutureFailure()
            else -> this
        }
    }

    private fun classifyStatus(status: Status): EtcdBackendErrorKind =
        when (status.code) {
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.CANCELLED -> EtcdBackendErrorKind.TRANSIENT

            Status.Code.UNAUTHENTICATED,
            Status.Code.PERMISSION_DENIED -> EtcdBackendErrorKind.NON_TRANSIENT

            else -> EtcdBackendErrorKind.NON_TRANSIENT
        }

    private fun Throwable.messageChain(): Sequence<String> =
        generateSequence(this) { it.cause }
            .mapNotNull {
                it.message
                    ?: (it as? StatusRuntimeException)?.status?.description
                    ?: (it as? StatusException)?.status?.description
            }
}
