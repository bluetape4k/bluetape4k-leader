package io.bluetape4k.leader.etcd.support

import io.etcd.jetcd.Client
import io.etcd.jetcd.ClientBuilder
import java.util.concurrent.ExecutorService
import kotlin.time.toJavaDuration

inline fun etcdClient(block: ClientBuilder.() -> Unit): Client =
    Client.builder().apply(block).build()

fun etcdClientOf(
    endpoint: String,
    connectTimeout: kotlin.time.Duration? = null,
    retryMaxDelay: Long? = null,
    retryMaxAttemps: Int? = null,
    keepaliveTimeout: kotlin.time.Duration? = null,
    executorService: ExecutorService? = null,
    block: ClientBuilder.() -> Unit = {},
): Client =
    etcdClient {
        endpoints(endpoint)
        connectTimeout?.let { connectTimeout(it.toJavaDuration()) }
        retryMaxDelay?.let { retryMaxDelay(it) }
        retryMaxAttemps?.let { retryMaxAttempts(it) }
        keepaliveTimeout?.let { keepaliveTimeout(it.toJavaDuration()) }
        executorService?.let { executorService(executorService) }
    }
