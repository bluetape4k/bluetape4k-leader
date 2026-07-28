package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireNotBlank
import org.apache.curator.utils.PathUtils
import java.io.Serializable

/**
 * `ZooKeeperElectionPath`는 ZooKeeper leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property lockName ZooKeeper backend 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property basePath ZooKeeper backend 계약에서 `basePath` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
         * `single` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
         */
        @JvmStatic
        fun single(
            lockName: String,
            basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
        ): ZooKeeperElectionPath =
            ZooKeeperElectionPath(lockName, basePath)

        /**
         * `group` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
