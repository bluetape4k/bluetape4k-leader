package io.bluetape4k.leader.spring.properties

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

class LeaderRouteGuardPropertiesSerializationTest {

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

        val copy = type.getMethod(
            "copy",
            Boolean::class.javaPrimitiveType,
            LeaderRouteAuthorityMode::class.java,
            String::class.java,
            LeaderRouteRejectionStatus::class.java,
        ).invoke(original, false, LeaderRouteAuthorityMode.STATE, "", LeaderRouteRejectionStatus.NOT_FOUND)
            .shouldBeInstanceOf<LeaderRouteGuardProperties>()
        copy.enabled shouldBeEqualTo false
        copy.authorityMode shouldBeEqualTo LeaderRouteAuthorityMode.STATE
        copy.rejectionStatus shouldBeEqualTo LeaderRouteRejectionStatus.NOT_FOUND
        copy.redirect shouldBeEqualTo LeaderRouteRedirectProperties()

        val copyDefault = type.getMethod(
            "copy\$default",
            type,
            Boolean::class.javaPrimitiveType,
            LeaderRouteAuthorityMode::class.java,
            String::class.java,
            LeaderRouteRejectionStatus::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        ).invoke(null, original, false, null, null, null, 0b1110, null)
            .shouldBeInstanceOf<LeaderRouteGuardProperties>()
        copyDefault.enabled shouldBeEqualTo false
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
        val redirectField = LeaderRouteGuardProperties::class.java.getDeclaredField("redirect")
        redirectField.isAccessible = true
        redirectField.set(legacyShape, null)

        val restored = roundTrip(legacyShape)

        restored.enabled shouldBeEqualTo true
        restored.authorityMode shouldBeEqualTo LeaderRouteAuthorityMode.CUSTOM
        restored.electorBean shouldBeEqualTo "ordersAuthority"
        restored.rejectionStatus shouldBeEqualTo LeaderRouteRejectionStatus.LOCKED
        restored.redirect shouldBeEqualTo LeaderRouteRedirectProperties()
        ObjectStreamClass.lookup(LeaderRouteGuardProperties::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
            @Suppress("UNCHECKED_CAST")
            it.readObject() as T
        }
    }
}
