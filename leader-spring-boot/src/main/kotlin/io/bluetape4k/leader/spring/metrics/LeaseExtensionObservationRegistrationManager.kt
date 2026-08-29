package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.LeaderLeaseExtensionObservationScope
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderLeaseExtensionObserver
import io.micrometer.observation.ObservationRegistry
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Spring context별 lease-extension registration handle을 registry identity 기준으로 공유합니다.
 *
 * 하나의 [ObservationRegistry]에는 하나의 core observer만 등록하고, 마지막 context가 닫힐
 * 때만 해당 observer registration을 제거합니다. registry identity와 옵션 비교는 하나의
 * reentrant lock 안에서 선형화됩니다.
 */
internal object LeaseExtensionObservationRegistrationManager {

    internal data class ManagedRegistration(
        val scope: LeaderLeaseExtensionObservationScope,
        private val closeHandle: AutoCloseable,
    ) : AutoCloseable by closeHandle

    private val lock = ReentrantLock()
    private val entries = IdentityHashMap<ObservationRegistry, Entry>()

    fun acquire(
        registry: ObservationRegistry,
        options: LeaderObservationOptions,
    ): ManagedRegistration = lock.withLock {
        val existing = entries[registry]
        if (existing != null) {
            check(existing.options == options) {
                "ObservationRegistry is already registered with different LeaderObservationOptions"
            }
            existing.referenceCount++
            return@withLock ManagedRegistration(existing.scope, RegistrationHandle(registry, existing))
        }

        val observer = MicrometerObservationLeaderLeaseExtensionObserver(registry, options)
        val scope = LeaderLeaseExtensionObservers.addScopedObserver(observer)
        val entry = Entry(options, scope)
        entries[registry] = entry
        ManagedRegistration(scope, RegistrationHandle(registry, entry))
    }

    internal fun registryCount(): Int = lock.withLock { entries.size }

    internal fun referenceCount(registry: ObservationRegistry): Int = lock.withLock {
        entries[registry]?.referenceCount ?: 0
    }

    private fun release(
        registry: ObservationRegistry,
        entry: Entry,
    ) {
        lock.withLock {
            if (entry.referenceCount == 0) return

            entry.referenceCount--
            if (entry.referenceCount == 0) {
                entries.remove(registry)
                entry.scope.close()
            }
        }
    }

    private class Entry(
        val options: LeaderObservationOptions,
        val scope: LeaderLeaseExtensionObservationScope,
        var referenceCount: Int = 1,
    )

    private class RegistrationHandle(
        private val registry: ObservationRegistry,
        private val entry: Entry,
    ) : AutoCloseable {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(registry, entry)
            }
        }
    }
}
