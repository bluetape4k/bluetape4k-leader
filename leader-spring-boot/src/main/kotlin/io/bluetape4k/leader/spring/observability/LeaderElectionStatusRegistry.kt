package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Registry of lock names known to the Spring Boot leader observability endpoint.
 *
 * ## Behavior / Contract
 * - Static lock names can be seeded from configuration.
 * - Listener callbacks record lock names observed through listener-aware electors.
 * - The registry is best-effort and JVM-local; it does not discover arbitrary backend locks.
 */
class LeaderElectionStatusRegistry(
    initialLockNames: Iterable<String> = emptyList(),
) : LeaderElectionListener {

    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /**
     * Registers [lockName] as known to observability endpoints.
     */
    fun register(lockName: String) {
        lockName.requireNotBlank("lockName")
        lockNames.add(lockName)
    }

    /**
     * Returns a stable sorted snapshot of currently known lock names.
     */
    fun snapshot(): List<String> =
        lockNames.toList()

    override fun onElected(lockName: String) {
        register(lockName)
    }

    override fun onRevoked(lockName: String) {
        register(lockName)
    }

    override fun onSkipped(lockName: String) {
        register(lockName)
    }
}
