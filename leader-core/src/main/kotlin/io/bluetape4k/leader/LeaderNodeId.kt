package io.bluetape4k.leader

import java.lang.management.ManagementFactory
import java.net.InetAddress

/**
 * 기본 leader node identity를 제공합니다. 값은 host name과 현재 JVM process ID로 구성됩니다.
 */
object LeaderNodeId {

    /**
     * 기본 node ID. host name 조회에 실패하면 runtime MXBean 이름을 사용하고, 그것도 실패하면 `localhost`를 사용합니다.
     */
    @JvmField
    val Default: String = "${hostname()}:${ProcessHandle.current().pid()}"

    private fun hostname(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .recoverCatching { ManagementFactory.getRuntimeMXBean().name.substringBefore('@') }
            .getOrDefault("localhost")
}
