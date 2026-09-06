package io.bluetape4k.leader.testcontainers

import com.github.dockerjava.api.model.ExposedPort
import org.testcontainers.containers.ContainerLaunchException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy
import org.testcontainers.containers.wait.strategy.WaitStrategy
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget
import org.testcontainers.utility.DockerImageName
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

/** Readiness 실패가 발생한 인프라 경계입니다. */
internal enum class ReadinessFailureBoundary {
    HOST_FORWARDING,
    CONTAINER_SERVICE,
    PORT_MAPPING,
    UNKNOWN,
}

/** 개별 readiness probe의 결과 상태입니다. */
internal enum class ReadinessProbeStatus {
    SUCCESS,
    FAILURE,
    UNAVAILABLE,
}

/** 진단할 container endpoint를 같은 타입의 원시 인자 대신 하나의 값으로 묶습니다. */
class ReadinessEndpoint(
    val name: String,
    val containerPort: Int,
    val path: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(containerPort in 1..65535) { "containerPort must be between 1 and 65535" }
        require(path.startsWith('/')) { "path must start with '/'" }
        require(path.none { it.isWhitespace() || it == '\'' }) { "path must not contain whitespace or single quotes" }
    }
}

/** 외부 로그로 노출할 세부 내용을 한 줄과 256자로 제한한 probe 결과입니다. */
internal class ReadinessProbeObservation private constructor(
    val status: ReadinessProbeStatus,
    val detail: String,
) {
    companion object {
        fun success(detail: String): ReadinessProbeObservation =
            ReadinessProbeObservation(ReadinessProbeStatus.SUCCESS, detail.toDiagnosticDetail())

        fun failure(detail: String): ReadinessProbeObservation =
            ReadinessProbeObservation(ReadinessProbeStatus.FAILURE, detail.toDiagnosticDetail())

        fun unavailable(detail: String): ReadinessProbeObservation =
            ReadinessProbeObservation(ReadinessProbeStatus.UNAVAILABLE, detail.toDiagnosticDetail())
    }
}

/** Internal, host, Docker mapping 관찰값을 하나의 안정적인 진단으로 합칩니다. */
internal class ReadinessBoundaryDiagnostic(
    val endpoint: ReadinessEndpoint,
    val internal: ReadinessProbeObservation,
    val host: ReadinessProbeObservation,
    val mapping: ReadinessProbeObservation,
    containerState: String,
) {
    val containerState: String = containerState.toDiagnosticDetail()

    val boundary: ReadinessFailureBoundary = when {
        mapping.status == ReadinessProbeStatus.FAILURE -> ReadinessFailureBoundary.PORT_MAPPING
        internal.status == ReadinessProbeStatus.FAILURE -> ReadinessFailureBoundary.CONTAINER_SERVICE
        internal.status == ReadinessProbeStatus.SUCCESS &&
            host.status == ReadinessProbeStatus.FAILURE &&
            mapping.status == ReadinessProbeStatus.SUCCESS -> ReadinessFailureBoundary.HOST_FORWARDING
        else -> ReadinessFailureBoundary.UNKNOWN
    }

    fun render(): String =
        "endpoint=${endpoint.name}:${endpoint.containerPort}${endpoint.path}, " +
            "boundary=$boundary, " +
            "internal=${internal.status}(${internal.detail}), " +
            "host=${host.status}(${host.detail}), " +
            "mapping=${mapping.status}(${mapping.detail}), " +
            "containerState=$containerState"
}

/** 실패한 wait 대상에서 readiness 경계 증거를 수집합니다. */
internal fun interface ReadinessBoundaryDiagnosticCollector {
    fun collect(target: WaitStrategyTarget, endpoint: ReadinessEndpoint): ReadinessBoundaryDiagnostic
}

/** 기존 wait의 정상 경로를 유지하고 실패 직전에 경계 진단을 덧붙입니다. */
internal class ReadinessBoundaryWaitStrategy(
    private val delegate: WaitStrategy,
    private val endpoint: ReadinessEndpoint,
    private val collector: ReadinessBoundaryDiagnosticCollector = DockerReadinessBoundaryDiagnosticCollector,
) : WaitStrategy {

    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) {
        try {
            delegate.waitUntilReady(waitStrategyTarget)
        } catch (failure: Exception) {
            val diagnostic = collectDiagnostic(waitStrategyTarget)
            throw ContainerLaunchException("Readiness boundary diagnostic: ${diagnostic.render()}", failure)
        }
    }

    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy {
        delegate.withStartupTimeout(startupTimeout)
        return this
    }

    private fun collectDiagnostic(target: WaitStrategyTarget): ReadinessBoundaryDiagnostic =
        try {
            collector.collect(target, endpoint)
        } catch (collectorFailure: Exception) {
            val detail = "collector unavailable: ${collectorFailure.toDiagnosticDetail()}"
            ReadinessBoundaryDiagnostic(
                endpoint = endpoint,
                internal = ReadinessProbeObservation.unavailable(detail),
                host = ReadinessProbeObservation.unavailable(detail),
                mapping = ReadinessProbeObservation.unavailable(detail),
                containerState = detail,
            )
        }
}

/** 지정한 endpoint에 기존 HTTP wait와 실패 진단을 함께 적용합니다. */
fun readinessBoundaryWaitStrategy(
    endpoint: ReadinessEndpoint,
    delegate: WaitStrategy = org.testcontainers.containers.wait.strategy.Wait
        .forHttp(endpoint.path)
        .forPort(endpoint.containerPort),
): WaitStrategy =
    ReadinessBoundaryWaitStrategy(
        delegate = delegate,
        endpoint = endpoint,
    )

