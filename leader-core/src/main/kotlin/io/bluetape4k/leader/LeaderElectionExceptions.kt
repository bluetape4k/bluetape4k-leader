package io.bluetape4k.leader

/**
 * Base exception for leader election failures.
 *
 * ## Behavior / Contract
 * Thrown when a leader election operation fails due to backend errors,
 * lock acquisition timeouts, or other irrecoverable conditions.
 *
 * ```kotlin
 * throw LeaderElectionException("Failed to acquire lock: $lockName")
 * ```
 */
open class LeaderElectionException: RuntimeException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}

/**
 * Exception for leader group election failures.
 *
 * ## Behavior / Contract
 * Thrown when a leader group election operation fails — e.g., all slots in a group lock
 * are occupied or a group member cannot acquire a permit.
 *
 * ```kotlin
 * throw LeaderGroupElectionException("No available slot in group: $groupName")
 * ```
 */
open class LeaderGroupElectionException: LeaderElectionException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}
