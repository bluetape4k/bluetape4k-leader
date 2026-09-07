package io.bluetape4k.leader.lettuce

import io.lettuce.core.api.StatefulRedisConnection
import java.lang.reflect.Proxy

/**
 * strategic elector의 standalone primary constructor를 유지하면서 Redis Cluster 보조
 * constructor가 같은 초기화 경로를 사용하도록 하는 내부 연결 placeholder입니다.
 * Cluster constructor는 primary 초기화 직후 실제 cluster registry로 교체합니다.
 */
internal object LettuceStrategicConstructorSupport {

    @Suppress("UNCHECKED_CAST")
    val clusterPrimaryConnection: StatefulRedisConnection<String, String> =
        Proxy.newProxyInstance(
            StatefulRedisConnection::class.java.classLoader,
            arrayOf(StatefulRedisConnection::class.java),
        ) { _, _, _ ->
            error("Cluster constructor placeholder connection must not be used")
        } as StatefulRedisConnection<String, String>
}
