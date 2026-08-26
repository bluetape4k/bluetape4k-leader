package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EngineConnectorConfig
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LeaderManagementShutdownTest {

    @Test
    fun `registry drain completes before engine stop`() = runSuspendIO {
        val registry = SuspendLeaderManagementActionRegistry()
        val engine = RecordingEngine()
        val drained = engine.stopLeaderManagementGracefully(registry)

        drained shouldBeEqualTo true
        engine.stopCalls.get() shouldBeEqualTo 1
        registry.close()
    }

    @Test
    fun `shutdown duration rejects non-positive values before engine stop`() = runSuspendIO {
        val registry = SuspendLeaderManagementActionRegistry()
        val engine = RecordingEngine()

        assertFailsWith<IllegalArgumentException> {
            engine.stopLeaderManagementGracefully(registry, timeoutMillis = 0L)
        }

        engine.stopCalls.get() shouldBeEqualTo 0
        registry.close()
    }

    private class RecordingEngine : ApplicationEngine {
        val stopCalls = AtomicInteger()

        override val environment: ApplicationEnvironment
            get() = error("not used")

        override suspend fun resolvedConnectors(): List<EngineConnectorConfig> = emptyList()

        override fun start(wait: Boolean): ApplicationEngine = this

        override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
            stopCalls.incrementAndGet()
        }
    }
}
