package io.bluetape4k.leader.consul.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.internal.BackendErrorKind
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.http.HttpTimeoutException

class ConsulBackendErrorClassifierTest {

    @Test
    fun `classifies HTTP timeout as transient`() {
        ConsulBackendErrorClassifier.classify(HttpTimeoutException("timeout")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `leaves non Consul specific errors unclassified`() {
        ConsulBackendErrorClassifier.classify(ConnectException("refused")).shouldBeNull()
    }
}
