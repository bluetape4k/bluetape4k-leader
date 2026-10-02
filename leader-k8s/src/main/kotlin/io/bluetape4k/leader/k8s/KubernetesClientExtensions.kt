package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> T,
): T? = KubernetesLeaseLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
inline fun <T> KubernetesClient.runIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    crossinline action: () -> T,
): T? =
    KubernetesLeaseLeaderGroupElector(this, options).runIfLeader(lockName) { action() }

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> KubernetesClient.runAsyncIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
suspend fun <T> KubernetesClient.suspendRunIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    action: suspend () -> T,
): T? = KubernetesLeaseSuspendLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Kubernetes Lease backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
suspend fun <T> KubernetesClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    action: suspend () -> T,
): T? =
    KubernetesLeaseSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
