package io.bluetape4k.leader.consul.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.testcontainers.infra.ConsulServer

internal object ConsulContractSupport {
    val server: ConsulServer by lazy { ConsulServer.Launcher.consul }

    fun endpoint(): ConsulEndpoint = ConsulEndpoint(server.url)

    fun keyPrefix(): String = "bluetape4k/contract-" + Base58.randomString(8)
}
