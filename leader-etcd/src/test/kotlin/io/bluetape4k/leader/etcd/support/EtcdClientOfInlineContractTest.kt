package io.bluetape4k.leader.etcd.support

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class EtcdClientOfInlineContractTest {

    @Test
    fun `builder block retains non-local return`() {
        returnFromBuilderBlock() shouldBeEqualTo "returned from builder block"
    }

    @Test
    fun `builder options are applied before caller block`() {
        var observedRetryMaxDelay: Long? = null
        val client = etcdClientOf(
            endpoint = "http://127.0.0.1:2379",
            retryMaxDelay = 100L,
            retryMaxAttempts = 3,
        ) {
            observedRetryMaxDelay = retryMaxDelay()
            retryMaxAttempts(7)
        }

        try {
            observedRetryMaxDelay shouldBeEqualTo 100L
        } finally {
            client.close()
        }
    }

    private fun returnFromBuilderBlock(): String {
        val executor = Executors.newSingleThreadExecutor()
        try {
            etcdClientOf(
                endpoint = "http://127.0.0.1:2379",
                executorService = executor,
            ) {
                return "returned from builder block"
            }
            return "builder block returned normally"
        } finally {
            executor.shutdownNow()
        }
    }
}
