package io.bluetape4k.leader.k8s

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 지정한 `lockName`의 Kubernetes Lease를 획득한 경우에만 [action]을 현재 스레드에서 실행합니다.
 *
 * Lease는 [options]의 namespace에 저장되고, 다른 인스턴스가 유효한 holder인 동안에는
 * 정상 contention으로 간주하여 `null`을 반환합니다. 획득 후 action이 끝나면 Lease를 정리합니다.
 */
inline fun <T> KubernetesClient.runIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    crossinline action: () -> T,
): T? = KubernetesLeaseLeaderElector(this, options).runIfLeader(lockName) { action() }

/**
 * [executor]에서 Kubernetes Lease 획득을 시도한 뒤, 획득한 경우에만 [action]을 비동기로 실행합니다.
 *
 * 획득하지 못하면 완료된 future의 값은 `null`이고, action future가 완료되거나 취소되면 Lease와
 * 자동 연장을 함께 정리합니다.
 */
inline fun <T> KubernetesClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderElector(this, options).runAsyncIfLeader(lockName, executor) { action() }

/**
 * `[lockName]-slot-N` 형태의 slot Lease 중 하나를 획득한 경우에만 [action]을 현재 스레드에서 실행합니다.
 *
 * slot 수와 Lease namespace, 대기/임대 시간은 [options]에서 가져오며, 모든 slot이 사용 중이면
 * 정상 contention으로 간주하여 `null`을 반환합니다.
 */
inline fun <T> KubernetesClient.runIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    crossinline action: () -> T,
): T? =
    KubernetesLeaseLeaderGroupElector(this, options).runIfLeader(lockName) { action() }

/**
 * [executor]에서 slot Lease 획득을 시도한 뒤, 획득한 경우에만 [action]을 비동기로 실행합니다.
 *
 * 사용 가능한 slot이 없으면 완료된 future의 값은 `null`이며, action future가 끝나면 획득한 slot의
 * Lease와 자동 연장을 정리합니다.
 */
inline fun <T> KubernetesClient.runAsyncIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    KubernetesLeaseLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor) { action() }

/**
 * [options]의 namespace에서 `lockName` Lease를 suspend 방식으로 획득한 경우에만 [action]을 실행합니다.
 *
 * 정상 contention은 `null`로 반환하고, Kubernetes I/O는 suspend 경로에서 `Dispatchers.IO`로 이동하며,
 * action 완료 또는 취소 후 Lease를 정리합니다.
 */
suspend inline fun <T> KubernetesClient.suspendRunIfLeader(
    lockName: String,
    options: KubernetesLeaseOptions = KubernetesLeaseOptions.Default,
    crossinline action: suspend () -> T,
): T? = KubernetesLeaseSuspendLeaderElector(this, options).runIfLeader(lockName) { action() }

/**
 * `[lockName]-slot-N` 형태의 slot Lease를 suspend 방식으로 획득한 경우에만 [action]을 실행합니다.
 *
 * [options]의 `maxLeaders`만큼 동시에 slot을 사용할 수 있으며, 사용 가능한 slot이 없으면 `null`을
 * 반환하고 action 완료 또는 취소 후 획득한 Lease를 정리합니다.
 */
suspend inline fun <T> KubernetesClient.suspendRunIfLeaderGroup(
    lockName: String,
    options: KubernetesLeaseGroupOptions = KubernetesLeaseGroupOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    KubernetesLeaseSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
