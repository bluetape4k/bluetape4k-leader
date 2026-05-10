package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [TenantAggregator] 설정.
 *
 * ## 동작/계약
 *
 * - [nodeId]: 본 집계 인스턴스 식별자. 로그/락 owner 기록 용도.
 * - [lockNamePrefix]: 테넌트별 분산 락 이름 prefix. 실제 락 이름은
 *   `"${lockNamePrefix}-${tenantId}"` 로 조립된다 (테넌트 단위 독립 leader-election).
 * - [tenants]: 집계 대상 테넌트 식별자 목록. 각 테넌트는 서로 독립적으로 leader-election 수행.
 *   - 빈 목록 금지
 *   - blank 항목 금지
 *   - 중복 항목 허용 (호출자 책임 — 같은 락에 두 번 시도해도 두 번째는 미선출)
 * - [pollInterval]: 사이클 간 휴지 시간. 리더 action 종료 후 다음 사이클까지의 대기,
 *   비리더가 락 미획득 후 재시도 전 대기에도 동일하게 적용된다.
 * - [waitTime]: 테넌트별 leader 락 획득 대기 시간. 짧게 설정하면 비리더가 빠르게 skip.
 * - [leaseTime]: 테넌트별 leader lease (락 TTL). aggregate 평균 실행 시간 + 안전 여유 (예: 2배) 권장.
 *
 * ### LeaderGroup 미사용 이유
 *
 * `LeaderGroupElector` 는 단일 lockName 의 maxLeaders 슬롯을 공유하므로
 * 어느 테넌트가 어느 슬롯에 배정될지 호출자가 제어하지 못한다. 본 집계기는
 * **테넌트별 독립 lockName** 으로 leader-election 을 수행하여
 * "테넌트 T 에 대해서는 정확히 1 인스턴스만 집계 코루틴 실행" 계약을 직접 만족시킨다 (E4 cache-warmer 와 동일).
 *
 * ```kotlin
 * TenantAggregatorOptions(
 *     nodeId = System.getenv("HOSTNAME") ?: "node-local",
 *     lockNamePrefix = "tenant-aggregator:metrics",
 *     tenants = listOf("tenant-A", "tenant-B", "tenant-C"),
 *     pollInterval = 5.seconds,
 *     waitTime = 1.seconds,
 *     leaseTime = 60.seconds,
 * )
 * ```
 */
data class TenantAggregatorOptions(
    val nodeId: String,
    val tenants: List<String>,
    val lockNamePrefix: String = "tenant-aggregator",
    val pollInterval: Duration = 5.seconds,
    val waitTime: Duration = 1.seconds,
    val leaseTime: Duration = 60.seconds,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockNamePrefix.requireNotBlank("lockNamePrefix")
        tenants.requireNotEmpty("tenants")
        tenants.forEachIndexed { idx, t -> t.requireNotBlank("tenants[$idx]") }
        pollInterval.inWholeMilliseconds.requirePositiveNumber("pollInterval")
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
