package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.etcd.AbstractEtcdLeaderTest
import io.etcd.jetcd.Client

internal object EtcdContractSupport {
    val client: Client by lazy { AbstractEtcdLeaderTest.newClient() }

    fun keyPrefix(): String = "/bluetape4k/contract-" + Base58.randomString(8)
}
