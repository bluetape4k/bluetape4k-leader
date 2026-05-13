package io.bluetape4k.leader.identity

/**
 * Marks an API as internal to the leader module SPI — not intended for application code.
 *
 * ## Contract
 * Opt-in is required to call annotated declarations. This separates module-internal SPIs
 * from the public application API surface.
 */
@RequiresOptIn(
    message = "This API is internal to the leader module SPI and is not intended for application code.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class LeaderInternalApi
