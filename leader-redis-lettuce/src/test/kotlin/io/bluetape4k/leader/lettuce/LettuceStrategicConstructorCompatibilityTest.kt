package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

class LettuceStrategicConstructorCompatibilityTest {

    @Test
    fun `strategic electors retain standalone public primary constructors`() {
        expectedContracts.forEach { contract ->
            val primaryConstructor = contract.type.primaryConstructor.shouldNotBeNull()

            primaryConstructor.visibility shouldBeEqualTo KVisibility.PUBLIC
            primaryConstructor.parameters.map { it.type.jvmErasure } shouldBeEqualTo
                listOf(StatefulRedisConnection::class, String::class)
            primaryConstructor.parameters.map { it.name } shouldBeEqualTo listOf("connection", "nodeId")
            primaryConstructor.parameters.map { it.isOptional } shouldBeEqualTo listOf(false, true)
        }
    }

    @Test
    fun `strategic electors retain additive cluster java constructors`() {
        expectedContracts.forEach { contract ->
            val descriptors = contract.type.java.declaredConstructors
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.parameterTypes.toList() }
                .toSet()

            descriptors shouldBeEqualTo setOf(
                listOf(StatefulRedisConnection::class.java, String::class.java),
                listOf(StatefulRedisConnection::class.java),
                listOf(StatefulRedisClusterConnection::class.java, String::class.java),
                listOf(StatefulRedisClusterConnection::class.java),
            )
        }
    }

    private data class ConstructorContract(val type: KClass<*>)

    private companion object {
        val expectedContracts = listOf(
            ConstructorContract(LettuceStrategicLeaderElector::class),
            ConstructorContract(LettuceStrategicLeaderGroupElector::class),
            ConstructorContract(LettuceStrategicSuspendLeaderElector::class),
            ConstructorContract(LettuceStrategicSuspendLeaderGroupElector::class),
        )
    }
}
