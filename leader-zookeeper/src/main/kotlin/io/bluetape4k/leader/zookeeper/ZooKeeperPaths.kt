package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireNotBlank
import org.apache.curator.utils.PathUtils
import java.io.Serializable

/**
 * Typed ZooKeeper election path used by convenience overloads to avoid positional string mistakes.
 *
 * ## Behavior / Contract
 * - [lockName] is validated with the common leader lock-name contract and must be one safe znode segment.
 * - [basePath] is normalized to a valid ZooKeeper path before election znodes are created below it.
 *
 * ```kotlin
 * val path = ZooKeeperElectionPath.single("daily-job")
 * curator.runIfLeader(path) { runJob() }
 * ```
 */
data class ZooKeeperElectionPath(
    val lockName: String,
    val basePath: String,
): Serializable {

    init {
        ZooKeeperPaths.validateBasePath(basePath)
        validateLockName(lockName)
    }

    companion object {
        /**
         * Creates a path descriptor for single-leader elections.
         */
        @JvmStatic
        fun single(
            lockName: String,
            basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
        ): ZooKeeperElectionPath =
            ZooKeeperElectionPath(lockName, basePath)

        /**
         * Creates a path descriptor for multi-leader group elections.
         */
        @JvmStatic
        fun group(
            lockName: String,
            basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
        ): ZooKeeperElectionPath =
            ZooKeeperElectionPath(lockName, basePath)

        private const val serialVersionUID = 1L
    }
}

internal object ZooKeeperPaths {

    fun electionPath(basePath: String, lockName: String): String {
        val normalizedBase = validateBasePath(basePath)
        validateLockName(lockName)

        return if (normalizedBase == "/") {
            "/$lockName"
        } else {
            "$normalizedBase/$lockName"
        }
    }

    fun validateBasePath(basePath: String): String {
        basePath.requireNotBlank("basePath")
        val normalizedBase = basePath.trimEnd('/').ifBlank { "/" }
        PathUtils.validatePath(normalizedBase)
        return normalizedBase
    }
}
