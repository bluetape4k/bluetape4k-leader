package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.lang.reflect.Modifier
import java.util.Base64
import org.junit.jupiter.api.Test
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.time.Duration.Companion.seconds

class LeaderGroupElectionOptionsCompatibilityTest {

    @Test
    fun `기준 직렬화 payload 는 새 필드를 false 로 읽는다`() {
        val bytes = Base64.getDecoder().decode(LEGACY_SERIALIZED_OPTIONS)

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readObject() as LeaderGroupElectionOptions
        }

        restored.maxLeaders shouldBeEqualTo 3
        restored.nodeId shouldBeEqualTo "legacy-node"
        restored.useDbTime shouldBeEqualTo false
    }

    @Test
    fun `serialVersionUID 와 기존 mangled descriptor 를 보존한다`() {
        val type = LeaderGroupElectionOptions::class.java
        ObjectStreamClass.lookup(type).serialVersionUID shouldBeEqualTo 1L

        val constructor = type.getDeclaredConstructor(
            intType,
            longType,
            longType,
            String::class.java,
            longType,
        )
        Modifier.isPrivate(constructor.modifiers).shouldBeTrue()

        val legacyDefaultConstructor = type.getConstructor(
            intType,
            longType,
            longType,
            String::class.java,
            longType,
            intType,
            DefaultConstructorMarker::class.java,
        )
        val defaultConstructed = legacyDefaultConstructor.newInstance(
            0,
            0L,
            0L,
            "",
            0L,
            31,
            null,
        ) as LeaderGroupElectionOptions
        defaultConstructed shouldBeEqualTo LeaderGroupElectionOptions.Default

        val legacyMarkerConstructor = type.getConstructor(
            intType,
            longType,
            longType,
            String::class.java,
            longType,
            DefaultConstructorMarker::class.java,
        )
        legacyMarkerConstructor.newInstance(
            2,
            5.seconds.inWholeNanoseconds,
            60.seconds.inWholeNanoseconds,
            "legacy-constructor",
            0L,
            null,
        ).let { (it as LeaderGroupElectionOptions).useDbTime shouldBeEqualTo false }

        val copy = type.getDeclaredMethod(
            "copy-5t7Pxr8",
            intType,
            longType,
            longType,
            String::class.java,
            longType,
        )
        copy.invoke(
            LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true),
            4,
            7.seconds.inWholeNanoseconds,
            90.seconds.inWholeNanoseconds,
            "legacy-copy",
            2.seconds.inWholeNanoseconds,
        ).let { (it as LeaderGroupElectionOptions).useDbTime shouldBeEqualTo true }

        val default = type.getDeclaredMethod(
            "copy-5t7Pxr8\$default",
            type,
            intType,
            longType,
            longType,
            String::class.java,
            longType,
            Int::class.javaPrimitiveType ?: error("missing int type"),
            Any::class.java,
        )
        default.invoke(
            null,
            LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true),
            4,
            7.seconds.inWholeNanoseconds,
            90.seconds.inWholeNanoseconds,
            "legacy-default",
            2.seconds.inWholeNanoseconds,
            1,
            null,
        ).let { (it as LeaderGroupElectionOptions).maxLeaders shouldBeEqualTo 3 }
    }

    companion object {
        private val intType = Int::class.javaPrimitiveType ?: error("missing int type")
        private val longType = Long::class.javaPrimitiveType ?: error("missing long type")

        private const val LEGACY_SERIALIZED_OPTIONS =
            "rO0ABXNyAC9pby5ibHVldGFwZTRrLmxlYWRlci5MZWFkZXJHcm91cEVsZWN0aW9uT3B0aW9ucwAAAAAAAAABAgAFSgAJbGVhc2VUaW1lSQAKbWF4TGVhZGVyc0oADG1pbkxlYXNlVGltZUoACHdhaXRUaW1lTAAGbm9kZUlkdAASTGphdmEvbGFuZy9TdHJpbmc7eHAAAAAp6NYIAAAAAAMAAAAA7msoAAAAAANCdwwAdAALbGVnYWN5LW5vZGU="
    }
}
