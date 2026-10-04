package io.bluetape4k.leader.spring.properties

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.lookup
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class LeaderRouteGuardPropertiesSerializationTest {

    companion object: KLogging()

    @Test
    fun `legacy four argument constructor and copy bridges remain callable`() {
        val type = LeaderRouteGuardProperties::class.java
        val constructor = type.getConstructor(
            Boolean::class.javaPrimitiveType,
            LeaderRouteAuthorityMode::class.java,
            String::class.java,
            LeaderRouteRejectionStatus::class.java,
        )
        val original = constructor.newInstance(
            true,
            LeaderRouteAuthorityMode.CUSTOM,
            "ordersAuthority",
            LeaderRouteRejectionStatus.LOCKED,
        ) as LeaderRouteGuardProperties

        original.redirect shouldBeEqualTo LeaderRouteRedirectProperties()

        val copy = type
            .getMethod(
                "copy",
                Boolean::class.javaPrimitiveType,
                LeaderRouteAuthorityMode::class.java,
                String::class.java,
                LeaderRouteRejectionStatus::class.java,
            )
            .invoke(original, false, LeaderRouteAuthorityMode.STATE, "", LeaderRouteRejectionStatus.NOT_FOUND)
            .shouldBeInstanceOf<LeaderRouteGuardProperties>()

        log.debug { "copy=$copy" }
        copy.enabled.shouldBeFalse()
        copy.authorityMode shouldBeEqualTo LeaderRouteAuthorityMode.STATE
        copy.rejectionStatus shouldBeEqualTo LeaderRouteRejectionStatus.NOT_FOUND
        copy.redirect shouldBeEqualTo LeaderRouteRedirectProperties()

        val copyDefault = type
            .getMethod(
                "copy\$default",
                type,
                Boolean::class.javaPrimitiveType,
                LeaderRouteAuthorityMode::class.java,
                String::class.java,
                LeaderRouteRejectionStatus::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
            .invoke(null, original, false, null, null, null, 0b1110, null)
            .shouldBeInstanceOf<LeaderRouteGuardProperties>()

        log.debug { "copyDefault=$copyDefault" }
        copyDefault.enabled.shouldBeFalse()
        copyDefault.authorityMode shouldBeEqualTo original.authorityMode
        copyDefault.electorBean shouldBeEqualTo original.electorBean
        copyDefault.rejectionStatus shouldBeEqualTo original.rejectionStatus
    }

    @Test
    fun `legacy serialized object without redirect field defaults safely`() {
        val legacyShape = LeaderRouteGuardProperties(
            enabled = true,
            authorityMode = LeaderRouteAuthorityMode.CUSTOM,
            electorBean = "ordersAuthority",
            rejectionStatus = LeaderRouteRejectionStatus.LOCKED,
        )
        log.debug { "legacyShape=$legacyShape" }

        val redirectField = LeaderRouteGuardProperties::class.java.getDeclaredField("redirect")
        redirectField.isAccessible = true
        redirectField.set(legacyShape, null)

        val restored = roundTrip(legacyShape)

        log.debug { "restored=$restored" }
        restored.enabled.shouldBeTrue()
        restored.authorityMode shouldBeEqualTo LeaderRouteAuthorityMode.CUSTOM
        restored.electorBean shouldBeEqualTo "ordersAuthority"
        restored.rejectionStatus shouldBeEqualTo LeaderRouteRejectionStatus.LOCKED
        restored.redirect shouldBeEqualTo LeaderRouteRedirectProperties()

        LeaderRouteGuardProperties::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `legacy fastfory serialized object without redirect field defaults safely`() {
        val legacyShape = LeaderRouteGuardProperties(
            enabled = true,
            authorityMode = LeaderRouteAuthorityMode.CUSTOM,
            electorBean = "ordersAuthority",
            rejectionStatus = LeaderRouteRejectionStatus.LOCKED,
        )
        log.debug { "legacyShape=$legacyShape" }

        val redirectField = LeaderRouteGuardProperties::class.java.getDeclaredField("redirect")
        redirectField.isAccessible = true
        redirectField.set(legacyShape, null)

        val restored = roundTripFastFory(legacyShape)

        log.debug { "restored=$restored" }
        restored.enabled.shouldBeTrue()
        restored.authorityMode shouldBeEqualTo LeaderRouteAuthorityMode.CUSTOM
        restored.electorBean shouldBeEqualTo "ordersAuthority"
        restored.rejectionStatus shouldBeEqualTo LeaderRouteRejectionStatus.LOCKED
        restored.redirect shouldBeEqualTo LeaderRouteRedirectProperties()

        LeaderRouteGuardProperties::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    @Suppress("DEPRECATION")
    private fun <T: Any> roundTrip(value: T): T {
        val bytes = BinarySerializers.Jdk.serialize(value)
        return BinarySerializers.Jdk.deserialize<T>(bytes).shouldNotBeNull()
    }

    private fun <T: Any> roundTripFastFory(value: T): T {
        val bytes = BinarySerializers.FastFory.serialize(value)
        return BinarySerializers.FastFory.deserialize<T>(bytes).shouldNotBeNull()
    }
}
