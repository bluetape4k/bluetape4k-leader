package io.bluetape4k.leader.zookeeper.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.apache.zookeeper.KeeperException

/**
 * ZooKeeper backend exception 분류 — T13 PR 8 (Issue #79).
 *
 * ## 동작/계약
 * - [KeeperException.ConnectionLossException] → [BackendErrorKind.TRANSIENT]
 *   (네트워크 일시 단절 — 재시도 가능)
 * - [KeeperException.OperationTimeoutException] → [BackendErrorKind.TRANSIENT]
 *   (요청 타임아웃 — 재시도 가능)
 * - [KeeperException.SessionExpiredException] → [BackendErrorKind.NON_TRANSIENT]
 *   (세션 만료 — ephemeral 락 자동 해제, 복구 불가)
 * - [KeeperException.SessionMovedException] → [BackendErrorKind.NON_TRANSIENT]
 *   (세션 이동 — 클라이언트가 다른 server 로 연결됨, 락 상태 보장 불가)
 * - 그 외 [KeeperException] → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 *
 * ## 비고 (R16)
 * ZooKeeper 는 세션 기반 — TTL 개념이 없어 [io.bluetape4k.leader.LeaderLeaseAutoExtender] watchdog
 * 가 비활성화되므로 backend 오류 분류는 주로 caller-driven `LockExtender.extendActiveLock` 경로에서만 사용됩니다.
 */
internal object ZooKeeperBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is KeeperException.ConnectionLossException -> BackendErrorKind.TRANSIENT
        is KeeperException.OperationTimeoutException -> BackendErrorKind.TRANSIENT
        is KeeperException.SessionExpiredException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException.SessionMovedException -> BackendErrorKind.NON_TRANSIENT
        is KeeperException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
