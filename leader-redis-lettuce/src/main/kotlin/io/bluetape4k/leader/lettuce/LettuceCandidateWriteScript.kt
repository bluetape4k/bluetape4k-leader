package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript

/**
 * v3 후보 value/index/fence 상태를 하나의 hash slot에서 갱신하는 스크립트입니다.
 *
 * legacy source key는 migration payload로만 전달하며 `KEYS`에 포함하지 않습니다.
 * 따라서 migration 이후 source 정리와 v3 destination 원자성의 경계를 분리할 수
 * 있고, Cluster에서 `CROSSSLOT`을 유발하지 않습니다.
 */
internal object LettuceCandidateWriteScript {

    const val REGISTER = "REGISTER"
    const val MIGRATE = "MIGRATE"
    const val UNREGISTER = "UNREGISTER"
    const val REMOVE_IF_VALUE = "REMOVE_IF_VALUE"

    const val ABSENT = 0L
    const val MALFORMED = -1L
    const val REGISTERED = 1L
    const val MIGRATED = 2L
    const val EXISTING_REPAIRED = 3L
    const val UNREGISTERED = 4L
    const val REMOVED = 5L
    const val TOMBSTONED = 6L

    val WRITE = RedisScript(
        """
        local operation = ARGV[1]

        if operation == '$REGISTER' then
          local ttl = tonumber(ARGV[3])
          if not ttl or ttl < 0 or ttl % 1 ~= 0 then
            return redis.error_reply('candidate TTL must be a non-negative integer')
          end

          redis.call('DEL', KEYS[3], KEYS[4])
          if ttl > 0 then
            redis.call('PSETEX', KEYS[1], ttl, ARGV[2])
          else
            redis.call('SET', KEYS[1], ARGV[2])
          end
          redis.call('SADD', KEYS[2], ARGV[4])
          redis.call('PERSIST', KEYS[2])
          return { $REGISTERED }
        end

        if operation == '$MIGRATE' then
          if redis.call('EXISTS', KEYS[3]) == 1 then
            return { $TOMBSTONED }
          end

          local ttl = tonumber(ARGV[3])
          if not ttl or ttl == -2 or ttl == 0 or ttl < -2 then
            return { $ABSENT }
          end

          if redis.call('EXISTS', KEYS[1]) == 1 then
            local current = redis.call('GET', KEYS[1])
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

            local currentNodeId, registeredAt, lastStartTime, lastCompletionTime, successCount, failureCount, metadata =
              string.match(current, '^([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|([^|]*)|(.*)$')
            local sourceNodeId = string.match(ARGV[2], '^([^|]*)|')
            if not currentNodeId or not sourceNodeId
              or not validLong(registeredAt)
              or not validOptionalLong(lastStartTime)
              or not validOptionalLong(lastCompletionTime)
              or not validLong(successCount)
              or not validLong(failureCount)
              or not validMetadata(metadata) then
              return { $MALFORMED, current }
            end

            if currentNodeId == sourceNodeId then
              redis.call('SADD', KEYS[2], ARGV[4])
              redis.call('PERSIST', KEYS[2])
              return { $EXISTING_REPAIRED }
            end

            redis.call('DEL', KEYS[1], KEYS[4])
            redis.call('SREM', KEYS[2], ARGV[4])
          end

          local written
          if ttl == -1 then
            written = redis.call('SET', KEYS[1], ARGV[2], 'NX')
          elseif ttl > 0 then
            written = redis.call('SET', KEYS[1], ARGV[2], 'NX', 'PX', ttl)
          end
          if written then
            redis.call('SADD', KEYS[2], ARGV[4])
            redis.call('PERSIST', KEYS[2])
            redis.call('SET', KEYS[4], ARGV[5])
            return { $MIGRATED, ARGV[5] }
          end

          if redis.call('EXISTS', KEYS[1]) == 1 then
            redis.call('SADD', KEYS[2], ARGV[4])
            redis.call('PERSIST', KEYS[2])
            return { $EXISTING_REPAIRED }
          end
          return { $ABSENT }
        end

        if operation == '$UNREGISTER' then
          redis.call('SET', KEYS[3], '1')
          redis.call('DEL', KEYS[1], KEYS[4])
          redis.call('SREM', KEYS[2], ARGV[2])
          return { $UNREGISTERED }
        end

        if operation == '$REMOVE_IF_VALUE' then
          local current = redis.call('GET', KEYS[1])
          local currentToken = redis.call('GET', KEYS[3])
          if current and currentToken and current == ARGV[2] and currentToken == ARGV[3] then
            redis.call('DEL', KEYS[1], KEYS[3])
            redis.call('SREM', KEYS[2], ARGV[4])
            return { $REMOVED }
          end
          return { $ABSENT }
        end

        return redis.error_reply('unsupported candidate write operation: ' .. operation)
        """.trimIndent()
    )
}
