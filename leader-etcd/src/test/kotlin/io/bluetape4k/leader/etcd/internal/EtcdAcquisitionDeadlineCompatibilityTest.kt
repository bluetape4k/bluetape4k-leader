package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class EtcdAcquisitionDeadlineCompatibilityTest {

    @Test
    fun `acquisition deadline companion retains Object as its direct superclass`() {
        EtcdAcquisitionDeadline.Companion::class.java.superclass shouldBeEqualTo Any::class.java
    }
}
