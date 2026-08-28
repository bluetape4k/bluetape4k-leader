package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript

/**
 * Redis 후보 heartbeat를 기존 결과 통계와 같은 원자 경계에서 갱신하는 스크립트입니다.
 *
 * 현재 후보의 등록·실행·결과 필드는 보존하고 metadata와 요청한 TTL만 교체합니다.
 * 후보가 이미 만료되었으면 새 후보를 만들지 않고 `ABSENT`를 반환합니다.
 */
internal object LettuceCandidateRefreshScript {

    const val ABSENT = 0L
    const val UPDATED = 1L
    const val MALFORMED = -1L

    val REFRESH = RedisScript(
        """
        local current = redis.call('GET', KEYS[1])
        if not current then
          return { $ABSENT }
        end

        local function validLong(value)
          return value and string.match(value, '^[+-]?%d+$') ~= nil
        end

        local function validOptionalLong(value)
          return value == '' or validLong(value)
        end

        local function validMetadata(value)
          if value == '' then
            return true
          end
          local start = 1
          while true do
            local separator = string.find(value, ',', start, true)
            local item = separator and string.sub(value, start, separator - 1) or string.sub(value, start)
            if not string.find(item, '=', 1, true) then
              return false
            end
            if not separator then
              return true
            end
            start = separator + 1
          end
        end

        local nodeId, registeredAt, lastStartTime, lastCompletionTime, successCount, failureCount, metadata =
          string.match(current, '^([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|(.*)$')
        local incomingNodeId, _, _, _, _, _, incomingMetadata =
          string.match(ARGV[1], '^([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|(.*)$')

        if not nodeId or not incomingNodeId or incomingNodeId ~= nodeId
          or not validLong(registeredAt)
          or not validOptionalLong(lastStartTime)
          or not validOptionalLong(lastCompletionTime)
          or not validLong(successCount)
          or not validLong(failureCount)
          or not validMetadata(metadata)
          or not incomingMetadata
          or not validMetadata(incomingMetadata) then
          return { $MALFORMED, current }
        end

        local updated = table.concat({
          nodeId, registeredAt, lastStartTime, lastCompletionTime, successCount,
          failureCount, incomingMetadata
        }, '|')
        local ttl = tonumber(ARGV[2])
        if ttl and ttl > 0 then
          redis.call('PSETEX', KEYS[1], ttl, updated)
        else
          redis.call('SET', KEYS[1], updated)
        end
        redis.call('SADD', KEYS[2], nodeId)
        redis.call('PERSIST', KEYS[2])
        return { $UPDATED }
        """.trimIndent()
    )

    /** 스크립트가 감지한 기존 codec 오류를 기존 예외 계약으로 다시 표면화합니다. */
    fun rethrowMalformed(reply: List<Any>) {
        val status = reply.firstOrNull()?.toString()?.toLongOrNull()
        if (status == MALFORMED) {
            LettuceCandidateInfoCodec.decode(reply.getOrNull(1)?.toString().orEmpty())
        }
    }
}
