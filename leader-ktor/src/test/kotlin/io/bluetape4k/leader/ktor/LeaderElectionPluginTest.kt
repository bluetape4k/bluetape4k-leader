package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.ApplicationResourceRegistryState
import io.bluetape4k.ktor.core.installApplicationResourceLifecycle
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LeaderElectionPluginTest: AbstractLeaderKtorTest() {

    companion object: KLoggingChannel()

    @Test
    fun `leaderElection 미설정 시 install 시점에 IllegalArgumentException 이 발생한다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(LeaderElectionPlugin) {
                        // leaderElection 의도적으로 미설정
                    }
                }
                startApplication()
            }
        }
    }

    @Test
    fun `정상 설치 시 leaderElectionPluginConfig 로 설정에 접근할 수 있다`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = elector
                }

                val cfg = leaderElectionPluginConfig()
                cfg.leaderElection.shouldNotBeNull()
                cfg.leaderElection shouldBeEqualTo elector
            }
            startApplication()
        }
    }

    @Test
    fun `플러그인 미설치 상태에서 leaderElectionPluginConfig 호출 시 IllegalStateException`() = runSuspendIO {
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    leaderElectionPluginConfig()
                }
                startApplication()
            }
        }
    }

    @Test
    fun `ApplicationStopped에서 plugin-owned resource만 한 번 닫힌다`() = runSuspendIO {
        val closeCount = AtomicInteger(0)
        lateinit var resource: AutoCloseable
        lateinit var registry: LeaderElectionResourceRegistry

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = FakeSuspendLeaderElector()
                }
                resource = AutoCloseable { closeCount.incrementAndGet() }
                registry = leaderElectionResourceRegistryOrNull()!!
                registry.register(resource)
            }
            startApplication()
        }
        registry.awaitClosed()

        closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `ApplicationStopped는 공통 lifecycle을 통해 Leader registry를 한 번 닫는다`() = runSuspendIO {
        val closeCount = AtomicInteger(0)
        lateinit var applicationRegistry: ApplicationResourceRegistry
        lateinit var leaderRegistry: LeaderElectionResourceRegistry

        testApplication {
            application {
                applicationRegistry = installApplicationResourceLifecycle()
                install(LeaderElectionPlugin) {
                    leaderElection = FakeSuspendLeaderElector()
                }
                leaderRegistry = leaderElectionResourceRegistryOrNull()!!
                leaderRegistry.register(AutoCloseable { closeCount.incrementAndGet() })
            }
            startApplication()
        }

        leaderRegistry.awaitClosed()
        applicationRegistry.closeReport.state shouldBeEqualTo ApplicationResourceRegistryState.CLOSED
        applicationRegistry.closeReport.attempted shouldBeEqualTo 1
        applicationRegistry.closeReport.closed shouldBeEqualTo 1
        closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `공통 lifecycle 반복 close 뒤 Leader late registration은 즉시 닫힌다`() = runSuspendIO {
        val regularCloseCount = AtomicInteger(0)
        val lateCloseCount = AtomicInteger(0)
        lateinit var applicationRegistry: ApplicationResourceRegistry
        lateinit var leaderRegistry: LeaderElectionResourceRegistry

        testApplication {
            application {
                applicationRegistry = installApplicationResourceLifecycle()
                install(LeaderElectionPlugin) {
                    leaderElection = FakeSuspendLeaderElector()
                }
                leaderRegistry = leaderElectionResourceRegistryOrNull()!!
                leaderRegistry.register(AutoCloseable { regularCloseCount.incrementAndGet() })
            }
            startApplication()

            applicationRegistry.close()
            applicationRegistry.close()
            leaderRegistry.register(AutoCloseable { lateCloseCount.incrementAndGet() })
        }

        leaderRegistry.awaitClosed()
        applicationRegistry.closeReport.attempted shouldBeEqualTo 1
        regularCloseCount.get() shouldBeEqualTo 1
        lateCloseCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `caller-owned AutoCloseable elector는 ApplicationStopped에서 닫지 않는다`() = runSuspendIO {
        val elector = AutoCloseableFakeSuspendLeaderElector()
        testApplication {
            application { install(LeaderElectionPlugin) { leaderElection = elector } }
            startApplication()
        }

        elector.closeCount.get() shouldBeEqualTo 0
    }
}
