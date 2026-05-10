package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [CachePartitionWarmer] 설정.
 *
 * ## 동작/계약
 *
 * - [nodeId]: 본 워머 인스턴스 식별자. 로그 + [WarmResult.nodeId] 노출용.
 * - [lockNamePrefix]: 파티션별 분산 락 이름 prefix. 실제 락 이름은
 *   `"${lockNamePrefix}-${partitionId}"` 로 조립된다 (파티션 단위 독립 leader-election).
 * - [partitions]: 워밍할 파티션 식별자 목록. 각 파티션은 서로 독립적으로 leader-election 수행.
 *   - 빈 목록 금지
 *   - blank 항목 금지
 *   - 중복 항목 허용 (호출자 책임 — 같은 락에 두 번 시도해도 두 번째는 미선출)
 * - [waitTime]: 파티션별 leader 락 획득 대기 시간. 짧게 설정하면 비리더가 빠르게 skip.
 * - [leaseTime]: 파티션별 leader lease. handler 평균 실행 시간 + 안전 여유 (예: 2배) 권장.
 *
 * ### LeaderGroup 미사용 이유
 *
 * Hazelcast 의 `LeaderGroupElector` 는 단일 lockName 의 maxLeaders 슬롯을 공유하므로
 * 어느 파티션이 어느 슬롯에 배정될지 호출자가 제어하지 못한다. 본 워머는 **파티션별 독립 lockName**
 * 으로 leader-election 을 수행하여 "파티션 P 에 대해서는 정확히 1개 인스턴스만 워밍" 계약을
 * 직접 표현한다.
 *
 * ```kotlin
 * CachePartitionWarmerOptions(
 *     nodeId = System.getenv("HOSTNAME") ?: "node-local",
 *     lockNamePrefix = "warmer:product-cache",
 *     partitions = listOf("region-asia", "region-eu", "region-us"),
 *     waitTime = 2.seconds,
 *     leaseTime = 30.seconds,
 * )
 * ```
 */
data class CachePartitionWarmerOptions(
    val nodeId: String,
    val partitions: List<String>,
    val lockNamePrefix: String = "warmer",
    val waitTime: Duration = 5.seconds,
    val leaseTime: Duration = 1.minutes,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockNamePrefix.requireNotBlank("lockNamePrefix")
        partitions.requireNotEmpty("partitions")
        partitions.forEachIndexed { idx, p -> p.requireNotBlank("partitions[$idx]") }
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
