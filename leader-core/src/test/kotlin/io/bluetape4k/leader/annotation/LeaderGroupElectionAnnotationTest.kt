package io.bluetape4k.leader.annotation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.full.findAnnotation

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderGroupElectionAnnotationTest {

    companion object : KLogging()

    @LeaderGroupElection(name = "group-lock")
    fun annotatedMethod() {}

    @LeaderGroupElection(
        name = "custom-group",
        maxLeaders = 3,
        waitTime = "PT5S",
        leaseTime = "PT60S",
        bean = "redissonLeaderGroupElectionFactory",
        failureMode = LeaderAspectFailureMode.RETHROW,
    )
    fun fullyAnnotatedMethod() {}

    @Test
    fun `기본 속성값 확인`() {
        val annotation = ::annotatedMethod.findAnnotation<LeaderGroupElection>()!!
        annotation.name shouldBeEqualTo "group-lock"
        annotation.maxLeaders shouldBeEqualTo -1
        annotation.waitTime shouldBeEqualTo ""
        annotation.leaseTime shouldBeEqualTo ""
        annotation.bean shouldBeEqualTo ""
        annotation.failureMode shouldBeEqualTo LeaderAspectFailureMode.INHERIT
    }

    @Test
    fun `커스텀 속성값 확인`() {
        val annotation = ::fullyAnnotatedMethod.findAnnotation<LeaderGroupElection>()!!
        annotation.name shouldBeEqualTo "custom-group"
        annotation.maxLeaders shouldBeEqualTo 3
        annotation.waitTime shouldBeEqualTo "PT5S"
        annotation.leaseTime shouldBeEqualTo "PT60S"
        annotation.bean shouldBeEqualTo "redissonLeaderGroupElectionFactory"
        annotation.failureMode shouldBeEqualTo LeaderAspectFailureMode.RETHROW
    }

    @Test
    fun `@Target FUNCTION 확인`() {
        val targets = LeaderGroupElection::class.annotations
            .filterIsInstance<Target>()
            .first()
            .allowedTargets
        targets.contains(AnnotationTarget.FUNCTION).shouldBeTrue()
    }

    @Test
    fun `@Retention RUNTIME 확인`() {
        val retention = LeaderGroupElection::class.annotations
            .filterIsInstance<Retention>()
            .first()
            .value
        retention shouldBeEqualTo AnnotationRetention.RUNTIME
    }
}
