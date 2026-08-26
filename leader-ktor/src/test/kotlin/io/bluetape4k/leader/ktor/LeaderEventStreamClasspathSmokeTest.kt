package io.bluetape4k.leader.ktor

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import org.junit.jupiter.api.Test

class LeaderEventStreamClasspathSmokeTest {

    @Test
    fun `always loaded bootstrap is isolated from optional stream artifacts`() {
        val mainLocation = Class.forName(
            "io.bluetape4k.leader.ktor.LeaderEventStreamBootstrapKt",
        ).protectionDomain.codeSource.location
        val filteredRuntimeUrls = buildList {
            add(mainLocation)
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter(String::isNotBlank)
                .map(::File)
                .map(File::toURI)
                .map(java.net.URI::toURL)
                .filterNot { it.file.contains("ktor-server-sse") }
                .filterNot { it.file.contains("ktor-server-websockets") }
                .filterNot { it.file.contains("ktor-server-status-pages") }
                .filterNot { it == mainLocation }
                .forEach(::add)
        }

        val parent = MissingEventTransportClassLoader(javaClass.classLoader)
        IsolatedLeaderClassLoader(filteredRuntimeUrls.toTypedArray(), parent).use { loader ->
            Class.forName("io.bluetape4k.leader.ktor.LeaderEventStreamBootstrapKt", true, loader)
            Class.forName("io.bluetape4k.leader.ktor.LeaderElectionPluginKt", true, loader)
            Class.forName("io.bluetape4k.leader.ktor.LeaderElectionPluginConfig", true, loader)

        }

        println(
            "optional event stream smoke: filteredRuntimeUrls=" +
                filteredRuntimeUrls.joinToString(",") { it.toString() } +
                "; bootstrap=plugin=config loaded; optional adapters not loaded",
        )
    }

    private class MissingEventTransportClassLoader(
        private val delegate: ClassLoader,
    ) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (
                name.startsWith("io.ktor.server.sse") ||
                name.startsWith("io.ktor.server.websocket") ||
                name.startsWith("io.ktor.websocket") ||
                name.startsWith("io.ktor.server.plugins.statuspages")
            ) {
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
