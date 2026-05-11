package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.contract.AbstractSyncLockExtenderContractTest
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSyncLockExtenderContractTest] 의 Local backend 구현.
 *
 * [LocalLeaderElector] 를 사용해 LockAssert / LockExtender × sync contract 를 검증한다.
 * 외부 인프라 불필요 — 단일 JVM 인메모리 실행.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLockExtenderContractTest : AbstractSyncLockExtenderContractTest() {

    override val elector: LeaderElector = LocalLeaderElector()
}
