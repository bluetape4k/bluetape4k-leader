package io.bluetape4k.leader.spring.observability

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.ConcurrentSkipListSet

/** 관찰된 lock 이름을 중복 없이 정렬해 Actuator와 readiness consumer에 제공합니다. */
class LeaderElectionStatusRegistry(
    initialLockNames: Iterable<String> = emptyList(),
): LeaderElectionListener {

    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /** blank가 아닌 lock 이름을 registry에 추가합니다. */
    fun register(lockName: String) {
        lockName.requireNotBlank("lockName")
        lockNames.add(lockName)
    }

    /** 현재까지 등록된 lock 이름을 정렬된 immutable snapshot으로 반환합니다. */
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

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("lockNames", lockNames)
            .toString()
    }
}
