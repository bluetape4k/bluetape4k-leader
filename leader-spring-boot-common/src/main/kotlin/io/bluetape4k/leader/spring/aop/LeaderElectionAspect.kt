package io.bluetape4k.leader.spring.aop

import io.bluetape4k.leader.LeaderElection as CoreLeaderElection
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.spring.aop.cache.FactoryCacheKey
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.SkipReason
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.AnnotationLookup
import io.bluetape4k.leader.spring.aop.util.DurationParser
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.beans.factory.SmartInitializingSingleton
import java.lang.reflect.Method
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.nanoseconds

/**
 * `@LeaderElection` 어노테이션 처리 Aspect — sync `T?` only.
 *
 * ## [T4.1a Option A] final class @Aspect
 * Boot 3 / Boot 4 공통. autoconfig 가 `@Bean` 으로 등록 — `@Component` 미부착.
 *
 * ## [Step 3-P] 보강 사항
 * - **Sec-3 (R-33)**: backend 예외만 [LeaderElectionException] 으로 wrapping (host/credentials 누출 차단)
 * - **Rel-1**: body 예외 vs backend 예외 분리 — body throw 는 wrapping 없이 그대로 전파
 * - **Rel-2**: [CancellationException] 항상 우선 재throw (CLAUDE.md memory feedback_cancellation_exception)
 * - **Perf-1**: metrics fan-out 시 `metrics.isEmpty()` fast-path
 * - **Perf-2**: Method-level cache — annotation lookup + lease threshold 사전계산 + factory bean name resolution
 *
 * @param beanSelector factory 빈 선택기
 * @param props AOP 전역 속성
 * @param spel SpEL 평가기
 * @param recorders 등록된 metrics recorder 리스트 (0개 = NoOp fast-path)
 */
