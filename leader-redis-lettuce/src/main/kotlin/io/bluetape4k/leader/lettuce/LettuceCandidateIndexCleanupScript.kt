package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript

/**
 * 현재 후보 key가 여전히 없는 경우에만 index member를 제거합니다.
 *
 * 후보 migration 또는 동시 등록이 stale snapshot과 index 정리 사이에 key를 만들 수
 * 있으므로, 존재 확인과 SREM/token 정리를 하나의 Redis 원자 실행으로 묶습니다.
 */
internal object LettuceCandidateIndexCleanupScript {

    val REMOVE_MISSING = RedisScript(
        """
        local removed = 0
        local candidateIndex = 2
        local memberIndex = 1
        while candidateIndex <= #KEYS do
          if redis.call('EXISTS', KEYS[candidateIndex]) == 0 then
            removed = removed + redis.call('SREM', KEYS[1], ARGV[memberIndex])
            redis.call('DEL', KEYS[candidateIndex + 1])
          end
          candidateIndex = candidateIndex + 2
          memberIndex = memberIndex + 1
        end
        return removed
        """.trimIndent()
    )
}
