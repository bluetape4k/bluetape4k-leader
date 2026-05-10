package io.bluetape4k.leader.examples.webhook

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [WebhookPoller] 설정.
 *
 * ## 동작/계약
 *
 * - [nodeId]: 본 polling 인스턴스 식별자. 점유한 event 의 `claimedBy` 에 기록.
 * - [lockName]: leader-election 분산 락 이름. **이벤트 컬렉션 단위**로 다르게 설정 권장.
 * - [pollInterval]: 폴링 사이클 간 휴지 시간. 리더 batch 처리 후 다음 사이클까지의 대기, 비리더가
 *   리더 락 미획득 후 재시도 전 대기에도 동일하게 적용된다.
 * - [batchSize]: 한 사이클에 처리할 최대 event 수 (atomic claim 반복 횟수 상한).
 * - [maxAttempts]: handler 호출 누적 횟수 상한. 도달 시 event status = `FAILED` (DLQ 대체).
 * - [claimDuration]: claim 후 다른 인스턴스가 reclaim 가능해질 때까지의 lease 시간.
 *   handler 가 [claimDuration] 보다 오래 걸리면 다른 인스턴스가 동일 event 를 재점유할 수 있으므로,
 *   handler 평균 실행 시간 + 안전 여유 (예: 2배) 이상으로 설정 필수.
 *
 * ### Leader-election 옵션은 elector 에서 관리
 *
 * `waitTime` / `leaseTime` 등 리더 락 자체의 옵션은 외부에서 주입되는 elector
 * (예: [io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector])
 * 의 `MongoLeaderElectionOptions.leaderOptions` 에서 설정한다. 본 옵션과 별도로 관리하여
 * desync 방지.
 *
 * ```kotlin
 * WebhookPollerOptions(
 *     nodeId = System.getenv("HOSTNAME"),
 *     lockName = "webhook-poller:prod",
 *     pollInterval = 500.milliseconds,
 *     batchSize = 20,
 *     maxAttempts = 5,
 *     claimDuration = 30.seconds,
 * )
 * ```
 */
data class WebhookPollerOptions(
    val nodeId: String,
    val lockName: String,
    val pollInterval: Duration = 1.seconds,
    val batchSize: Int = 10,
    val maxAttempts: Int = 5,
    val claimDuration: Duration = 30.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
        pollInterval.inWholeMilliseconds.requirePositiveNumber("pollInterval")
        batchSize.requirePositiveNumber("batchSize")
        maxAttempts.requirePositiveNumber("maxAttempts")
        claimDuration.inWholeMilliseconds.requirePositiveNumber("claimDuration")
    }
}
