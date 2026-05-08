package io.bluetape4k.leader.annotation

import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.full.findAnnotation

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderElectionAnnotationTest {

    companion object : KLogging()

    @LeaderElection(name = "test-lock")
    fun annotatedMethod() {}

    @LeaderElection(
        name = "custom-lock",
        waitTime = "PT5S",
        leaseTime = "PT60S",
        bean = "redissonLeaderElectionFactory",
        failureMode = LeaderAspectFailureMode.SKIP,
    )
    fun fullyAnnotatedMethod() {}

    @Test
    fun `기본 속성값 확인`() {
        val annotation = ::annotatedMethod.findAnnotation<LeaderElection>()!!
        annotation.name shouldBeEqualTo "test-lock"
        annotation.waitTime shouldBeEqualTo ""
        annotation.leaseTime shouldBeEqualTo ""
        annotation.bean shouldBeEqualTo ""
        annotation.failureMode shouldBeEqualTo LeaderAspectFailureMode.INHERIT
    }

    @Test
    fun `커스텀 속성값 확인`() {
        val annotation = ::fullyAnnotatedMethod.findAnnotation<LeaderElection>()!!
        annotation.name shouldBeEqualTo "custom-lock"
        annotation.waitTime shouldBeEqualTo "PT5S"
        annotation.leaseTime shouldBeEqualTo "PT60S"
        annotation.bean shouldBeEqualTo "redissonLeaderElectionFactory"
        annotation.failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
    }

    @Test
    fun `@Target FUNCTION 확인`() {
        val targets = LeaderElection::class.annotations
            .filterIsInstance<Target>()
            .first()
            .allowedTargets
        targets.contains(AnnotationTarget.FUNCTION).shouldBeTrue()
    }

    @Test
    fun `@Retention RUNTIME 확인`() {
        val retention = LeaderElection::class.annotations
            .filterIsInstance<Retention>()
            .first()
            .value
        retention shouldBeEqualTo AnnotationRetention.RUNTIME
    }
}
