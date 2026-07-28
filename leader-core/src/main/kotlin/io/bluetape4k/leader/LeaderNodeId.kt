package io.bluetape4k.leader

import java.lang.management.ManagementFactory
import java.net.InetAddress

/**
 * `LeaderNodeId` 선언은 leader election 계약에서 사용되는 object입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
object LeaderNodeId {

    /**
     * `Default` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    @JvmField
    val Default: String = "${hostname()}:${ProcessHandle.current().pid()}"

    private fun hostname(): String =
        runCatching { InetAddress.getLocalHost().hostName }
            .recoverCatching { ManagementFactory.getRuntimeMXBean().name.substringBefore('@') }
            .getOrDefault("localhost")
}
