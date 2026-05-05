package io.bluetape4k.leader.metrics

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkipReasonTest {

    companion object : KLogging()

    @Test
    fun `enum 항목 2개 존재 확인`() {
        SkipReason.entries.size shouldBeEqualTo 2
    }

    @Test
    fun `CONTENTION, BACKEND_ERROR 모두 포함`() {
        val entries = SkipReason.entries.map { it.name }
        entries shouldContainAll listOf("CONTENTION", "BACKEND_ERROR")
    }

    @Test
    fun `valueOf 동작 확인`() {
        SkipReason.valueOf("CONTENTION") shouldBeEqualTo SkipReason.CONTENTION
        SkipReason.valueOf("BACKEND_ERROR") shouldBeEqualTo SkipReason.BACKEND_ERROR
    }
}
