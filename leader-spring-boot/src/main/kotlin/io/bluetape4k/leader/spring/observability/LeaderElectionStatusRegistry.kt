package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.ConcurrentSkipListSet

/**
 * `LeaderElectionStatusRegistry`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
class LeaderElectionStatusRegistry(
    initialLockNames: Iterable<String> = emptyList(),
) : LeaderElectionListener {

    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /**
     * `register` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun register(lockName: String) {
        lockName.requireNotBlank("lockName")
        lockNames.add(lockName)
    }

    /**
     * `snapshot` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
