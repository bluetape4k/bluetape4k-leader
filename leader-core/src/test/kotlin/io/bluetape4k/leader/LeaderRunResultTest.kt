package io.bluetape4k.leader

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
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

    // --- LeaderGroupElector.runIfLeaderResult ---

    @Test
    fun `group runIfLeaderResult - 슬롯 획득 성공 시 Elected 반환`() {
        val result = groupElection.runIfLeaderResult(randomLockName()) { "group-done" }

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "group-done"
    }

    @Test
    fun `group runIfLeaderResult - action 이 null 반환해도 Elected 로 분류`() {
        val result = groupElection.runIfLeaderResult(randomLockName()) { null }

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

    // --- T58: Elected.leaderId (@JvmOverloads 호환성) ---

    @Test
    fun `Elected - 단일 인수 생성 시 leaderId 는 null`() {
        val r = LeaderRunResult.Elected(42)
        r.value shouldBeEqualTo 42
        r.leaderId.shouldBeNull()
    }

    @Test
    fun `Elected - leaderId 명시 생성 시 값 보존`() {
        val r = LeaderRunResult.Elected("done", leaderId = "node-alpha")
        r.value shouldBeEqualTo "done"
        r.leaderId shouldBeEqualTo "node-alpha"
    }

    @Test
    fun `Elected - leaderId 포함 equals`() {
        val a = LeaderRunResult.Elected("v", leaderId = "node-a")
        val b = LeaderRunResult.Elected("v", leaderId = "node-a")
        a shouldBeEqualTo b
    }

    @Test
    fun `Elected - leaderId 다르면 not equals`() {
        val a = LeaderRunResult.Elected("v", leaderId = "node-a")
        val b = LeaderRunResult.Elected("v", leaderId = "node-b")
        (a == b) shouldBeEqualTo false
    }

    @Test
    fun `Elected - leaderId null 과 명시 null 은 equals`() {
        val a = LeaderRunResult.Elected("v")
        val b = LeaderRunResult.Elected("v", leaderId = null)
        a shouldBeEqualTo b
    }

    @Test
    fun `Elected - copy 로 leaderId 교체 가능`() {
        val orig = LeaderRunResult.Elected("v", leaderId = "node-a")
        val updated = orig.copy(leaderId = "node-b")
        updated.leaderId shouldBeEqualTo "node-b"
        updated.value shouldBeEqualTo "v"
    }
}
