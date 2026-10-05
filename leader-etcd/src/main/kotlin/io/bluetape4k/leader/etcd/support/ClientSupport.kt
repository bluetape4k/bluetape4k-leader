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
    retryMaxAttempts: Int? = null,
    keepaliveTimeout: kotlin.time.Duration? = null,
    executorService: ExecutorService? = null,
    block: ClientBuilder.() -> Unit = {},
): Client =
    etcdClient {
        endpoints(endpoint)
        connectTimeout?.let { this.connectTimeout(it.toJavaDuration()) }
        retryMaxDelay?.let { this.retryMaxDelay(it) }
        retryMaxAttempts?.let { this.retryMaxAttempts(it) }
        keepaliveTimeout?.let { this.keepaliveTimeout(it.toJavaDuration()) }
        executorService?.let { this.executorService(it) }
        block()
    }
