package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * caller executor에는 action 시작만 제출하고, 제출 대기와 실제 action future 양쪽으로 취소를 전달합니다.
 * acquire/release 소유 스레드는 반환 future를 기다리므로 caller executor의 스레드를 점유하지 않습니다.
 */
internal fun <T> submitZooKeeperAction(
    lockName: String,
    executor: Executor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T> {
    val actionRelay = LeaderFutureBridge.cancellationRelay()
    val handle = requireNotNull(AopScopeAccess.peekSyncMatching(lockName))
    val submitted = CompletableFuture.supplyAsync({
        actionRelay.invoke {
            AopScopeAccess.withPushedSync(handle) {
                if (handle is LeaderLockHandle.Real && handle.identity.kind == LockIdentity.AnnotationKind.GROUP) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
                } else {
                    action()
                }
            }
        }
    }, executor)
    return LeaderFutureBridge.propagateCancellation(
        LeaderFutureBridge.flatMap(submitted) { future, failure ->
            if (failure != null) CompletableFuture.failedFuture(failure) else requireNotNull(future)
        },
        actionRelay,
    )
}
