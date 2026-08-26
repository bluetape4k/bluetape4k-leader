package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionPhase
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.LeaderManagementQuarantineReason
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class MicrometerLeaderManagementActionObserverTest {

    @Test
    fun `quarantine counter and active gauge use fixed low cardinality tags`() {
        val meters = SimpleMeterRegistry()
        val observer = MicrometerLeaderManagementActionObserver(meters)
        val observation = observation(
            reason = LeaderManagementQuarantineReason.NON_INTERRUPTIBLE,
            phase = LeaderManagementActionPhase.QUARANTINED,
            surface = LeaderManagementActionSurface.KTOR,
        )

        observer.onResult(observation)

        meters.counter(
            MicrometerNames.MANAGEMENT_QUARANTINE,
            MicrometerNames.MANAGEMENT_TAG_REASON,
            "non-interruptible",
            MicrometerNames.MANAGEMENT_TAG_PHASE,
            "quarantined",
            MicrometerNames.MANAGEMENT_TAG_SURFACE,
            "ktor",
        ).count() shouldBeEqualTo 1.0
        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE_ACTIVE)
            .tags(
                MicrometerNames.MANAGEMENT_TAG_REASON, "non-interruptible",
                MicrometerNames.MANAGEMENT_TAG_PHASE, "quarantined",
                MicrometerNames.MANAGEMENT_TAG_SURFACE, "ktor",
            )
            .gauge()
            ?.value() shouldBeEqualTo 1.0
        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE)
            .meters()
            .single()
            .id
            .tags
            .none { tag -> tag.key == "lock" || tag.key == "exception" }
            .shouldBeTrue()
    }

    @Test
    fun `recovery callback decreases active gauge but preserves counter`() {
        val meters = SimpleMeterRegistry()
        val observer = MicrometerLeaderManagementActionObserver(meters)
        val observation = observation(
            reason = LeaderManagementQuarantineReason.CLEANUP_TIMEOUT,
            phase = LeaderManagementActionPhase.QUARANTINED,
            surface = LeaderManagementActionSurface.CORE,
        )

        observer.onResult(observation)
        observer.onQuarantineRecovered(observation)
        observer.onQuarantineRecovered(observation)

        meters.counter(
            MicrometerNames.MANAGEMENT_QUARANTINE,
            MicrometerNames.MANAGEMENT_TAG_REASON,
            "cleanup-timeout",
            MicrometerNames.MANAGEMENT_TAG_PHASE,
            "quarantined",
            MicrometerNames.MANAGEMENT_TAG_SURFACE,
            "core",
        ).count() shouldBeEqualTo 1.0
        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE_ACTIVE)
            .tags(
                MicrometerNames.MANAGEMENT_TAG_REASON, "cleanup-timeout",
                MicrometerNames.MANAGEMENT_TAG_PHASE, "quarantined",
                MicrometerNames.MANAGEMENT_TAG_SURFACE, "core",
            )
            .gauge()
            ?.value() shouldBeEqualTo 0.0
    }

    @Test
    fun `non quarantined terminal result is ignored`() {
        val meters = SimpleMeterRegistry()
        val observer = MicrometerLeaderManagementActionObserver(meters)
        observer.onResult(
            LeaderManagementActionObservation(
                surface = LeaderManagementActionSurface.SPRING,
                outcome = LeaderManagementActionOutcome.RELEASED,
                phase = LeaderManagementActionPhase.TERMINALIZED,
                mutationAttempted = true,
                quarantined = false,
            ),
        )

        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE).counter().let { counter ->
            (counter == null).shouldBeTrue()
        }
        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE_ACTIVE).gauge().let { gauge ->
            (gauge == null).shouldBeTrue()
        }
    }

    @Test
    fun `every quarantine reason has a stable metric value`() {
        val meters = SimpleMeterRegistry()
        val observer = MicrometerLeaderManagementActionObserver(meters)
        LeaderManagementQuarantineReason.values().forEach { reason ->
            observer.onResult(
                observation(
                    reason = reason,
                    phase = LeaderManagementActionPhase.QUARANTINED,
                    surface = LeaderManagementActionSurface.CORE,
                ),
            )
        }

        meters.find(MicrometerNames.MANAGEMENT_QUARANTINE).counters().size shouldBeEqualTo 4
        LeaderManagementQuarantineReason.values().forEach { reason ->
            meters.find(MicrometerNames.MANAGEMENT_QUARANTINE)
                .tag(MicrometerNames.MANAGEMENT_TAG_REASON, reason.name.lowercase().replace('_', '-'))
                .counter()
                ?.count() shouldBeEqualTo 1.0
        }
    }

    private fun observation(
        reason: LeaderManagementQuarantineReason,
        phase: LeaderManagementActionPhase,
        surface: LeaderManagementActionSurface,
    ) = LeaderManagementActionObservation(
        surface = surface,
        outcome = LeaderManagementActionOutcome.ACTION_TIMED_OUT,
        phase = phase,
        mutationAttempted = true,
        quarantined = true,
        quarantineReason = reason,
    )
}
