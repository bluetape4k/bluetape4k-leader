package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.ApplicationResourceRegistry
import io.bluetape4k.ktor.core.ApplicationResourceRegistryState
import io.bluetape4k.ktor.core.installApplicationResourceLifecycle
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

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

    @Test
    fun `후속 설정 검증 실패는 이미 등록한 event hub와 registry를 한 번 닫고 원래 예외를 보존한다`() = runSuspendIO {
        lateinit var leaderRegistry: LeaderElectionResourceRegistry
        lateinit var hub: io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub

        val failure = assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    try {
                        install(LeaderElectionPlugin) {
                            leaderElection = LocalSuspendLeaderElector()
                            eventStreamRouteEnabled = true
                            managementActionRouteEnabled = true
                        }
                    } catch (cause: Throwable) {
                        leaderRegistry = requireNotNull(leaderElectionResourceRegistryOrNull())
                        hub = requireNotNull(pluginOrNull(LeaderEventStreamRuntimePlugin)?.hub)
                        runBlocking {
                            withTimeout(1.seconds) {
                                val report = leaderRegistry.awaitClosed()
                                report.attempted shouldBeEqualTo 1
                                report.closed shouldBeEqualTo 1
                                hub.awaitClosed()
                            }
                        }
                        throw cause
                    }
                }
                startApplication()
            }
        }

        failure.message shouldBeEqualTo
            "managementActionRouteEnabled=true 이면 application-owned managementActionRegistry를 설정해야 합니다."
        leaderRegistry.awaitClosed().attempted shouldBeEqualTo 1
    }
}
