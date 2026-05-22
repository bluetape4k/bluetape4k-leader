package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import java.net.http.HttpTimeoutException

internal object ConsulBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is HttpTimeoutException -> BackendErrorKind.TRANSIENT
        else -> null
    }
}
