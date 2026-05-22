package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.consul.ConsulEndpoint
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal class JavaHttpConsulLockClient(
    private val endpoint: ConsulEndpoint,
    keyPrefix: String = ConsulLeaderPaths.DefaultPrefix,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(endpoint.requestTimeout.toJavaDuration())
        .build(),
) : ConsulLockClient {

    private val paths = ConsulLeaderPaths(keyPrefix)

    override fun singleLockKey(lockName: String): String =
        paths.single(lockName)

    override fun groupLockKey(lockName: String, slot: Int): String =
        paths.group(lockName, slot)

    override fun createSession(
        name: String,
        ttl: Duration,
        lockDelay: Duration,
    ): CompletableFuture<ConsulSessionId> {
        require(name.isNotBlank()) { "name must not be blank." }
        val payload = buildString {
            append('{')
            appendJsonField("Name", name)
            append(',')
            appendJsonField("Behavior", "release")
            append(',')
            appendJsonField("TTL", "${ConsulSessionTtl.ttlSeconds(ttl)}s")
            append(',')
            appendJsonField("LockDelay", "${lockDelay.inWholeSeconds}s")
            append('}')
        }

        val request = requestBuilder("/v1/session/create")
            .PUT(HttpRequest.BodyPublishers.ofString(payload))
            .header("Content-Type", "application/json")
            .build()

        return sendString(request).thenApply { response ->
            response.requireStatus(200, "create Consul session")
            val id = JSON_STRING_FIELD.find(response.body())?.groupValues?.get(1)
                ?: throw LeaderElectionException("Consul session create response did not include ID.")
            ConsulSessionId(id)
        }
    }

    override fun acquire(
        key: String,
        sessionId: ConsulSessionId,
        ownerPayload: String,
    ): CompletableFuture<Boolean> {
        require(key.isNotBlank()) { "key must not be blank." }
        return putBoolean("/v1/kv/$key", mapOf("acquire" to sessionId.value), ownerPayload, "acquire Consul KV lock")
    }

    override fun release(
        key: String,
        sessionId: ConsulSessionId,
    ): CompletableFuture<Boolean> {
        require(key.isNotBlank()) { "key must not be blank." }
        return putBoolean("/v1/kv/$key", mapOf("release" to sessionId.value), "", "release Consul KV lock")
    }

    override fun destroySession(sessionId: ConsulSessionId): CompletableFuture<Unit> {
        val request = requestBuilder("/v1/session/destroy/${urlEncode(sessionId.value)}")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

        return sendString(request).thenApply { response ->
            response.requireStatus(200, "destroy Consul session")
            Unit
        }
    }

    override fun renewSession(sessionId: ConsulSessionId): CompletableFuture<ConsulSessionRenewal> {
        val request = requestBuilder("/v1/session/renew/${urlEncode(sessionId.value)}")
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

        return sendString(request).thenApply { response ->
            response.requireStatus(200, "renew Consul session")
            ConsulSessionRenewal(sessionId, Instant.now())
        }
    }

    override fun read(key: String): CompletableFuture<ConsulKvEntry?> {
        require(key.isNotBlank()) { "key must not be blank." }
        val request = requestBuilder("/v1/kv/$key")
            .GET()
            .build()

        return sendString(request).thenApply { response ->
            when (response.statusCode()) {
                200 -> parseKvEntry(response.body())
                404 -> null
                else -> throw LeaderElectionException(
                    "Failed to read Consul KV key. status=${response.statusCode()}, body=${response.body()}",
                )
            }
        }
    }

    private fun putBoolean(
        path: String,
        params: Map<String, String>,
        body: String,
        operation: String,
    ): CompletableFuture<Boolean> {
        val request = requestBuilder(path, params)
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build()

        return sendString(request).thenApply { response ->
            response.requireStatus(200, operation)
            response.body().trim().toBooleanStrictOrNull()
                ?: throw LeaderElectionException("Consul $operation response was not boolean. body=${response.body()}")
        }
    }

    private fun sendString(request: HttpRequest): CompletableFuture<HttpResponse<String>> =
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    private fun requestBuilder(path: String, params: Map<String, String> = emptyMap()): HttpRequest.Builder {
        val uri = uri(path, params + datacenterParams())
        val builder = HttpRequest.newBuilder(uri)
            .timeout(endpoint.requestTimeout.toJavaDuration())
            .header("Accept", "application/json")
        endpoint.aclToken?.let { builder.header("X-Consul-Token", it) }
        return builder
    }

    private fun datacenterParams(): Map<String, String> =
        endpoint.datacenter?.let { mapOf("dc" to it) }.orEmpty()

    private fun uri(path: String, params: Map<String, String>): URI {
        val base = endpoint.normalizedBaseUrl.toString().trimEnd('/')
        val query = params.entries.joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }
        val fullPath = "$base/${path.trimStart('/')}"
        return URI.create(if (query.isBlank()) fullPath else "$fullPath?$query")
    }

    private fun parseKvEntry(body: String): ConsulKvEntry? {
        val objectBody = body.trim().removePrefix("[").removeSuffix("]").trim()
        if (objectBody.isBlank()) {
            return null
        }
        val key = stringField(objectBody, "Key") ?: return null
        val value = stringField(objectBody, "Value")?.let {
            String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8)
        }
        val session = stringField(objectBody, "Session")?.takeIf { it.isNotBlank() }?.let(::ConsulSessionId)
        return ConsulKvEntry(
            key = key,
            value = value,
            sessionId = session,
            lockIndex = longField(objectBody, "LockIndex") ?: 0L,
            modifyIndex = longField(objectBody, "ModifyIndex") ?: 0L,
        )
    }

    private fun stringField(json: String, name: String): String? {
        val pattern = Regex("\"${Regex.escape(name)}\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
        return pattern.find(json)?.groupValues?.get(1)?.jsonUnescape()
    }

    private fun longField(json: String, name: String): Long? {
        val pattern = Regex("\"${Regex.escape(name)}\"\\s*:\\s*(\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    companion object {
        private val JSON_STRING_FIELD = Regex("\"ID\"\\s*:\\s*\"([^\"]+)\"")
    }
}

private fun HttpResponse<String>.requireStatus(expected: Int, operation: String) {
    if (statusCode() != expected) {
        throw LeaderElectionException("Failed to $operation. status=${statusCode()}, body=${body()}")
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append('"').append(':')
    append('"').append(value.jsonEscape()).append('"')
}

private fun String.jsonUnescape(): String {
    val result = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\\' && index + 1 < length) {
            index++
            when (val escaped = this[index]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hex = substring(index + 1, index + 5)
                    result.append(hex.toInt(16).toChar())
                    index += 4
                }
                else -> result.append(escaped)
            }
        } else {
            result.append(char)
        }
        index++
    }
    return result.toString()
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