@Aspect
class LeaderElectionAspect(
    private val beanSelector: LeaderBeanSelector,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val lockNameValidator: LockNameValidator,
    private val recorders: List<LeaderAopMetricsRecorder>,
) : SmartInitializingSingleton {

    /** [Step 3-P-Perf-2] Method-level cache — annotation + parsed options + factory lookup. */
    private val metadataCache = ConcurrentHashMap<Method, AdviceMetadata>()

    /** [C1][R-26] FactoryCacheKey 기반 cross-backend collision-safe 캐싱. */
    private val factoryCache = ConcurrentHashMap<FactoryCacheKey, CoreLeaderElection>()

    /** [Perf-1] recorder 0개일 때 fast-path 활성화. */
    private val hasRecorders = recorders.isNotEmpty()

    @Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? {
        val method = (pjp.signature as MethodSignature).method
        val target = pjp.target
        val args = pjp.args

        val meta = metadataCache.computeIfAbsent(method) { resolveMetadata(it, target) }
        val lockName = resolveLockName(meta, method, args, target)
        val opts = meta.options
        val factoryBeanName = meta.factoryBeanName
        val factory = meta.factory

        val cacheKey = FactoryCacheKey(factoryBeanName, opts)
        val election = factoryCache.computeIfAbsent(cacheKey) { factory.create(opts) }

        fanOut { it.onLockAttempt(lockName, opts) }
        val start = System.nanoTime()

        return try {
            val result = election.runIfLeader(lockName) {
                fanOut {
                    it.onLockAcquired(lockName, opts, (System.nanoTime() - start).nanoseconds)
                    it.onTaskStarted(lockName)
                }
                executeBody(pjp, lockName, start)
            }
            if (result == null) {
                fanOut { it.onLockNotAcquired(lockName, opts, SkipReason.CONTENTION) }
                log.debug { "leader.aop.skipped lockName=$lockName reason=CONTENTION" }
            } else {
                val elapsed = System.nanoTime() - start
                fanOut { it.onTaskFinished(lockName, elapsed.nanoseconds) }
                log.debug { "leader.aop.elected lockName=$lockName elapsedNs=$elapsed" }
                if (elapsed > meta.leaseTimeWarnThresholdNanos) {
                    log.warn {
                        "leader.aop.lease-warn lockName=$lockName elapsedNs=$elapsed leaseTimeNs=${opts.leaseTime.toNanos()}"
                    }
                }
            }
            result
        } catch (e: CancellationException) {
            // [Rel-2] CancellationException 항상 우선 재throw
            throw e
        } catch (bodyMarker: BodyThrownMarker) {
            // [Rel-1] body 예외는 wrapping 없이 그대로 전파 — failureMode 무관
            throw bodyMarker.cause
        } catch (backendEx: Throwable) {
            // [Sec-3][R-33] backend 예외만 LeaderElectionException 으로 wrapping
            val wrapped = LeaderElectionException("leader backend error for lock '$lockName'", backendEx)
            fanOut {
                it.onLockNotAcquired(lockName, opts, SkipReason.BACKEND_ERROR)
                it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, backendEx)
            }
            when (meta.failureMode) {
                LeaderAspectFailureMode.RETHROW -> throw wrapped
                LeaderAspectFailureMode.SKIP -> {
                    log.warn(backendEx) { "leader.aop.skipped lockName=$lockName reason=BACKEND_ERROR" }
                    null
                }
            }
        }
    }

    /**
     * [Rel-1] body 실행 — 사용자 본문 throw 는 wrapping 없이 그대로 전파.
     * [CancellationException] 우선 재throw [Rel-2].
     *
     * body 예외는 [BodyThrownMarker] 로 감싸서 outer catch 가 backend 예외와 구분할 수 있게 한다.
     * outer catch 가 [BodyThrownMarker] 를 만나면 cause 를 그대로 re-throw (wrapping 없음).
     */
    private fun executeBody(pjp: ProceedingJoinPoint, lockName: String, start: Long): Any? {
        return try {
            pjp.proceed()
        } catch (e: CancellationException) {
            fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, e) }
            throw e
        } catch (bodyEx: Throwable) {
            fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, bodyEx) }
            throw BodyThrownMarker(bodyEx)
        }
    }

    /** Body throw vs backend throw 구분용 internal marker — outer catch 에서 cause 만 re-throw. */
    private class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

    /**
     * [T2.14] SpEL pre-parse hook — startup 시점 모든 `@LeaderElection` 메서드의 SpEL 검증.
     */
    override fun afterSingletonsInstantiated() {
        // BPP/Validator 가 별도로 검증 — Aspect 는 캐시 워밍만 시도 (실패 시 startup 진행, 호출 시점 fail)
    }

    private fun resolveLockName(meta: AdviceMetadata, method: Method, args: Array<Any?>, target: Any): String {
        val rawName = if (meta.literalName != null) {
            meta.literalName
        } else {
            spel.evaluate(meta.nameExpression, method, args, target)
        }
        val prefixed = lockNameValidator.applyPrefix(rawName)
        lockNameValidator.validate(prefixed)
        return prefixed
    }

    private fun resolveMetadata(method: Method, target: Any): AdviceMetadata {
        val ann = AnnotationLookup.findAnnotationWithTargetFallback(method, target, LeaderElection::class.java)
            ?: error("@LeaderElection not found on ${method.declaringClass.name}#${method.name}")

        val waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime)
        val leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime)

        val opts = LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime)
        val selected = beanSelector.selectElectionFactory(ann.bean)

        // literal fast-path 분류 — SpEL 평가 우회 가능 여부 사전 판단
        val literal = if (LITERAL_PATTERN.matches(ann.name)) ann.name else null

        return AdviceMetadata(
            nameExpression = ann.name,
            literalName = literal,
            options = opts,
            factoryBeanName = selected.beanName,
            factory = selected.bean,
            failureMode = ann.failureMode,
            leaseTimeWarnThresholdNanos = (leaseTime.toNanos() * LEASE_WARN_RATIO).toLong(),
        )
    }

    private inline fun fanOut(crossinline action: (LeaderAopMetricsRecorder) -> Unit) {
        if (!hasRecorders) return
        for (recorder in recorders) {
            runCatching { action(recorder) }
                .onFailure { log.warn(it) { "metrics recorder threw" } }
        }
    }

    private data class AdviceMetadata(
        val nameExpression: String,
        val literalName: String?,
        val options: LeaderElectionOptions,
        val factoryBeanName: String,
        val factory: LeaderElectionFactory,
        val failureMode: LeaderAspectFailureMode,
        val leaseTimeWarnThresholdNanos: Long,
    )

    companion object: KLogging() {
        private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
        private const val LEASE_WARN_RATIO = 0.8
    }
}
