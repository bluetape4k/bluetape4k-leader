package io.bluetape4k.leader.spring.route.webflux

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteLeaseRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.LeaderRouteEvaluation
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectFramework
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectPolicy
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import io.bluetape4k.leader.spring.route.LeaseObservationCode
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `LeaderWebFluxRouteGuardFactory`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property runtime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property properties Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property evaluationScheduler blocking authority 평가를 실행할 scheduler입니다.
 */
@Suppress("TooManyFunctions")
class LeaderWebFluxRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
    private val evaluationScheduler: Scheduler = Schedulers.boundedElastic(),
    private val redirectPolicy: LeaderRouteRedirectPolicy? =
        properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy),
    private val leaseRuntime: LeaderRouteLeaseRuntime? = null,
) {

    internal constructor(
        runtime: LeaderRouteAuthorityRuntime,
        properties: LeaderRouteGuardProperties,
        redirectPolicy: LeaderRouteRedirectPolicy?,
    ) : this(runtime, properties, Schedulers.boundedElastic(), redirectPolicy)

    internal constructor(
        runtime: LeaderRouteAuthorityRuntime,
        properties: LeaderRouteGuardProperties,
    ) : this(
        runtime,
        properties,
        Schedulers.boundedElastic(),
        properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy),
    )

    /**
     * `filter` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun filter(slot: LeaderSlot): WebFilter = filterInternal(slot, null, null)

    /** resolver를 route owner가 직접 제공하는 additive overload입니다. */
    fun filter(slot: LeaderSlot, resolver: LeaderRouteRedirectResolver): WebFilter =
        filterInternal(slot, resolver, null)

    /** raw metadata provider를 포함한 redirect route filter입니다. */
    fun filter(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange>?,
    ): WebFilter = filterInternal(slot, resolver, metadataProvider)

    private fun filterInternal(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver?,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange>?,
    ): WebFilter = WebFilter { exchange, chain ->
        if (properties.authorityMode == io.bluetape4k.leader.spring.properties.LeaderRouteAuthorityMode.LEASE) {
            return@WebFilter leaseFilter(slot, exchange, chain)
        }
        Mono.fromCallable {
            val evaluation = runtime.evaluateSnapshot(slot)
            val metadata = if (
                evaluation.decision == LeaderRouteDecision.NotLeader &&
                resolver != null &&
                redirectPolicy != null
            ) {
                metadataProvider?.capture(exchange)
            } else {
                null
            }
            val location = if (resolver != null && redirectPolicy != null) {
                redirectPolicy.redirect(slot, evaluation, resolver, metadata, LeaderRouteRedirectFramework.WEBFLUX)
            } else {
                null
            }
            RedirectResult(evaluation, location)
        }
            .subscribeOn(evaluationScheduler)
            .onErrorResume { failure ->
                val unwrapped = Exceptions.unwrap(failure)
                when (unwrapped) {
                    is CancellationException -> Mono.error(unwrapped)
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        Mono.error(unwrapped)
                    }
                    is Error -> Mono.error(unwrapped)
                    else -> Mono.just(
                        RedirectResult(
                            LeaderRouteEvaluation(LeaderRouteDecision.Unavailable, null, java.time.Instant.now()),
                            null,
                        ),
                    )
                }
            }
            .flatMap { result ->
                if (result.evaluation.decision == LeaderRouteDecision.Allowed) {
                    chain.filter(exchange)
                } else if (result.location != null) {
                    exchange.response.statusCode = HttpStatusCode.valueOf(TEMPORARY_REDIRECT_STATUS)
                    exchange.response.headers.location = result.location
                    exchange.response.setComplete()
                } else {
                    exchange.response.statusCode = HttpStatusCode.valueOf(properties.rejectionStatus.value)
                    exchange.response.setComplete()
                }
            }
    }

    @Suppress("ReturnCount")
    private fun leaseFilter(
        slot: LeaderSlot,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
    ): Mono<Void> {
        val activeRuntime = leaseRuntime
        val requestedFingerprint = fingerprint(slot)
        existingLeaseDecision(activeRuntime, requestedFingerprint, exchange, chain)?.let { return it }
        val acquireMarker = LeaseAcquireMarker(requestedFingerprint)
        if (!installAcquireMarker(exchange, acquireMarker)) {
            return staleRejection(activeRuntime, exchange)
        }
        val resource = acquireResource(activeRuntime, slot, exchange, acquireMarker)
        return usingLeaseResource(
            resource = resource,
            exchange = exchange,
            chain = chain,
            runtime = activeRuntime,
            marker = acquireMarker,
            fingerprint = requestedFingerprint,
        )
    }

    private fun existingLeaseDecision(
        runtime: LeaderRouteLeaseRuntime?,
        requestedFingerprint: Int,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
    ): Mono<Void>? {
        val existing = synchronized(exchange.attributes) { exchange.attributes[LEASE_HANDLE_ATTRIBUTE] }
        return when (existing) {
            null -> null
            is LeaseExchangeHolder -> if (
                existing.fingerprint == requestedFingerprint && retainHolderUse(exchange, existing)
            ) {
                withRetainedHolder(exchange, chain, existing)
            } else {
                staleRejection(runtime, exchange)
            }
            is LeaseAcquireMarker -> if (existing.fingerprint == requestedFingerprint) {
                awaitHolder(existing)
                    .flatMap { holder ->
                        if (!retainHolderUse(exchange, holder)) {
                            Mono.empty()
                        } else {
                            withRetainedHolder(exchange, chain, holder)
                        }
                    }
                    .switchIfEmpty(Mono.defer { reject(exchange) })
            } else {
                staleRejection(runtime, exchange)
            }
            else -> staleRejection(runtime, exchange)
        }
    }

    private fun installAcquireMarker(
        exchange: ServerWebExchange,
        marker: LeaseAcquireMarker,
    ): Boolean = synchronized(exchange.attributes) {
        if (exchange.attributes.containsKey(LEASE_HANDLE_ATTRIBUTE)) {
            false
        } else {
            exchange.attributes[LEASE_HANDLE_ATTRIBUTE] = marker
            true
        }
    }

    private fun awaitHolder(marker: LeaseAcquireMarker): Mono<LeaseExchangeHolder> = Mono.create { sink ->
        marker.published.whenComplete { holder, failure ->
            when {
                failure != null -> sink.error(failure)
                holder != null -> sink.success(holder)
                else -> sink.success()
            }
        }
    }

    private fun withRetainedHolder(
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
        holder: LeaseExchangeHolder,
    ): Mono<Void> = Mono.usingWhen(
        Mono.just(holder),
        { chain.filter(exchange) },
        { releaseHolderUse(exchange, holder.token) },
        { _, _ -> releaseHolderUse(exchange, holder.token) },
        { releaseHolderUse(exchange, holder.token) },
    )

    @Suppress("ThrowsCount")
    private fun acquireResource(
        runtime: LeaderRouteLeaseRuntime?,
        slot: LeaderSlot,
        exchange: ServerWebExchange,
        marker: LeaseAcquireMarker,
    ): Mono<LeaseResource> {
        val resource: Mono<LeaseResource> = if (runtime == null || !runtime.acceptsAcquire()) {
            Mono.just<LeaseResource>(LeaseResource.Rejected)
        } else if (runtime.suspendAcquirer != null) {
            mono<LeaseResource> {
                var acquired: SuspendLeaderLeaseHandle? = null
                try {
                    acquired = runtime.tryAcquireSuspend(slot)
                    currentCoroutineContext().ensureActive()
                    acquired?.let(LeaseResource::Suspend) ?: LeaseResource.Rejected
                } catch (cancelled: CancellationException) {
                    acquired?.release()
                    throw cancelled
                }
            }
        } else {
            Mono.fromCallable<LeaseResource> {
                var acquired: LeaderLeaseHandle? = null
                try {
                    acquired = runtime.tryAcquire(slot)
                    if (Thread.currentThread().isInterrupted) {
                        acquired?.release()
                        throw CancellationException("lease acquire cancelled")
                    }
                    acquired?.let(LeaseResource::Blocking) ?: LeaseResource.Rejected
                } catch (cancelled: CancellationException) {
                    acquired?.release()
                    throw cancelled
                }
            }.subscribeOn(evaluationScheduler)
        }
        val pending = AtomicReference<LeaseResource?>()
        return resource.doFinally { signal ->
            if (signal != SignalType.ON_COMPLETE) {
                pending.getAndSet(null)?.let { releaseResource(exchange, it).subscribe() }
                clearAcquireMarker(exchange, marker)
            }
        }.doOnNext { acquired ->
            pending.set(acquired)
        }
    }

    private fun usingLeaseResource(
        resource: Mono<LeaseResource>,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
        runtime: LeaderRouteLeaseRuntime?,
        marker: LeaseAcquireMarker,
        fingerprint: Int,
    ): Mono<Void> = Mono.usingWhen(
        resource,
        { acquired -> acquiredResource(acquired, exchange, chain, runtime, marker, fingerprint) },
        { acquired -> releaseResource(exchange, acquired) },
        { acquired, _ -> releaseResource(exchange, acquired) },
        { acquired -> releaseResource(exchange, acquired) },
    )

    private fun acquiredResource(
        resource: LeaseResource,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
        runtime: LeaderRouteLeaseRuntime?,
        marker: LeaseAcquireMarker,
        fingerprint: Int,
    ): Mono<Void> = when (resource) {
        LeaseResource.Rejected -> {
            clearAcquireMarker(exchange, marker)
            reject(exchange)
        }
        is LeaseResource.Blocking -> handleBlockingResource(resource, exchange, chain, runtime, marker, fingerprint)
        is LeaseResource.Suspend -> handleSuspendResource(resource, exchange, chain, runtime, marker, fingerprint)
    }

    private fun handleBlockingResource(
        resource: LeaseResource.Blocking,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
        runtime: LeaderRouteLeaseRuntime?,
        marker: LeaseAcquireMarker,
        fingerprint: Int,
    ): Mono<Void> {
        val holder = LeaseExchangeHolder(resource = resource, fingerprint = fingerprint)
        return if (publishHolder(exchange, marker, holder, resource)) {
            chain.filter(exchange)
        } else {
            runtime?.observe(LeaseObservationCode.STALE)
            clearAcquireMarker(exchange, marker)
            releaseResource(exchange, resource).then(reject(exchange))
        }
    }

    private fun handleSuspendResource(
        resource: LeaseResource.Suspend,
        exchange: ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
        runtime: LeaderRouteLeaseRuntime?,
        marker: LeaseAcquireMarker,
        fingerprint: Int,
    ): Mono<Void> {
        val holder = LeaseExchangeHolder(resource = resource, fingerprint = fingerprint)
        return if (publishHolder(exchange, marker, holder, resource)) {
            chain.filter(exchange)
        } else {
            runtime?.observe(LeaseObservationCode.STALE)
            clearAcquireMarker(exchange, marker)
            releaseResource(exchange, resource).then(reject(exchange))
        }
    }

    private fun reject(exchange: ServerWebExchange): Mono<Void> {
        exchange.response.statusCode = HttpStatusCode.valueOf(properties.rejectionStatus.value)
        return exchange.response.setComplete()
    }

    private fun staleRejection(runtime: LeaderRouteLeaseRuntime?, exchange: ServerWebExchange): Mono<Void> {
        runtime?.observe(LeaseObservationCode.STALE)
        return reject(exchange)
    }

    private fun releaseResource(exchange: ServerWebExchange, resource: LeaseResource): Mono<Void> {
        return when {
            !resource.released.compareAndSet(false, true) -> Mono.empty()
            resource is LeaseResource.Rejected -> Mono.empty()
            else -> {
                val physical = synchronized(exchange.attributes) {
                    if (!resource.published.get()) {
                        true
                    } else {
                        releaseHolderUseLocked(exchange, resource.token) == HolderRelease.PHYSICAL
                    }
                }
                if (physical) releasePhysicalResource(resource) else Mono.empty()
            }
        }
    }

    private fun releasePhysicalResource(resource: LeaseResource): Mono<Void> = when (resource) {
        LeaseResource.Rejected -> Mono.empty()
        is LeaseResource.Blocking -> Mono.fromRunnable { resource.handle.release() }
        is LeaseResource.Suspend -> mono<Void> {
            resource.handle.release()
            null
        }
    }

    private fun releaseHolderUse(exchange: ServerWebExchange, token: Any): Mono<Void> {
        val resource = synchronized(exchange.attributes) {
            val holder = exchange.attributes[LEASE_HANDLE_ATTRIBUTE] as? LeaseExchangeHolder
                ?: return@synchronized null
            if (holder.token !== token || !holder.releaseUse()) return@synchronized null
            exchange.attributes.remove(LEASE_HANDLE_ATTRIBUTE)
            holder.resource
        }
        return resource?.let(::releasePhysicalResource) ?: Mono.empty()
    }

    @Suppress("ReturnCount")
    private fun releaseHolderUseLocked(exchange: ServerWebExchange, token: Any): HolderRelease {
        val holder = exchange.attributes[LEASE_HANDLE_ATTRIBUTE] as? LeaseExchangeHolder
            ?: return HolderRelease.ABSENT
        if (holder.token !== token) return HolderRelease.ABSENT
        if (!holder.releaseUse()) return HolderRelease.SHARED
        exchange.attributes.remove(LEASE_HANDLE_ATTRIBUTE)
        return HolderRelease.PHYSICAL
    }

    private fun retainHolderUse(exchange: ServerWebExchange, holder: LeaseExchangeHolder): Boolean =
        synchronized(exchange.attributes) {
            if (exchange.attributes[LEASE_HANDLE_ATTRIBUTE] !== holder) return@synchronized false
            holder.tryRetain()
        }

    private fun clearAcquireMarker(exchange: ServerWebExchange, marker: LeaseAcquireMarker) {
        synchronized(exchange.attributes) {
            if (exchange.attributes[LEASE_HANDLE_ATTRIBUTE] === marker) {
                exchange.attributes.remove(LEASE_HANDLE_ATTRIBUTE)
                marker.published.complete(null)
            }
        }
    }

    private fun publishHolder(
        exchange: ServerWebExchange,
        marker: LeaseAcquireMarker,
        holder: LeaseExchangeHolder,
        resource: LeaseResource,
    ): Boolean = synchronized(exchange.attributes) {
        if (resource.released.get() || exchange.attributes[LEASE_HANDLE_ATTRIBUTE] !== marker) {
            return@synchronized false
        }
        exchange.attributes[LEASE_HANDLE_ATTRIBUTE] = holder
        resource.published.set(true)
        marker.published.complete(holder)
        true
    }

    private fun fingerprint(slot: LeaderSlot): Int =
        FINGERPRINT_MULTIPLIER * slot.lockName.hashCode() + slot.leaderId.hashCode()

    private sealed interface LeaseResource {
        data object Rejected : LeaseResource {
            override val token: Any get() = this
            override val released: AtomicBoolean = AtomicBoolean(true)
            override val published: AtomicBoolean = AtomicBoolean(false)
        }

        data class Blocking(val handle: LeaderLeaseHandle) : LeaseResource {
            override val token: Any get() = handle
            override val released: AtomicBoolean = AtomicBoolean(false)
            override val published: AtomicBoolean = AtomicBoolean(false)
        }

        data class Suspend(val handle: SuspendLeaderLeaseHandle) : LeaseResource {
            override val token: Any get() = handle
            override val released: AtomicBoolean = AtomicBoolean(false)
            override val published: AtomicBoolean = AtomicBoolean(false)
        }

        val token: Any
        val released: AtomicBoolean
        val published: AtomicBoolean
    }

    private enum class HolderRelease {
        PHYSICAL,
        SHARED,
        ABSENT,
    }

    private data class LeaseExchangeHolder(
        val resource: LeaseResource,
        val fingerprint: Int,
        val uses: AtomicInteger = AtomicInteger(1),
        val terminal: AtomicBoolean = AtomicBoolean(false),
    ) {
        val token: Any get() = resource.token

        fun tryRetain(): Boolean {
            var retained = false
            while (!terminal.get() && !retained) {
                val current = uses.get()
                retained = current > 0 && uses.compareAndSet(current, current + 1)
            }
            return retained
        }

        fun releaseUse(): Boolean {
            val remaining = uses.decrementAndGet()
            return remaining == 0 && terminal.compareAndSet(false, true)
        }
    }

    private class LeaseAcquireMarker(val fingerprint: Int) {
        val published = CompletableFuture<LeaseExchangeHolder?>()
    }

    private data class RedirectResult(
        val evaluation: LeaderRouteEvaluation,
        val location: java.net.URI?,
    )

    private companion object {
        const val TEMPORARY_REDIRECT_STATUS = 307
        const val FINGERPRINT_MULTIPLIER = 31
        const val LEASE_HANDLE_ATTRIBUTE = "io.bluetape4k.leader.spring.route.webflux.LEASE_HANDLE"
    }
}
