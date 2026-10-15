package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireNotBlank
import org.apache.curator.utils.PathUtils
import java.io.Serializable

/**
 * `ZooKeeperElectionPath`는 논리적 lockName과 정규화된 root path를 묶어 single/group election 경로를 만드는 값 객체입니다.
 *
 * @property lockName ZooKeeper path 마지막 segment가 될 논리적 lock 이름입니다. blank·잘못된 path 문자는 검증에서 거부됩니다.
 * @property basePath lock 이름을 붙일 root path입니다. trailing `/`를 제거하고 `/` 자체는 유지합니다.
 */
data class ZooKeeperElectionPath(
    val lockName: String,
    val basePath: String,
): Serializable {

    init {
        ZooKeeperPaths.validateBasePath(basePath)
        lockName.validateLockName()
    }

    companion object {
        /** single 선출 기본 root(`/leader-election`)와 lock 이름을 담은 경로 값을 만듭니다. */
        @JvmStatic
        fun single(
            lockName: String,
            basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
        ): ZooKeeperElectionPath =
            ZooKeeperElectionPath(lockName, basePath)

        /** 그룹 선출 기본 root(`/leader-group-election`)와 lock 이름을 담은 경로 값을 만듭니다. */
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
        lockName.validateLockName()

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
