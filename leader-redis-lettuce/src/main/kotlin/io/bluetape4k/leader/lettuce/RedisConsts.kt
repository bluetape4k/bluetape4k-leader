package io.bluetape4k.leader.lettuce

const val REGISTER = "REGISTER"
const val MIGRATE = "MIGRATE"
const val UNREGISTER = "UNREGISTER"
const val REMOVE_IF_VALUE = "REMOVE_IF_VALUE"
const val REMOVE_LEGACY_IF_VALUE = "REMOVE_LEGACY_IF_VALUE"

const val ABSENT = 0L
const val MALFORMED = -1L
const val REGISTERED = 1L
const val UPDATED = 1L
const val MIGRATED = 2L
const val EXISTING_REPAIRED = 3L
const val UNREGISTERED = 4L
const val REMOVED = 5L
const val TOMBSTONED = 6L

const val REDIS_KEY_ABSENT_TTL = -2L
const val MAX_REGISTER_FENCE_ATTEMPTS = 3
