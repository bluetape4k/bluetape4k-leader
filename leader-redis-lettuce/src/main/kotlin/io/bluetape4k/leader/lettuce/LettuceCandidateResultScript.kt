package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.strategy.CandidateResult

/**
 * Redis 서버 안에서 후보 결과 카운터를 읽고 갱신하는 스크립트입니다.
 *
 * 후보 값은 하나의 문자열로 인코딩되어 있으므로 클라이언트의
 * read-modify-write 대신 Redis 원자 실행 단위에서 카운터와 TTL을 함께 보존합니다.
 */
internal object LettuceCandidateResultScript {

    const val ABSENT = 0L
    const val UPDATED = 1L
    const val MALFORMED = -1L

    val UPDATE = RedisScript(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
          return { $ABSENT }
        end

        local nodeId, registeredAt, lastStartTime, lastCompletionTime, successCount, failureCount, metadata =
          string.match(current, '^([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|(.*)$')
        if not nodeId or not tonumber(registeredAt)
          or (lastStartTime ~= '' and not tonumber(lastStartTime))
          or (lastCompletionTime ~= '' and not tonumber(lastCompletionTime))
          or not tonumber(successCount) or not tonumber(failureCount) then
          return { $MALFORMED, current }
        end

        if ARGV[1] == 'SUCCESS' then
          successCount = tostring(tonumber(successCount) + 1)
        elseif ARGV[1] == 'FAILURE' then
          failureCount = tostring(tonumber(failureCount) + 1)
        else
          return redis.error_reply('지원하지 않는 CandidateResult: ' .. ARGV[1])
        end

        local updated = table.concat({
          nodeId, registeredAt, lastStartTime, ARGV[2], successCount, failureCount, metadata
        }, '|')
        local written = redis.call('SET', KEYS[1], updated, 'XX', 'KEEPTTL')
        if written then
          return { $UPDATED }
        end
        return { $ABSENT }
        """.trimIndent()
    )

    /** 스크립트가 감지한 기존 codec 오류를 기존 예외 계약으로 다시 표면화합니다. */
    fun rethrowMalformed(reply: List<Any>) {
        val status = reply.firstOrNull()?.toString()?.toLongOrNull()
        if (status == MALFORMED) {
            LettuceCandidateInfoCodec.decode(reply.getOrNull(1)?.toString().orEmpty())
        }
    }

    fun resultArgs(result: CandidateResult, completionTimeMillis: Long): Array<String> = arrayOf(
        result.name,
        completionTimeMillis.toString(),
    )
}
