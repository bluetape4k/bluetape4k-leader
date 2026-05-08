package io.bluetape4k.leader.zookeeper

import io.bluetape4k.support.requireNotBlank

internal object ZooKeeperPaths {

    fun electionPath(basePath: String, lockName: String): String {
        basePath.requireNotBlank("basePath")
        lockName.requireNotBlank("lockName")

        val normalizedBase = basePath.trimEnd('/').ifBlank { "/" }
        return if (normalizedBase == "/") {
            "/$lockName"
        } else {
            "$normalizedBase/$lockName"
        }
    }
}
