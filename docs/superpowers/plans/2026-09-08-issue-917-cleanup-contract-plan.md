# #917 cleanup 계약 테스트 공통화 계획

> 실행: `executing-plans`로 직접 수행한다. native 검토 실행 실패는 독립 PASS로 바꾸지 않는다.

목표: production 코드를 바꾸지 않고 네 backend의 dispatcher 회귀 계약을 기존 core testFixtures에 모은다.
구조: 공통 추상 테스트 + 각 모듈 내부 dispatcher를 호출하는 얇은 adapter. Kotlin/JUnit5/bluetape4k assertions 사용.

## 작업 1 — 공유 suite와 adapter

- [ ] `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractAsyncLeaseCleanupContractTest.kt`에 아래 실행 경계를 둔다.

```kotlin
abstract class AbstractAsyncLeaseCleanupContractTest {
    protected abstract fun completeOwned(source: CompletableFuture<String>, cleanup: () -> Unit): CompletableFuture<String?>
    protected abstract fun completeInjected(source: CompletableFuture<String>, primary: Executor, fallback: Executor, cleanup: () -> Unit): CompletableFuture<String?>
}
```

- [ ] MongoDB의 7개 scheduler 회귀를 위 suite로 옮기고 cleanup 단독 실패, 중복 dispatch, 정상 완료 순서를 추가한다.
- [ ] `leader-{consul,etcd,k8s,mongodb}/src/test/kotlin/io/bluetape4k/leader/{consul,etcd,k8s,mongodb}/`에 `*AsyncLeaseCleanupContractTest.kt` adapter를 만든다.

```kotlin
override fun completeOwned(source: CompletableFuture<String>, cleanup: () -> Unit) =
    AsyncLeaseCleanupDispatcher.completeAfter(source, cleanup) { value, _ -> value }
override fun completeInjected(source: CompletableFuture<String>, primary: Executor, fallback: Executor, cleanup: () -> Unit) =
    AsyncLeaseCleanupDispatcher.completeAfter(source, primary, cleanup, fallback) { value, _ -> value }
```

- [ ] 네 모듈 각각 `./gradlew :bluetape4k-leader-<module>:test --tests '*AsyncLeaseCleanupContractTest' --console=plain --no-build-cache`를 순차 실행한다. production 동작 변경이 없는 refactor이므로 기존 #916/#915 RED 근거를 보존하고 현재 GREEN으로 이동 안전성을 잠근다.

## 작업 2 — 테스트 중복 제거

- [ ] 기존 `MongoAsyncLeaseCleanupDispatcherTest.kt`를 공통 suite adapter로 대체한다.
- [ ] `ConsulLeaderElectorDelegationTest`, `EtcdAsyncLifecycleTest`, `KubernetesLeaseAsyncLifecycleTest`에서 공유 suite가 대체하는 scheduler 이중 실패 테스트만 제거한다.
- [ ] backend barrier·single/group lifecycle 테스트는 유지한다. grounded fail-safe fallback의 의미는 변경하지 않는다.

## 작업 3 — 검증과 전달

- [ ] core testFixtures compile 및 네 backend 전체 test/detekt를 하나씩 순차 실행한다.
- [ ] `./gradlew checkBinaryCompatibility --console=plain --no-build-cache`, `git diff --check`와 test count 확인.
- [ ] README production 계약은 변하지 않으므로 spec의 차이 표와 lesson으로 공통 테스트/소유권 결정을 기록한다. 새 module/workflow/Spring/catalog 변경은 N/A.
- [ ] 성능/안정성/보안/Ops/API/caller 관점과 통합 검토를 수행한다. 실패 시 해당 adapter/suite와 모듈 검증부터 다시 실행한다.
- [ ] lesson 및 GNO update, 좁은 커밋, 승인된 stacked PR 생성. CI 미실행은 PENDING으로 기록하고 머지하지 않는다.

## 위험과 되돌리기

adapter가 production dispatcher 대신 fake 로직을 검사하면 contract가 무의미해진다. adapter의 실제 호출을 검토한다.
모든 suite 메서드의 JUnit 상속 실행 수를 네 XML에서 확인한다. backend별 차이를 공통화하려 production 상태 머신을
변경하지 않는다. 되돌리기는 이 branch의 테스트·문서 diff만 대상으로 하며 #916 commit은 보존한다.
