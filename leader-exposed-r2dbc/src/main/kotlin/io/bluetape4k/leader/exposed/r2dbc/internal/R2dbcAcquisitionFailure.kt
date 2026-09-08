package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

private val acquisitionErrorClassifier = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)

/** cause 체인의 취소와 JVM 오류를 보존하고 기존 backend 분류기로 재시도 여부를 결정합니다. */
internal fun classifyAcquisitionFailure(failure: Exception): BackendErrorKind {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    var deepest: Throwable = failure
    var backendKind: BackendErrorKind? = null
    while (current != null && seen.add(current)) {
        when (current) {
            is Error -> throw current
            is CancellationException -> throw current
        }
        if (backendKind == null) backendKind = ExposedR2dbcBackendErrorClassifier.classify(current)
        deepest = current
        current = current.cause
    }
    return backendKind ?: acquisitionErrorClassifier.classify(deepest)
}
