package io.bluetape4k.leader.examples.migration

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [MigrationGate] 설정.
 *
 * ## 동작/계약
 *
 * - [nodeId]: 본 인스턴스 식별자. 락 row 와 history 에 lockOwner 로 기록되어 운영 추적성 확보
 * - [lockName]: 분산 락 키. 환경/스키마/마이그레이션 셋 단위로 다르게 설정 권장
 *   (예: `"prod-app-schema-v3"`)
 * - [waitTime]: 비리더 인스턴스가 마이그레이션 완료를 대기하는 최대 시간
 * - [leaseTime]: 락 TTL. **마이그레이션 최악 실행 시간보다 길게** 설정해야 split-brain 방지
 *   (현재 backend 는 auto-extend 미지원)
 *
 * ```kotlin
 * MigrationGateOptions(
 *     nodeId = System.getenv("HOSTNAME") ?: "node-${UUID.randomUUID()}",
 *     lockName = "prod-app-schema-v3",
 *     waitTime = 30.seconds,
 *     leaseTime = 5.minutes,
 * )
 * ```
 */
data class MigrationGateOptions(
    val nodeId: String,
    val lockName: String,
    val waitTime: Duration = 30.seconds,
    val leaseTime: Duration = 5.minutes,
) {
    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
        waitTime.inWholeMilliseconds.requirePositiveNumber("waitTime")
        leaseTime.inWholeMilliseconds.requirePositiveNumber("leaseTime")
    }
}
