package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

@Suppress("NonAsciiCharacters")
class BackendErrorClassifierTest {

    // ── CoreBackendErrorClassifier ─────────────────────────────────────────

    @Test
    fun `CoreBackendErrorClassifier - OutOfMemoryError 는 FATAL 을 반환한다`() {
        CoreBackendErrorClassifier.classify(OutOfMemoryError()) shouldBeEqualTo BackendErrorKind.FATAL
    }

    @Test
    fun `CoreBackendErrorClassifier - StackOverflowError 는 FATAL 을 반환한다`() {
        CoreBackendErrorClassifier.classify(StackOverflowError()) shouldBeEqualTo BackendErrorKind.FATAL
    }

    @Test
    fun `CoreBackendErrorClassifier - LinkageError 는 FATAL 을 반환한다`() {
        CoreBackendErrorClassifier.classify(LinkageError("link")) shouldBeEqualTo BackendErrorKind.FATAL
    }

    @Test
    fun `CoreBackendErrorClassifier - SQLTransientException 는 TRANSIENT 을 반환한다`() {
        CoreBackendErrorClassifier.classify(SQLTransientException("tx")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CoreBackendErrorClassifier - SQLRecoverableException 는 TRANSIENT 을 반환한다`() {
        CoreBackendErrorClassifier.classify(SQLRecoverableException("rx")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CoreBackendErrorClassifier - SQLNonTransientException 는 NON_TRANSIENT 을 반환한다`() {
        CoreBackendErrorClassifier.classify(SQLNonTransientException("ntx")) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `CoreBackendErrorClassifier - SocketTimeoutException 는 TRANSIENT 을 반환한다`() {
        CoreBackendErrorClassifier.classify(SocketTimeoutException("timeout")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CoreBackendErrorClassifier - ConnectException 는 TRANSIENT 을 반환한다`() {
        CoreBackendErrorClassifier.classify(ConnectException("refused")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CoreBackendErrorClassifier - 알 수 없는 예외는 null 을 반환한다`() {
        CoreBackendErrorClassifier.classify(RuntimeException("unknown")).shouldBeNull()
    }

    @Test
    fun `CoreBackendErrorClassifier - IllegalStateException 은 null 을 반환한다`() {
        CoreBackendErrorClassifier.classify(IllegalStateException("state")).shouldBeNull()
    }

    // ── CompositeBackendErrorClassifier ───────────────────────────────────

    @Test
    fun `CompositeBackendErrorClassifier - backendSpecific 이 분류하면 그 결과를 반환한다`() {
        val backendSpecific = BackendErrorClassifier { cause ->
            if (cause is IllegalArgumentException) BackendErrorKind.NON_TRANSIENT else null
        }
        val composite = CompositeBackendErrorClassifier(backendSpecific)

        composite.classify(IllegalArgumentException("bad arg")) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `CompositeBackendErrorClassifier - backendSpecific 이 null 반환 시 CoreBackendErrorClassifier 에 위임한다`() {
        val backendSpecific = BackendErrorClassifier { null }  // 항상 분류 불가
        val composite = CompositeBackendErrorClassifier(backendSpecific)

        // CoreBackendErrorClassifier 가 처리: SQLTransientException → TRANSIENT
        composite.classify(SQLTransientException("tx")) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CompositeBackendErrorClassifier - 둘 다 null 이면 NON_TRANSIENT 을 반환한다`() {
        val backendSpecific = BackendErrorClassifier { null }
        val composite = CompositeBackendErrorClassifier(backendSpecific)

        // CoreBackendErrorClassifier 도 null → default NON_TRANSIENT
        composite.classify(RuntimeException("unknown")) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `CompositeBackendErrorClassifier - backendSpecific 이 TRANSIENT 반환 시 FATAL 이어도 override 된다`() {
        // backend-specific 이 먼저 시도되므로 NON_TRANSIENT 반환 시 Core 를 우선함
        val backendSpecific = BackendErrorClassifier { BackendErrorKind.TRANSIENT }
        val composite = CompositeBackendErrorClassifier(backendSpecific)

        // OOM 이어도 backendSpecific 이 TRANSIENT 반환 — backendSpecific 이 core 보다 우선
        composite.classify(OutOfMemoryError()) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }

    @Test
    fun `CompositeBackendErrorClassifier - backendSpecific 이 null 이면 OOM 은 FATAL 이다`() {
        val backendSpecific = BackendErrorClassifier { null }
        val composite = CompositeBackendErrorClassifier(backendSpecific)

        composite.classify(OutOfMemoryError()) shouldBeEqualTo BackendErrorKind.FATAL
    }

    // ── BackendErrorClassifier fun interface ─────────────────────────────

    @Test
    fun `BackendErrorClassifier 는 fun interface 로 람다로 구현할 수 있다`() {
        val classifier: BackendErrorClassifier = BackendErrorClassifier { cause ->
            if (cause is UnsupportedOperationException) BackendErrorKind.NON_TRANSIENT else null
        }

        classifier.classify(UnsupportedOperationException()) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
        classifier.classify(RuntimeException()).shouldBeNull()
    }
}
