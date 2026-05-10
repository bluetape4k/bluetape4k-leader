package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

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
}
