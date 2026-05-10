package io.bluetape4k.leader.examples.warmer

/**
 * [CachePartitionWarmer.warmAll] 결과.
 *
 * ## 동작/계약
 *
 * - [warmed]: 본 인스턴스가 leader 로 선출되어 워밍을 마친 partition 목록.
 *   handler 가 정상 종료한 경우만 포함된다.
 * - [skipped]: 본 인스턴스가 leader 미선출 (다른 인스턴스가 워밍 중) 인 partition 목록.
 * - [failed]: handler 가 예외를 던진 partition. key=partitionId, value=예외 메시지
 *   (메시지가 없으면 클래스 이름).
 *
 * 세 컬렉션은 mutually exclusive 하며, 합치면 본 인스턴스가 시도한 전체 partition 집합과 같다.
 *
 * ```kotlin
 * val result = warmer.warmAll()
 * println("워밍 완료: ${result.warmed}")
 * println("skip: ${result.skipped}")
 * if (result.failed.isNotEmpty()) error("실패: ${result.failed}")
 * ```
 */
data class WarmResult(
    val nodeId: String,
    val warmed: List<String>,
    val skipped: List<String>,
    val failed: Map<String, String>,
)
