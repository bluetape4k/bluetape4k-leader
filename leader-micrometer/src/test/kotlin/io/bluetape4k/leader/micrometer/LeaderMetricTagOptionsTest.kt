package io.bluetape4k.leader.micrometer

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderMetricTagOptionsTest {

    @Test
    fun `default options redact lock name and leader id`() {
        val sanitizer = LeaderMetricTagSanitizer.Default

        sanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, "tenant-42-job") shouldBeEqualTo "redacted-lock"
        sanitizer.sanitize(TAG_LEADER_ID, "leader-42") shouldBeEqualTo "redacted-leader"
    }

    @Test
    fun `raw options pass through every tag value`() {
        val sanitizer = LeaderMetricTagSanitizer.from(LeaderMetricTagOptions.Raw)

        sanitizer.sanitize(MicrometerNames.TAG_LOCK_NAME, "tenant-42-job") shouldBeEqualTo "tenant-42-job"
        sanitizer.sanitize(TAG_LEADER_ID, "leader-42") shouldBeEqualTo "leader-42"
        sanitizer.sanitize("custom.tag", "value") shouldBeEqualTo "value"
    }

    @Test
    fun `denylist wins over allowlist overlap`() {
        val rule = LeaderMetricTagRule(
            mode = LeaderMetricTagMode.RAW,
            allowList = setOf("static-job"),
            denyList = setOf("static-job"),
            redactedValue = "blocked",
        )

        rule.sanitize("static-job") shouldBeEqualTo "blocked"
    }

    @Test
    fun `allowlist admits only exact static values`() {
        val rule = LeaderMetricTagRule(
            mode = LeaderMetricTagMode.RAW,
            allowList = setOf("daily-job"),
            redactedValue = "other-job",
        )

        rule.sanitize("daily-job") shouldBeEqualTo "daily-job"
        rule.sanitize("tenant-42-job") shouldBeEqualTo "other-job"
    }

    @Test
    fun `hash mode is deterministic lowercase sha256 prefix`() {
        val rule = LeaderMetricTagRule(
            mode = LeaderMetricTagMode.HASH,
            hashLength = 12,
        )

        rule.sanitize("tenant-42-job") shouldBeEqualTo "765d1df1024d"
        rule.sanitize("tenant-42-job") shouldBeEqualTo rule.sanitize("tenant-42-job")
    }

    @Test
    fun `truncate mode requires positive max length and truncates raw value`() {
        val rule = LeaderMetricTagRule(
            mode = LeaderMetricTagMode.TRUNCATE,
            maxLength = 6,
        )

        rule.sanitize("tenant-42-job") shouldBeEqualTo "tenant"
        assertFailsWith<IllegalArgumentException> {
            LeaderMetricTagRule(mode = LeaderMetricTagMode.TRUNCATE, maxLength = 0)
        }
    }

    @Test
    fun `invalid validation values fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderMetricTagRule(redactedValue = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderMetricTagRule(maxLength = -1)
        }
    }

    @Test
    fun `unknown tag keys use default rule`() {
        val options = LeaderMetricTagOptions(
            defaultRule = LeaderMetricTagRule(redactedValue = "unknown"),
        )

        LeaderMetricTagSanitizer.from(options).sanitize("custom.tag", "raw") shouldBeEqualTo "unknown"
    }
}