private object DockerReadinessBoundaryDiagnosticCollector : ReadinessBoundaryDiagnosticCollector {

    override fun collect(
        target: WaitStrategyTarget,
        endpoint: ReadinessEndpoint,
    ): ReadinessBoundaryDiagnostic =
        ReadinessBoundaryDiagnostic(
            endpoint = endpoint,
            internal = probeInternal(target, endpoint),
            host = probeHost(target, endpoint),
            mapping = inspectMapping(target, endpoint),
            containerState = inspectContainerState(target),
        )

    private fun probeInternal(
        target: WaitStrategyTarget,
        endpoint: ReadinessEndpoint,
    ): ReadinessProbeObservation {
        val marker = "__BLUETAPE_READINESS_PROBE_EXIT__="
        val url = "http://127.0.0.1:${endpoint.containerPort}${endpoint.path}"
        val command = "wget -qO- -T 2 '$url' || { code=\$?; echo; echo '$marker'\$code; exit 0; }"
        val helper = AlpineHttpProbeContainer()
            .withNetworkMode("container:${target.containerId}")
            .withCommand("sh", "-c", command)
            .withStartupCheckStrategy(OneShotStartupCheckStrategy().withTimeout(INTERNAL_PROBE_TIMEOUT))
            .waitingFor(NoOpWaitStrategy)

        return try {
            helper.start()
            val output = helper.logs.toDiagnosticDetail()
            if (marker in output) {
                ReadinessProbeObservation.failure(output)
            } else {
                ReadinessProbeObservation.success(output.ifBlank { "HTTP response received" })
            }
        } catch (probeFailure: Exception) {
            ReadinessProbeObservation.unavailable(probeFailure.toDiagnosticDetail())
        } finally {
            helper.stop()
        }
    }

    private fun probeHost(
        target: WaitStrategyTarget,
        endpoint: ReadinessEndpoint,
    ): ReadinessProbeObservation =
        try {
            val mappedPort = target.getMappedPort(endpoint.containerPort)
            val connection = URI("http", null, target.host, mappedPort, endpoint.path, null, null)
                .toURL()
                .openConnection() as HttpURLConnection

            try {
                connection.connectTimeout = HOST_PROBE_TIMEOUT_MILLIS
                connection.readTimeout = HOST_PROBE_TIMEOUT_MILLIS
                connection.requestMethod = "GET"
                val status = connection.responseCode
                val body = (if (status in 200..399) connection.inputStream else connection.errorStream)
                    ?.reader()
                    ?.use { it.readDiagnosticDetail() }
                    .orEmpty()
                if (status == HttpURLConnection.HTTP_OK) {
                    ReadinessProbeObservation.success("HTTP $status $body")
                } else {
                    ReadinessProbeObservation.failure("HTTP $status $body")
                }
            } finally {
                connection.disconnect()
            }
        } catch (probeFailure: Exception) {
            ReadinessProbeObservation.failure(probeFailure.toDiagnosticDetail())
        }

    private fun inspectMapping(
        target: WaitStrategyTarget,
        endpoint: ReadinessEndpoint,
    ): ReadinessProbeObservation =
        try {
            val exposedPort = ExposedPort.tcp(endpoint.containerPort)
            val bindings = target.currentContainerInfo.networkSettings?.ports?.bindings?.get(exposedPort)
            if (bindings.isNullOrEmpty()) {
                ReadinessProbeObservation.failure("$exposedPort is not mapped")
            } else {
                val rendered = bindings.joinToString(separator = ",") { binding ->
                    "${binding.hostIp.orEmpty().ifBlank { "*" }}:${binding.hostPortSpec.orEmpty()}"
                }
                ReadinessProbeObservation.success("$exposedPort -> $rendered")
            }
        } catch (inspectFailure: Exception) {
            ReadinessProbeObservation.unavailable(inspectFailure.toDiagnosticDetail())
        }

    private fun inspectContainerState(target: WaitStrategyTarget): String =
        try {
            val state = target.currentContainerInfo.state
            "status=${state?.status},running=${state?.running},exitCode=${state?.exitCodeLong}"
        } catch (inspectFailure: Exception) {
            "unavailable: ${inspectFailure.toDiagnosticDetail()}"
        }
}

private class AlpineHttpProbeContainer : GenericContainer<AlpineHttpProbeContainer>(ALPINE_PROBE_IMAGE)

private object NoOpWaitStrategy : WaitStrategy {
    override fun waitUntilReady(waitStrategyTarget: WaitStrategyTarget) = Unit

    override fun withStartupTimeout(startupTimeout: Duration): WaitStrategy = this
}

private fun Throwable.toDiagnosticDetail(): String =
    "${javaClass.simpleName}: ${message.orEmpty()}".toDiagnosticDetail()

private fun String.toDiagnosticDetail(): String =
    lineSequence()
        .joinToString(separator = " ") { it.trim() }
        .trim()
        .ifBlank { "none" }
        .take(MAX_DIAGNOSTIC_DETAIL_LENGTH)

private fun java.io.Reader.readDiagnosticDetail(): String {
    val buffer = CharArray(MAX_DIAGNOSTIC_DETAIL_LENGTH + 1)
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) break
        offset += count
    }
    return String(buffer, 0, offset).toDiagnosticDetail()
}

private const val MAX_DIAGNOSTIC_DETAIL_LENGTH = 256
private const val HOST_PROBE_TIMEOUT_MILLIS = 2_000
private val INTERNAL_PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
private val ALPINE_PROBE_IMAGE: DockerImageName = DockerImageName.parse(
    "alpine@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc",
)
