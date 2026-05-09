package io.bluetape4k.leader

import java.lang.management.ManagementFactory
import java.net.InetAddress

/**
 * 기본 리더 노드 식별자를 제공합니다.
 *
 * ## 계약
 * [Default]는 JVM 프로세스 단위로 한 번 계산되는 stable id입니다. 옵션 기본값에서 매번
 * 랜덤 id를 생성하면 동일 설정 간 equality/cache key가 흔들리므로, 기본값은 프로세스 수명 동안
 * 일정해야 합니다.
 */
object LeaderNodeId {

    /**
     * 현재 JVM 프로세스의 기본 노드 식별자입니다.
     *
     * 형식은 `host:pid`이며, host 조회가 실패하면 `localhost`를 사용합니다.
     */
    @JvmField
    val Default: String = "${hostname()}:${ProcessHandle.current().pid()}"

    private fun hostname(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .recoverCatching { ManagementFactory.getRuntimeMXBean().name.substringBefore('@') }
            .getOrDefault("localhost")
}
