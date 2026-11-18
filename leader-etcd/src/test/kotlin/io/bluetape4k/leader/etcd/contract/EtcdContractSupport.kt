package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.etcd.support.etcdClientOf
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.bluetape4k.utils.ShutdownQueue
import io.etcd.jetcd.Client
import kotlin.time.Duration.Companion.seconds

internal object EtcdContractSupport {

    val etcdServer by lazy { EtcdServer.Launcher.etcd }

    fun newClient(): Client = etcdClientOf(
        endpoint = etcdServer.endpoint,
        connectTimeout = 10.seconds
    )

    val client: Client by lazy {
        newClient().apply {
            ShutdownQueue.register(this)
        }
    }

    fun keyPrefix(): String = "/bluetape4k/contract-" + Base58.randomString(8)
}
