package io.bluetape4k.leader

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [LeaderElector]에 [LeaderElectionListener] dispatch를 추가하는 데코레이터입니다.
 *
 * 모든 backend 구현체에 적용할 수 있으며, [delegate]의 리더 선출/예외 전파 동작은 유지합니다.
 */
class ListeningLeaderElector(
    private val delegate: LeaderElector,
) : LeaderElector, LeaderElectionListenerRegistry {

    private val listeners = LeaderElectionListenerSupport()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            listeners.notifyElected(lockName)
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            listeners.notifyElected(lockName)
            try {
                action().whenComplete { _, _ -> listeners.notifyRevoked(lockName) }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(lockName)
            }
        }
    }
}

/**
 * [LeaderGroupElector]에 [LeaderElectionListener] dispatch를 추가하는 데코레이터입니다.
 */
class ListeningLeaderGroupElector(
    private val delegate: LeaderGroupElector,
) : LeaderGroupElector, LeaderElectionListenerRegistry {

    private val listeners = LeaderElectionListenerSupport()

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(lockName)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            listeners.notifyElected(lockName)
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            listeners.notifyElected(lockName)
            try {
                action().whenComplete { _, _ -> listeners.notifyRevoked(lockName) }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(lockName)
            }
        }
    }
}

/**
 * [LeaderElector]를 listener-aware 데코레이터로 감쌉니다.
 */
fun LeaderElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderElector =
    ListeningLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * [LeaderGroupElector]를 listener-aware 데코레이터로 감쌉니다.
 */
fun LeaderGroupElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderGroupElector =
    ListeningLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }
