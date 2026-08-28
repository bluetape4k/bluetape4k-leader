package io.bluetape4k.leader.ktor

import io.bluetape4k.support.requireNotBlank

/** Ktor leader route 경로를 선행 slash가 있는 형식으로 정규화합니다. */
internal fun normalizeLeaderRoutePath(path: String): String {
    path.requireNotBlank("path")
    return if (path.startsWith('/')) path else "/$path"
}
