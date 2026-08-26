package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.ktor.LeaderElectionPluginConfig
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class LeaderEventStreamPayloadTest {

    @Test
    fun `event stream defaults are safe and bounded`() {
        val config = LeaderEventStreamConfig()

        config.eventStreamRouteEnabled.shouldBeFalse()
        config.eventStreamRoutePath shouldBeEqualTo "/management/leaderElection/events"
        config.eventStreamSseEnabled.shouldBeTrue()
        config.eventStreamWebSocketEnabled.shouldBeFalse()
        config.eventStreamAllLocksEnabled.shouldBeFalse()
        config.eventStreamExposeLockName.shouldBeFalse()
        config.eventStreamExposeLeaderMetadata.shouldBeFalse()
        config.eventStreamReplayCapacity shouldBeEqualTo 32
        config.eventStreamMaxConnections shouldBeEqualTo 128
        config.eventStreamHeartbeat shouldBeEqualTo 15.seconds
    }

    @Test
    fun `plugin config defaults are copied into the immutable stream config`() {
        val config = LeaderElectionPluginConfig()

        config.toLeaderEventStreamConfig() shouldBeEqualTo LeaderEventStreamConfig()
    }

    @Test
    fun `plugin config의 event stream 정책은 모두 immutable config로 복사된다`() {
        val config = LeaderElectionPluginConfig().apply {
            eventStreamRouteEnabled = true
            eventStreamRoutePath = "/internal/events"
            eventStreamSseEnabled = false
            eventStreamWebSocketEnabled = true
            eventStreamAllLocksEnabled = true
            eventStreamExposeLockName = true
            eventStreamExposeLeaderMetadata = true
            eventStreamReplayCapacity = 64
            eventStreamMaxConnections = 8
            eventStreamHeartbeat = 2.seconds
        }

        config.toLeaderEventStreamConfig() shouldBeEqualTo LeaderEventStreamConfig(
            eventStreamRouteEnabled = true,
            eventStreamRoutePath = "/internal/events",
            eventStreamSseEnabled = false,
            eventStreamWebSocketEnabled = true,
            eventStreamAllLocksEnabled = true,
            eventStreamExposeLockName = true,
            eventStreamExposeLeaderMetadata = true,
            eventStreamReplayCapacity = 64,
            eventStreamMaxConnections = 8,
            eventStreamHeartbeat = 2.seconds,
        )
    }

    @Test
    fun `capacity는 0부터 1024까지만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamReplayCapacity = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamReplayCapacity = 1025)
        }
        LeaderEventStreamConfig(eventStreamReplayCapacity = 0)
        LeaderEventStreamConfig(eventStreamReplayCapacity = 1024)
    }

    @Test
    fun `connection limit은 1부터 1024까지만 허용한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamMaxConnections = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamMaxConnections = 1025)
        }
    }

    @Test
    fun `route와 path 및 heartbeat 조합을 fail closed로 검증한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamRouteEnabled = true, eventStreamSseEnabled = false)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamRoutePath = "events")
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamRoutePath = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamHeartbeat = kotlin.time.Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamHeartbeat = kotlin.time.Duration.INFINITE)
        }
    }

    @Test
    fun `all lock stream은 lockName 노출 opt in 없이는 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderEventStreamConfig(eventStreamAllLocksEnabled = true)
        }
        LeaderEventStreamConfig(eventStreamAllLocksEnabled = true, eventStreamExposeLockName = true)
    }

    @Test
    fun `기본 event payload는 type과 sequence만 노출한다`() {
        val event = LeaderElectionEvent.Elected(
            lockName = "batch-job",
            leaderId = "node-a",
            leaseExpiry = Instant.parse("2026-08-26T01:02:03Z"),
            leader = LeaderLease("node-a"),
        )

        LeaderEventStreamPayload.event(event, sequence = 7) shouldBeEqualTo
            "{\"type\":\"Elected\",\"sequence\":7}"
    }

    @Test
    fun `lockName과 leader metadata는 각각 명시적으로 opt in한 경우에만 노출한다`() {
        val event = LeaderElectionEvent.Elected(
            lockName = "batch-job",
            leaderId = "node-a",
            leaseExpiry = Instant.parse("2026-08-26T01:02:03Z"),
            leader = LeaderLease("node-a"),
        )

        val lockNameJson = LeaderEventStreamPayload.event(
            event = event,
            sequence = 7,
            exposeLockName = true,
        )
        lockNameJson shouldBeEqualTo
            "{\"type\":\"Elected\",\"sequence\":7,\"lockName\":\"batch-job\"}"

        val metadataJson = LeaderEventStreamPayload.event(
            event = event,
            sequence = 7,
            exposeLockName = true,
            exposeLeaderMetadata = true,
        )
        metadataJson shouldBeEqualTo
            "{\"type\":\"Elected\",\"sequence\":7,\"lockName\":\"batch-job\",\"leaderId\":\"node-a\",\"leaseExpiry\":\"2026-08-26T01:02:03Z\"}"
        metadataJson.contains("LeaderLease").shouldBeFalse()
    }

    @Test
    fun `Revoked와 Skipped도 안전한 type payload로 변환한다`() {
        LeaderEventStreamPayload.event(LeaderElectionEvent.Revoked("job"), sequence = 8) shouldBeEqualTo
            "{\"type\":\"Revoked\",\"sequence\":8}"
        LeaderEventStreamPayload.event(LeaderElectionEvent.Skipped("job"), sequence = 9) shouldBeEqualTo
            "{\"type\":\"Skipped\",\"sequence\":9}"
    }

    @Test
    fun `문자열 metadata는 JSON escaping을 사용한다`() {
        val event = LeaderElectionEvent.Elected(
            lockName = "job\"\\\n",
            leaderId = "node\"\\\n",
            leaseExpiry = Instant.parse("2026-08-26T01:02:03Z"),
        )

        val json = LeaderEventStreamPayload.event(
            event = event,
            sequence = 1,
            exposeLockName = true,
            exposeLeaderMetadata = true,
        )

        json shouldContain "\\\""
        json shouldContain "\\\\"
        json shouldContain "\\n"
        json.contains("job\"\\\n").shouldBeFalse()
    }

    @Test
    fun `heartbeat과 replay gap은 작은 control payload를 만든다`() {
        LeaderEventStreamPayload.heartbeat() shouldBeEqualTo "{\"event\":\"heartbeat\"}"
        LeaderEventStreamPayload.replayGap(from = 3, to = 4) shouldBeEqualTo
            "{\"event\":\"replay_gap\",\"from\":3,\"to\":4}"
    }

    @Test
    fun `null metadata는 명시적 opt in에서만 null로 표현한다`() {
        val event = LeaderElectionEvent.Elected(lockName = "job")

        LeaderEventStreamPayload.event(
            event = event,
            sequence = 1,
            exposeLockName = true,
            exposeLeaderMetadata = true,
        ) shouldContain "\"leaderId\":null"
    }
}
