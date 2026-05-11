package io.bluetape4k.leader.mongodb.internal

import com.mongodb.MongoCommandException
import com.mongodb.MongoException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSecurityException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * MongoDB backend exception 분류 — T9 PR 4 (Issue #79).
 *
 * ## 동작/계약
 * - [MongoTimeoutException] / [MongoSocketException] /
 *   [MongoNodeIsRecoveringException] / [MongoNotPrimaryException] → [BackendErrorKind.TRANSIENT]
 *   (재시도 가능 — replica set 페일오버 / 네트워크 일시 단절)
 * - [MongoSecurityException] / [MongoWriteException] / 인증 관련 [MongoCommandException] (code 13/18) →
 *   [BackendErrorKind.NON_TRANSIENT] (영구 오류 — 권한 / write 실패)
 * - 그 외 [MongoException] → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(
 *     MongoBackendErrorClassifier,
 *     CoreBackendErrorClassifier,
 * )
 * ```
 */
internal object MongoBackendErrorClassifier : BackendErrorClassifier {

    private const val AUTH_FAILED = 13
    private const val AUTHENTICATION_FAILED = 18

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is MongoTimeoutException -> BackendErrorKind.TRANSIENT
        is MongoSocketException -> BackendErrorKind.TRANSIENT
        is MongoNodeIsRecoveringException -> BackendErrorKind.TRANSIENT
        is MongoNotPrimaryException -> BackendErrorKind.TRANSIENT
        is MongoSecurityException -> BackendErrorKind.NON_TRANSIENT
        is MongoWriteException -> BackendErrorKind.NON_TRANSIENT
        is MongoCommandException -> when (cause.errorCode) {
            AUTH_FAILED, AUTHENTICATION_FAILED -> BackendErrorKind.NON_TRANSIENT
            else -> BackendErrorKind.NON_TRANSIENT
        }
        is MongoException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
