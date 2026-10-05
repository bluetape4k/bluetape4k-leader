package io.bluetape4k.leader.etcd.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget
import org.junit.jupiter.api.Test

class EtcdBuilderSupportTest {

    @Test
    fun `get option helper applies block after convenience values`() {
        val endKey = "jobs-end".toByteSequence()
        val option = getOptionOf(
            isPrefix = false,
            sortField = SortTarget.KEY,
            sortOrder = SortOrder.ASCEND,
            limit = 1,
        ) {
            isPrefix(true)
            withRange(endKey)
            withSortField(SortTarget.MOD)
            withSortOrder(SortOrder.DESCEND)
            withLimit(7)
        }

        option.isPrefix() shouldBeEqualTo true
        option.getEndKey().orElseThrow() shouldBeEqualTo endKey
        option.getSortField() shouldBeEqualTo SortTarget.MOD
        option.getSortOrder() shouldBeEqualTo SortOrder.DESCEND
        option.getLimit() shouldBeEqualTo 7L
    }

    @Test
    fun `client helper applies scalar defaults before builder block`() {
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
}
