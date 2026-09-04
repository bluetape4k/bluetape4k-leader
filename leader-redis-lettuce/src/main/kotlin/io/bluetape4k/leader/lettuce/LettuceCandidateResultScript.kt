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

        local LONG_MAX = '9223372036854775807'
        local LONG_MIN_ABS = '9223372036854775808'

        local function normalizeLong(value)
          if not value or not string.match(value, '^[+-]?%d+$') then
            return nil
          end

          local sign = ''
          local digits = value
          local first = string.sub(value, 1, 1)
          if first == '+' or first == '-' then
            sign = first
            digits = string.sub(value, 2)
          end
          digits = string.gsub(digits, '^0+', '')
          if digits == '' then
            digits = '0'
          end
          if sign == '-' and digits ~= '0' then
            digits = '-' .. digits
          end

          local negative = string.sub(digits, 1, 1) == '-'
          local absolute = negative and string.sub(digits, 2) or digits
          local limit = negative and LONG_MIN_ABS or LONG_MAX
          if string.len(absolute) > string.len(limit)
            or (string.len(absolute) == string.len(limit) and absolute > limit) then
            return nil
          end
          return digits
        end

        local function addPositiveOne(digits)
          local result = {}
          local index = string.len(digits)
          local carry = 1
          while index > 0 do
            local digit = string.byte(digits, index) - string.byte('0')
            local sum = digit + carry
            result[index] = string.char((sum % 10) + string.byte('0'))
            carry = math.floor(sum / 10)
            index = index - 1
          end
          if carry == 1 then
            table.insert(result, 1, '1')
          end
          return table.concat(result)
        end

        local function subtractPositiveOne(digits)
          local result = {}
          local index = string.len(digits)
          local borrow = 1
          while index > 0 do
            local digit = string.byte(digits, index) - string.byte('0')
            local difference = digit - borrow
            if difference < 0 then
              difference = difference + 10
              borrow = 1
            else
              borrow = 0
            end
            result[index] = string.char(difference + string.byte('0'))
            index = index - 1
          end
          local normalized = string.gsub(table.concat(result), '^0+', '')
          if normalized == '' then
            return '0'
          end
          return normalized
        end

        local function incrementLong(value)
          local normalized = normalizeLong(value)
          if not normalized then
            return nil
          end
          if string.sub(normalized, 1, 1) == '-' then
            local absolute = string.sub(normalized, 2)
            if absolute == '1' then
              return '0'
            end
            return '-' .. subtractPositiveOne(absolute)
          end
          if normalized == LONG_MAX then
            return '-' .. LONG_MIN_ABS
          end
          return addPositiveOne(normalized)
        end

        local function normalizeOptionalLong(value)
          if value == '' then
            return ''
          end
          return normalizeLong(value) or ''
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
        local normalizedRegisteredAt = normalizeLong(registeredAt)
        local normalizedSuccessCount = normalizeLong(successCount)
        local normalizedFailureCount = normalizeLong(failureCount)
        if not nodeId or not normalizedRegisteredAt or not normalizedSuccessCount
          or not normalizedFailureCount or not validMetadata(metadata) then
          return { $MALFORMED, current }
        end

        local normalizedStartTime = normalizeOptionalLong(lastStartTime)
        local normalizedCompletionTime = normalizeOptionalLong(lastCompletionTime)

        if ARGV[1] == 'SUCCESS' then
          normalizedSuccessCount = incrementLong(normalizedSuccessCount)
        elseif ARGV[1] == 'FAILURE' then
          normalizedFailureCount = incrementLong(normalizedFailureCount)
        else
          return redis.error_reply('지원하지 않는 CandidateResult: ' .. ARGV[1])
        end

        local updated = table.concat({
          nodeId, normalizedRegisteredAt, normalizedStartTime, ARGV[2], normalizedSuccessCount,
          normalizedFailureCount, metadata
        }, '|')
        local written = redis.call('SET', KEYS[1], updated, 'XX', 'KEEPTTL')
        if written then
          redis.call('DEL', KEYS[2])
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
