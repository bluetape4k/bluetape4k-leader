package io.bluetape4k.leader

/**
 * cleanup과 residual slot의 선점을 나타내는 내부 경계입니다.
 *
 * 구현체는 terminal proof 전까지 reservation을 pool에 반환하지 않아야 합니다.
 */
interface LeaseCleanupReservation {
    /** cleanup reservation이 이미 terminalized 되었는지 여부입니다. */
    val isTerminal: Boolean

    /** reservation을 한 번만 terminalize합니다. */
    fun terminalize()
}
