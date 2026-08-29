package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.leader.LeaderLeaseExtensionObservationScope
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.atomic.AtomicReference

internal const val LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME =
    "leaseExtensionObservationScopeOwner"

internal class LeaseExtensionObservationScopeOwner(
    val registry: ObservationRegistry,
) {
    private val scope = AtomicReference<LeaderLeaseExtensionObservationScope?>()

    fun activate(value: LeaderLeaseExtensionObservationScope) {
        check(scope.compareAndSet(null, value)) { "Lease extension observation scope is already active" }
    }

    fun current(): LeaderLeaseExtensionObservationScope? = scope.get()?.takeIf { it.isActive() }

    fun clear(expected: LeaderLeaseExtensionObservationScope) {
        scope.compareAndSet(expected, null)
    }
}
