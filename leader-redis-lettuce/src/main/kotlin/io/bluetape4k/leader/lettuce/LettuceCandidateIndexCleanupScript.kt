package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript

/**
 * 현재 후보 key가 여전히 없는 경우에만 index member를 제거합니다.
 *
 * 후보 migration 또는 동시 등록이 stale snapshot과 index 정리 사이에 key를 만들 수
 * 있으므로, 존재 확인과 SREM을 하나의 Redis 원자 실행으로 묶습니다.
 */
internal object LettuceCandidateIndexCleanupScript {

    val REMOVE_MISSING = RedisScript(
        """
        local removed = 0
        for index = 2, #KEYS do
          if redis.call('EXISTS', KEYS[index]) == 0 then
            removed = removed + redis.call('SREM', KEYS[1], ARGV[index - 1])
          end
        end
        return removed
        """.trimIndent()
    )
}
