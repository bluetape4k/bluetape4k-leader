package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException

internal object KubernetesBackendErrorClassifier : BackendErrorClassifier {
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val CONFLICT = 409
    private const val TOO_MANY_REQUESTS = 429

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is KubernetesClientTimeoutException -> BackendErrorKind.TRANSIENT
        is KubernetesClientException -> when (cause.code) {
            CONFLICT, TOO_MANY_REQUESTS -> BackendErrorKind.TRANSIENT
            UNAUTHORIZED, FORBIDDEN -> BackendErrorKind.NON_TRANSIENT
            in 500..599 -> BackendErrorKind.TRANSIENT
            else -> BackendErrorKind.NON_TRANSIENT
        }
        else -> null
    }
}
