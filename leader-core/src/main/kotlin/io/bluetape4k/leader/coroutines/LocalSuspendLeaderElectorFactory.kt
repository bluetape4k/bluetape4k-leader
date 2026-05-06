package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions

/**
 * [LocalSuspendLeaderElector] 팩토리 — `kotlinx.coroutines.sync.Mutex` 기반 단일 JVM suspend 리더 선출 인스턴스 생성.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LocalSuspendLeaderElectorFactory()
 * val elector = factory.create(LeaderElectionOptions.Default)
 * val result = elector.runIfLeader("job-lock") { "done" }
 * ```
 */
class LocalSuspendLeaderElectorFactory : SuspendLeaderElectorFactory {

    override fun create(options: LeaderElectionOptions): SuspendLeaderElector =
        LocalSuspendLeaderElector(options)
}
