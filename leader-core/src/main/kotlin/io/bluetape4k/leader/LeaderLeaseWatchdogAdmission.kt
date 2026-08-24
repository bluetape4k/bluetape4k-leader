package io.bluetape4k.leader

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * 요청 단위 통합이 backend watchdog tick을 bounded lane으로 제한하도록 연결하는 내부 bridge입니다.
 * provider는 짧은 수명의 reservation을 반환하며 bounded lane이 가득 차면 null을 반환합니다.
 */
object LeaderLeaseWatchdogAdmission {
    private val provider = ThreadLocal<(() -> AutoCloseable?)?>()

    @JvmStatic
    fun current(): (() -> AutoCloseable?)? = provider.get()

    @JvmStatic
    fun <T> withProvider(admission: () -> AutoCloseable?, block: () -> T): T {
        val previous = provider.get()
        provider.set(admission)
        return try {
            block()
        } finally {
            if (previous == null) provider.remove() else provider.set(previous)
        }
    }

    @JvmStatic
    fun <T> withOptionalProvider(admission: (() -> AutoCloseable?)?, block: () -> T): T =
        if (admission == null) block() else withProvider(admission, block)

    /** dispatcher가 바뀌어도 admission provider를 보존하는 coroutine context element입니다. */
    fun asContextElement(admission: () -> AutoCloseable?): ThreadContextElement<(() -> AutoCloseable?)?> =
        provider.asContextElement(admission)
}
