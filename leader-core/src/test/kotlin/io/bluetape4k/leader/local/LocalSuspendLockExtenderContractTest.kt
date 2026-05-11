package io.bluetape4k.leader.local

import io.bluetape4k.leader.contract.AbstractSuspendLockExtenderContractTest
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendLockExtenderContractTest] 의 Local backend 구현.
 *
 * [LocalSuspendLeaderElector] 를 사용해 LockAssert / LockExtender × suspend contract 를 검증한다.
 * 외부 인프라 불필요 — 단일 JVM 인메모리 실행.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSuspendLockExtenderContractTest : AbstractSuspendLockExtenderContractTest() {

    override val elector: SuspendLeaderElector = LocalSuspendLeaderElector()
}
