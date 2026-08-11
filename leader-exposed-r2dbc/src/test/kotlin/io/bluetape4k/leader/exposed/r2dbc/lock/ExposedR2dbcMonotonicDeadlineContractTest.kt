package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 단일·그룹 R2DBC 재시도 루프가 벽시계 시간에 의존하지 않는다는 계약을 고정합니다.
 *
 * 이 테스트는 현재 구현의 결함을 먼저 재현하는 RED 단계에서 사용하며,
 * monotonic deadline helper 적용 후에는 두 경로의 공통 계약을 검증합니다.
 */
class ExposedR2dbcMonotonicDeadlineContractTest {

    @Test
    fun `단일 및 그룹 retry loop는 monotonic deadline helper를 사용한다`() {
        val lockSources = listOf(
            "src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcLock.kt",
            "src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcGroupLock.kt",
        ).map { Path.of(it) }

        lockSources.all { source ->
            val content = Files.readString(source)
            content.contains("MonotonicDeadline") &&
                content.contains("MonotonicDeadline.fromNow(waitTime)") &&
                content.contains("deadline.remainingMillisForSleep()") &&
                !content.contains("System.currentTimeMillis()")
        }.shouldBeTrue()
    }
}
