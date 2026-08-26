@file:Suppress("SwallowedException", "TooGenericExceptionCaught")

package io.bluetape4k.leader.audit.http

import io.bluetape4k.leader.audit.LeaderAuditDelivery
import io.bluetape4k.leader.audit.LeaderAuditDeliveryResult
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * JDK `HttpClient` 기반의 one-shot audit delivery입니다.
 *
 * 응답 body는 보존하지 않고, status 408/429/5xx와 비동기 I/O 실패만 retryable로
 * 분류합니다. 반환 future 취소는 underlying request 취소로 전달됩니다.
 */
internal class HttpLeaderAuditDelivery(
    private val client: HttpClient,
    private val endpoint: LeaderAuditTrustedHttpsEndpoint,
    headers: Map<String, String>,
    private val encoder: LeaderAuditPayloadEncoder,
    exportOptions: LeaderAuditExportOptions,
    private val httpOptions: LeaderAuditHttpOptions,
) : LeaderAuditDelivery {

    private val attemptTimeout: Duration = exportOptions.attemptTimeout
    private val headers: Map<String, String> = normalizeHeaders(headers)

    init {
        require(client.followRedirects() == HttpClient.Redirect.NEVER) {
            "HttpClient.followRedirects must be NEVER"
        }
    }

    override fun deliver(event: LeaderAuditExportEvent): CompletableFuture<LeaderAuditDeliveryResult> =
        encodeOrNull(event)?.let { payload ->
            val body = payload.body()
            if (body.size > httpOptions.maxPayloadBytes) {
                HttpLeaderAuditDeliveryLogger.log.warn {
                    "Leader audit payload exceeded configured HTTP bound; delivery is terminal"
                }
                terminalFailure()
            } else {
                deliverPayload(payload, body)
            }
        } ?: terminalFailure()

    private fun encodeOrNull(event: LeaderAuditExportEvent): LeaderAuditHttpPayload? = try {
        encoder.encode(event)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HttpLeaderAuditDeliveryLogger.log.warn {
            "Leader audit payload encoder failed; delivery is terminal"
        }
        null
    }

    private fun deliverPayload(
        payload: LeaderAuditHttpPayload,
        body: ByteArray,
    ): CompletableFuture<LeaderAuditDeliveryResult> {
        val request = buildRequestOrNull(payload, body)
        return request?.let(::sendRequest) ?: terminalFailure()
    }

    private fun buildRequestOrNull(payload: LeaderAuditHttpPayload, body: ByteArray): HttpRequest? = try {
        buildRequest(payload, body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HttpLeaderAuditDeliveryLogger.log.warn {
            "Leader audit HTTP request validation failed; delivery is terminal"
        }
        null
    }

    private fun sendRequest(request: HttpRequest): CompletableFuture<LeaderAuditDeliveryResult> {
        val requestFuture = try {
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HttpLeaderAuditDeliveryLogger.log.warn {
                "Leader audit HTTP request failed before enqueue; delivery classification applied"
            }
            return CompletableFuture.completedFuture(classifySynchronousFailure(e))
        }

        val result = CompletableFuture<LeaderAuditDeliveryResult>()
        result.whenComplete { _, failure ->
            if (failure is CancellationException || result.isCancelled) {
                requestFuture.cancel(true)
            }
        }
        requestFuture.whenComplete { response, failure ->
            if (failure != null) {
                val cause = failure.unwrapCompletionFailure()
                if (cause is CancellationException) {
                    result.cancel(false)
                } else if (cause is Error) {
                    result.completeExceptionally(cause)
                } else {
                    val classification = classifyFailure(cause)
                    if (classification == LeaderAuditDeliveryResult.RETRYABLE_FAILURE) {
                        HttpLeaderAuditDeliveryLogger.log.warn {
                            "Leader audit HTTP I/O failure; delivery is retryable"
                        }
                    } else {
                        HttpLeaderAuditDeliveryLogger.log.warn {
                            "Leader audit HTTP failure; delivery is terminal"
                        }
                    }
                    result.complete(classification)
                }
            } else {
                val classification = classifyStatus(response.statusCode())
                if (classification != LeaderAuditDeliveryResult.SUCCESS) {
                    HttpLeaderAuditDeliveryLogger.log.warn {
                        "Leader audit HTTP response was classified as $classification"
                    }
                }
                result.complete(classification)
            }
        }
        return result
    }

    private fun terminalFailure(): CompletableFuture<LeaderAuditDeliveryResult> =
        CompletableFuture.completedFuture(LeaderAuditDeliveryResult.TERMINAL_FAILURE)

    private fun buildRequest(payload: LeaderAuditHttpPayload, body: ByteArray): HttpRequest {
        val builder = HttpRequest.newBuilder(endpoint.uri)
            .timeout(attemptTimeout)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        builder.setHeader("Content-Type", payload.contentType)
        return builder.build()
    }

    private fun classifyStatus(status: Int): LeaderAuditDeliveryResult = when {
        status in HTTP_SUCCESS_STATUSES -> LeaderAuditDeliveryResult.SUCCESS
        status == HTTP_REQUEST_TIMEOUT_STATUS ||
            status == HTTP_TOO_MANY_REQUESTS_STATUS ||
            status in HTTP_SERVER_ERROR_STATUSES -> LeaderAuditDeliveryResult.RETRYABLE_FAILURE
        else -> LeaderAuditDeliveryResult.TERMINAL_FAILURE
    }

    private fun classifySynchronousFailure(failure: Exception): LeaderAuditDeliveryResult {
        return classifyFailure(failure)
    }

    private fun classifyFailure(failure: Throwable): LeaderAuditDeliveryResult {
        val cause = failure.unwrapCompletionFailure()
        return if (cause is java.io.IOException || cause is java.util.concurrent.TimeoutException) {
            LeaderAuditDeliveryResult.RETRYABLE_FAILURE
        } else {
            LeaderAuditDeliveryResult.TERMINAL_FAILURE
        }
    }

    private object HttpLeaderAuditDeliveryLogger : KLogging()
}

private val HTTP_ALLOWED_HEADERS = setOf("content-type", "authorization")
private val HTTP_FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding")
private val HTTP_SUCCESS_STATUSES = 200..299
private val HTTP_SERVER_ERROR_STATUSES = 500..599
private const val HTTP_REQUEST_TIMEOUT_STATUS: Int = 408
private const val HTTP_TOO_MANY_REQUESTS_STATUS: Int = 429
private const val HTTP_CONTROL_CHARACTER_MIN_CODE: Int = 0x20
private const val HTTP_DELETE_CHARACTER_CODE: Int = 0x7f

private fun normalizeHeaders(input: Map<String, String>): Map<String, String> {
    val normalized = LinkedHashMap<String, String>(input.size)
    input.forEach { (rawName, rawValue) ->
        val name = rawName.requireNotBlank("header name")
        require(!name.containsHttpControlCharacter()) {
            "header name must not contain control characters"
        }
        val lowerName = name.lowercase(Locale.ROOT)
        require(lowerName !in HTTP_FORBIDDEN_HEADERS) {
            "header is forbidden: $name"
        }
        require(lowerName in HTTP_ALLOWED_HEADERS) {
            requireHeaderAllowed(name)
        }
        val value = rawValue.requireNotBlank("header value")
        require(!value.containsHttpControlCharacter()) {
            "header value must not contain control characters"
        }
        val canonical = when (lowerName) {
            "content-type" -> "Content-Type"
            "authorization" -> "Authorization"
            else -> error("unreachable header allow-list branch")
        }
        require(normalized.put(canonical, value) == null) {
            "duplicate header name: $canonical"
        }
    }
    return normalized.toMap()
}

private fun requireHeaderAllowed(name: String): Nothing =
    throw IllegalArgumentException("header is not allow-listed: $name")

private fun String.containsHttpControlCharacter(): Boolean = any {
    it.code < HTTP_CONTROL_CHARACTER_MIN_CODE || it.code == HTTP_DELETE_CHARACTER_CODE
}

private fun Throwable.unwrapCompletionFailure(): Throwable {
    var current = this
    while (current is CompletionException || current is ExecutionException) {
        current = current.cause ?: break
    }
    return current
}
