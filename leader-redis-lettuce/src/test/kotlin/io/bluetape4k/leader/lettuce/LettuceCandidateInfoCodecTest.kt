package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.jupiter.api.Test
import java.time.Instant

class LettuceCandidateInfoCodecTest: AbstractLettuceLeaderTest() {

    @Test
    fun `roundtrip - 기본 필드 보존`() {
        val original = CandidateInfo(
            nodeId = "node-1",
            registeredAt = Instant.parse("2026-04-30T10:00:00Z"),
            lastStartTime = Instant.parse("2026-04-30T10:01:00Z"),
            lastCompletionTime = Instant.parse("2026-04-30T10:02:00Z"),
            successCount = 7,
            failureCount = 3,
            metadata = mapOf("region" to "ap-northeast-2", "version" to "1.0.0"),
        )

        val encoded = LettuceCandidateInfoCodec.encode(original)
        val decoded = LettuceCandidateInfoCodec.decode(encoded)

        decoded shouldBeEqualTo original
    }

    @Test
    fun `roundtrip - null 시각 필드 보존`() {
        val original = CandidateInfo(nodeId = "n1", registeredAt = Instant.now())
        val encoded = LettuceCandidateInfoCodec.encode(original)
        val decoded = LettuceCandidateInfoCodec.decode(encoded)
        decoded.lastStartTime shouldBeEqualTo null
        decoded.lastCompletionTime shouldBeEqualTo null
        decoded.metadata shouldBeEqualTo emptyMap()
    }

    @Test
    fun `roundtrip - metadata 값에 percent encoded 토큰 리터럴 포함 (CRITICAL 회귀 테스트)`() {
        // 인코딩된 `%7C` (즉, 사용자 입력이 `%7C` 문자열) 처리: 잘못된 unesc 순서면 깨짐
        val original = CandidateInfo(
            nodeId = "node",
            registeredAt = Instant.now(),
            metadata = mapOf(
                "literal-7C" to "%7C",
                "literal-2C" to "%2C",
                "literal-25" to "%25",
                "literal-3D" to "%3D",
            ),
        )

        val encoded = LettuceCandidateInfoCodec.encode(original)
        val decoded = LettuceCandidateInfoCodec.decode(encoded)

        decoded.metadata["literal-7C"] shouldBeEqualTo "%7C"
        decoded.metadata["literal-2C"] shouldBeEqualTo "%2C"
        decoded.metadata["literal-25"] shouldBeEqualTo "%25"
        decoded.metadata["literal-3D"] shouldBeEqualTo "%3D"
    }

    @Test
    fun `roundtrip - 구분자 문자(특수문자) metadata 값 안전 처리`() {
        val original = CandidateInfo(
            nodeId = "node",
            registeredAt = Instant.now(),
            metadata = mapOf(
                "delim-pipe" to "a|b|c",
                "delim-comma" to "a,b,c",
                "delim-equal" to "a=b=c",
                "delim-percent" to "100%",
                "all" to "a|b,c=d%e",
            ),
        )

        val encoded = LettuceCandidateInfoCodec.encode(original)
        val decoded = LettuceCandidateInfoCodec.decode(encoded)

        decoded.metadata shouldBeEqualTo original.metadata
    }

    @Test
    fun `roundtrip - nodeId 에 모든 구분자 포함`() {
        val original = CandidateInfo(
            nodeId = "weird|node,id=1%v",
            registeredAt = Instant.parse("2026-04-30T10:00:00Z"),
        )
        val encoded = LettuceCandidateInfoCodec.encode(original)
        val decoded = LettuceCandidateInfoCodec.decode(encoded)
        decoded.nodeId shouldBeEqualTo "weird|node,id=1%v"
    }

    @Test
    fun `decode - 잘못된 포맷이면 IllegalArgumentException`() {
        invoking { LettuceCandidateInfoCodec.decode("only|three|fields") } shouldThrow IllegalArgumentException::class
    }

    @Test
    fun `decode - 손상된 metadata 항목(=없음) IllegalArgumentException`() {
        // 7개 필드 형식은 맞지만 metadata 마지막 필드의 한 항목에 = 가 없음
        val now = Instant.now().toEpochMilli()
        val malformed = "node|$now|||0|0|key1=val1,brokenpair"
        invoking { LettuceCandidateInfoCodec.decode(malformed) } shouldThrow IllegalArgumentException::class
    }
}
