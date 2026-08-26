package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.ktor.statuspages.leaderElectionErrors
import io.bluetape4k.leader.ktor.statuspages.respondLeaderElectionError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader

class LeaderElectionStatusPagesAdapterTest {

    @Test
    fun `StatusPages 없이도 stable JSON fallback을 반환한다`() = runSuspendIO {
        testApplication {
            application {
                routing {
                    get("/error") {
                        call.respondLeaderElectionError(
                            toErrorContext(LeaderElectionErrorCode.NOT_LEADER),
                        )
                    }
                }
            }

            val response = client.get("/error")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldBeEqualTo
                """{"code":"NOT_LEADER","message":"leader state does not allow this request","status":503}"""
        }
    }

    @Test
    fun `StatusPages adapter는 예외를 같은 payload로 변환한다`() = runSuspendIO {
        testApplication {
            application {
                install(StatusPages) { leaderElectionErrors() }
                routing {
                    get("/error") {
                        throw LeaderElectionHttpException(
                            toErrorContext(LeaderElectionErrorCode.BACKEND_UNAVAILABLE),
                        )
                    }
                }
            }

            val response = client.get("/error")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""
        }
    }

    @Test
    fun `StatusPages artifact가 없어도 plugin과 config는 로드되고 adapter는 linkage error를 낸다`() {
        val mainLocation = Class.forName(
            "io.bluetape4k.leader.ktor.LeaderElectionPluginKt",
        ).protectionDomain.codeSource.location
        val filteredRuntimeUrls = buildList {
            add(mainLocation)
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter(String::isNotBlank)
                .map(::File)
                .map(File::toURI)
                .map(java.net.URI::toURL)
                .filterNot { it.file.contains("ktor-server-status-pages") }
                .filterNot { it == mainLocation }
                .forEach(::add)
        }

        val parent = MissingStatusPagesClassLoader(javaClass.classLoader)
        IsolatedLeaderClassLoader(filteredRuntimeUrls.toTypedArray(), parent).use { loader ->
            Class.forName("io.bluetape4k.leader.ktor.LeaderElectionPluginKt", true, loader)
            Class.forName("io.bluetape4k.leader.ktor.LeaderElectionPluginConfig", true, loader)

            val adapterClass = Class.forName(
                "io.bluetape4k.leader.ktor.statuspages.LeaderElectionStatusPagesAdapterKt",
                true,
                loader,
            )
            assertFailsWith<LinkageError> { adapterClass.declaredMethods }
        }

        println(
            "optional StatusPages smoke: filteredRuntimeUrls=" +
                filteredRuntimeUrls.joinToString(",") { it.toString() } +
                "; plugin=config loaded; adapter=blocked",
        )
    }

    private class MissingStatusPagesClassLoader(
        private val delegate: ClassLoader,
    ) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("io.ktor.server.plugins.statuspages.")) {
                throw ClassNotFoundException(name)
            }
            return delegate.loadClass(name)
        }
    }

    private class IsolatedLeaderClassLoader(
        urls: Array<URL>,
        parent: ClassLoader,
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("io.bluetape4k.leader.ktor.")) {
                synchronized(getClassLoadingLock(name)) {
                    findLoadedClass(name)?.let { return it }
                    try {
                        val isolated = findClass(name)
                        if (resolve) resolveClass(isolated)
                        return isolated
                    } catch (_: ClassNotFoundException) {
                        // Fall through to the parent for classes not present in the module output.
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }
}
