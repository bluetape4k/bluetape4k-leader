package io.bluetape4k.leader

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderRunResultTest {

    companion object : KLogging()

    private val election = LocalLeaderElector()
    private val groupElection = LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 2))

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // --- LeaderElector.runIfLeaderResult ---

    @Test
    fun `runIfLeaderResult - 리더 선출 성공 시 Elected 반환`() {
        val result = election.runIfLeaderResult(randomLockName()) { "done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
    }

    @Test
    fun `runIfLeaderResult - action 이 null 반환해도 Elected 로 분류`() {
        val result = election.runIfLeaderResult(randomLockName()) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value.shouldBeNull()
    }

    @Test
    fun `runIfLeaderResult - 정수 결과도 Elected 로 반환`() {
        val result = election.runIfLeaderResult(randomLockName()) { 42 }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo 42
    }

    @Test
    fun `LeaderRunResult Elected - when 분기 망라 가능 — Skipped 분기 도달 불가`() {
        val result = election.runIfLeaderResult(randomLockName()) { "value" }
        val output = when (result) {
            is LeaderRunResult.Elected -> result.value
            is LeaderRunResult.Skipped -> null
        }
        output shouldBeEqualTo "value"
    }

    // --- LeaderGroupElector.runIfGroupLeaderResult ---

    @Test
    fun `runIfGroupLeaderResult - 슬롯 획득 성공 시 Elected 반환`() {
        val result = groupElection.runIfGroupLeaderResult(randomLockName()) { "group-done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "group-done"
    }

    @Test
    fun `runIfGroupLeaderResult - action 이 null 반환해도 Elected 로 분류`() {
        val result = groupElection.runIfGroupLeaderResult(randomLockName()) { null }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value.shouldBeNull()
    }

    // --- data class / object 특성 ---

    @Test
    fun `LeaderRunResult Elected - 동일 value 면 equals`() {
        val a = LeaderRunResult.Elected("hello")
        val b = LeaderRunResult.Elected("hello")
        a shouldBeEqualTo b
    }

    @Test
    fun `LeaderRunResult Skipped - singleton object`() {
        val a: LeaderRunResult<String> = LeaderRunResult.Skipped
        val b: LeaderRunResult<Int> = LeaderRunResult.Skipped
        (a === b) shouldBeEqualTo true
    }
}
