package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.leader.history.LeaderHistoryStatus

/**
 * Deprecated alias for [LeaderHistoryStatus].
 *
 * Migrate to [io.bluetape4k.leader.history.LeaderHistoryStatus] directly.
 */
@Suppress("DEPRECATION")
@Deprecated(
    message = "Use io.bluetape4k.leader.history.LeaderHistoryStatus.",
    replaceWith = ReplaceWith(
        expression = "LeaderHistoryStatus",
        imports = ["io.bluetape4k.leader.history.LeaderHistoryStatus"],
    ),
    level = DeprecationLevel.WARNING,
)
typealias HistoryStatus = LeaderHistoryStatus
