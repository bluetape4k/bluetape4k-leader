package io.bluetape4k.leader.audit

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer

/**
 * `LeaderElectionEventPublisher` lifecycle event를 exporter에 연결하는 closeable subscription입니다.
 *
 * callback admission과 `close()`는 하나의 lock으로 선형화합니다. close가 반환된 뒤에는
 * 새 lifecycle submit이 시작되지 않으며, close 직전에 lock을 획득한 callback 하나는
 * crossing allowance로 완료할 수 있습니다. exporter의 drop 결과는 publisher event의
 * 의미나 upstream scope를 변경하지 않습니다.
 */
class LeaderElectionEventExportSubscription private constructor(
    private val exporter: LeaderAuditExporter,
    private val sanitizer: LeaderAuditValueSanitizer,
    private val attributes: Map<String, String>,
) : AutoCloseable {

    private val gate = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private var upstream: AutoCloseable? = null

    /** publisher를 구독하고 subscription handle을 반환합니다. */
    constructor(
        publisher: LeaderElectionEventPublisher,
        scope: CoroutineScope,
        exporter: LeaderAuditExporter,
    ) : this(publisher, scope, exporter, emptyMap(), LeaderAuditValueSanitizer.Default)

    /** 지정한 attributes와 redaction 정책으로 publisher를 구독합니다. */
    constructor(
        publisher: LeaderElectionEventPublisher,
        scope: CoroutineScope,
        exporter: LeaderAuditExporter,
        attributes: Map<String, String>,
        sanitizer: LeaderAuditValueSanitizer,
    ) : this(exporter, sanitizer, attributes.toMap()) {
        upstream = publisher.onEvent(
            scope,
            Consumer { event -> submitIfOpen(event) },
        )
    }

    override fun close() {
        gate.lock()
        try {
            if (!closed.compareAndSet(false, true)) return
            upstream?.close()
            upstream = null
        } finally {
            gate.unlock()
        }
    }

    private fun submitIfOpen(event: LeaderElectionEvent) {
        gate.lock()
        try {
            if (closed.get()) return
            exporter.submit(LeaderAuditExportEvent.Lifecycle.from(event, attributes, sanitizer))
        } finally {
            gate.unlock()
        }
    }
}
