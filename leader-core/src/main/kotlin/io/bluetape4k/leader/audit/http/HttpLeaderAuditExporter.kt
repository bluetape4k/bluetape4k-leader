package io.bluetape4k.leader.audit.http

import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.audit.internal.BoundedLeaderAuditExporter
import java.net.http.HttpClient

/**
 * bounded core exporter에 JDK HTTP/webhook delivery를 연결하는 public exporter입니다.
 *
 * `endpoint`는 [LeaderAuditTrustedHttpsEndpoint.trusted]로 명시적으로 구성해야 하며,
 * HTTP serialization은 [encoder]가 소유합니다. `exportOptions.executor`와
 * `exportOptions.scheduler`는 caller 소유이므로 이 exporter를 먼저 `close`한 다음
 * 외부 실행기를 종료해야 합니다. `ACCEPTED`는 전달 성공이 아닌 admission입니다.
 */
class HttpLeaderAuditExporter(
    client: HttpClient,
    endpoint: LeaderAuditTrustedHttpsEndpoint,
    headers: Map<String, String>,
    encoder: LeaderAuditPayloadEncoder,
    exportOptions: LeaderAuditExportOptions,
    httpOptions: LeaderAuditHttpOptions,
) : LeaderAuditExporter {

    private val delegate: LeaderAuditExporter = BoundedLeaderAuditExporter(
        delivery = HttpLeaderAuditDelivery(
            client = client,
            endpoint = endpoint,
            headers = headers,
            encoder = encoder,
            exportOptions = exportOptions,
            httpOptions = httpOptions,
        ),
        options = exportOptions,
    )

    /** bounded pipeline에 event를 제출합니다. */
    override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult = delegate.submit(event)

    /** delivery/admission observation을 등록합니다. */
    override fun observe(observer: LeaderAuditExportObserver): AutoCloseable = delegate.observe(observer)

    /** 현재 bounded exporter snapshot을 반환합니다. */
    override fun snapshot(): LeaderAuditExportSnapshot = delegate.snapshot()

    /** queued/retry/in-flight work를 취소하고 exporter를 멱등적으로 닫습니다. */
    override fun close() = delegate.close()
}
