package io.bluetape4k.leader.testcontainers

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget
import java.time.Duration

class ReadinessBoundaryWaitStrategyTest {

    @Test
    fun `internal endpoint만 성공하면 host forwarding 실패로 분류한다`() {
        val diagnostic = diagnostic(
            internal = ReadinessProbeObservation.success("HTTP 200"),
            host = ReadinessProbeObservation.failure("Connection reset"),
            mapping = ReadinessProbeObservation.success("8474/tcp -> 127.0.0.1:55123"),
        )

        diagnostic.boundary shouldBeEqualTo ReadinessFailureBoundary.HOST_FORWARDING
    }

    @Test
    fun `internal endpoint가 실패하면 container service 실패로 분류한다`() {
        val diagnostic = diagnostic(
            internal = ReadinessProbeObservation.failure("Connection refused"),
            host = ReadinessProbeObservation.failure("Connection reset"),
            mapping = ReadinessProbeObservation.success("2379/tcp -> 127.0.0.1:55124"),
        )

        diagnostic.boundary shouldBeEqualTo ReadinessFailureBoundary.CONTAINER_SERVICE
    }

    @Test
    fun `port mapping이 없으면 mapping 실패로 우선 분류한다`() {
        val diagnostic = diagnostic(
            internal = ReadinessProbeObservation.failure("not probed"),
            host = ReadinessProbeObservation.failure("not probed"),
            mapping = ReadinessProbeObservation.failure("2379/tcp is not mapped"),
        )

        diagnostic.boundary shouldBeEqualTo ReadinessFailureBoundary.PORT_MAPPING
    }

    @Test
    fun `불완전한 probe 결과는 unknown으로 분류한다`() {
        val diagnostic = diagnostic(
            internal = ReadinessProbeObservation.unavailable("helper image unavailable"),
            host = ReadinessProbeObservation.failure("Connection reset"),
            mapping = ReadinessProbeObservation.success("2379/tcp -> 127.0.0.1:55124"),
        )

        diagnostic.boundary shouldBeEqualTo ReadinessFailureBoundary.UNKNOWN
    }

    @Test
    fun `delegate가 성공하면 diagnostic collector를 호출하지 않는다`() {
        val delegate = mockk<WaitStrategy>()
        val collector = mockk<ReadinessBoundaryDiagnosticCollector>()
        val target = mockk<WaitStrategyTarget>()
        every { delegate.waitUntilReady(target) } just Runs
        val strategy = ReadinessBoundaryWaitStrategy(delegate, TOXIPROXY, collector)

        strategy.waitUntilReady(target)

        verify(exactly = 0) { collector.collect(any(), any()) }
    }

    @Test
    fun `delegate 실패는 진단과 원래 cause를 함께 보존한다`() {
        val delegate = mockk<WaitStrategy>()
        val collector = mockk<ReadinessBoundaryDiagnosticCollector>()
        val target = mockk<WaitStrategyTarget>()
        val failure = IllegalStateException("host-wait-timeout")
        val diagnostic = diagnostic(
            internal = ReadinessProbeObservation.success("HTTP 200 {version:2.9.0}"),
            host = ReadinessProbeObservation.failure("Connection reset"),
            mapping = ReadinessProbeObservation.success("8474/tcp -> 127.0.0.1:55123"),
        )
        every { delegate.waitUntilReady(target) } throws failure
        every { collector.collect(target, TOXIPROXY) } returns diagnostic
        val strategy = ReadinessBoundaryWaitStrategy(delegate, TOXIPROXY, collector)

        val thrown = assertFailsWith<ContainerLaunchException> {
            strategy.waitUntilReady(target)
        }

        thrown.cause shouldBeSameInstanceAs failure
        thrown.message.orEmpty() shouldContain "boundary=HOST_FORWARDING"
        thrown.message.orEmpty() shouldContain "internal=SUCCESS"
        thrown.message.orEmpty() shouldContain "host=FAILURE"
        thrown.message.orEmpty() shouldContain "mapping=SUCCESS"
    }

    @Test
    fun `collector 실패도 원래 wait failure를 덮지 않는다`() {
        val delegate = mockk<WaitStrategy>()
        val collector = mockk<ReadinessBoundaryDiagnosticCollector>()
        val target = mockk<WaitStrategyTarget>()
        val failure = IllegalStateException("host-wait-timeout")
        every { delegate.waitUntilReady(target) } throws failure
        every { collector.collect(target, TOXIPROXY) } throws IllegalStateException("docker-inspect-unavailable")
        val strategy = ReadinessBoundaryWaitStrategy(delegate, TOXIPROXY, collector)

        val thrown = assertFailsWith<ContainerLaunchException> {
            strategy.waitUntilReady(target)
        }

        thrown.cause shouldBeSameInstanceAs failure
        thrown.message.orEmpty() shouldContain "boundary=UNKNOWN"
        thrown.message.orEmpty() shouldContain "internal=UNAVAILABLE"
        thrown.message.orEmpty() shouldContain "docker-inspect-unavailable"
    }

    @Test
    fun `probe detail은 한 줄 256자로 제한한다`() {
        ReadinessProbeObservation.success("first\nsecond").detail shouldBeEqualTo "first second"
        ReadinessProbeObservation.failure("x".repeat(300)).detail.length shouldBeEqualTo 256
    }

    @Test
    fun `startup timeout은 delegate에 그대로 전달한다`() {
        val delegate = mockk<WaitStrategy>()
        val collector = mockk<ReadinessBoundaryDiagnosticCollector>()
        val timeout = Duration.ofSeconds(60)
        every { delegate.withStartupTimeout(timeout) } returns delegate
        val strategy = ReadinessBoundaryWaitStrategy(delegate, TOXIPROXY, collector)

        strategy.withStartupTimeout(timeout) shouldBeSameInstanceAs strategy

        verify(exactly = 1) { delegate.withStartupTimeout(timeout) }
    }

    private fun diagnostic(
        internal: ReadinessProbeObservation,
        host: ReadinessProbeObservation,
        mapping: ReadinessProbeObservation,
    ): ReadinessBoundaryDiagnostic =
        ReadinessBoundaryDiagnostic(
            endpoint = TOXIPROXY,
            internal = internal,
            host = host,
            mapping = mapping,
            containerState = "running",
        )

    private companion object {
        val TOXIPROXY = ReadinessEndpoint("toxiproxy", containerPort = 8474, path = "/version")
    }
}
