package io.bluetape4k.leader.annotation

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderAspectFailureModeTest {

    companion object : KLogging()

    @Test
    fun `enum 항목 3개 존재 확인`() {
        LeaderAspectFailureMode.entries.size shouldBeEqualTo 3
    }

    @Test
    fun `INHERIT, RETHROW, SKIP 모두 포함`() {
        val entries = LeaderAspectFailureMode.entries.map { it.name }
        entries shouldContainAll listOf("INHERIT", "RETHROW", "SKIP")
    }

    @Test
    fun `valueOf 동작 확인`() {
        LeaderAspectFailureMode.valueOf("INHERIT") shouldBeEqualTo LeaderAspectFailureMode.INHERIT
        LeaderAspectFailureMode.valueOf("RETHROW") shouldBeEqualTo LeaderAspectFailureMode.RETHROW
        LeaderAspectFailureMode.valueOf("SKIP") shouldBeEqualTo LeaderAspectFailureMode.SKIP
    }
}
