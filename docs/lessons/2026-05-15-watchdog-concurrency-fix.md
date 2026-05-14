# Lessons Learned — watchdog concurrency fix (2026-05-15)

**관련 PR**: #244
**영향 모듈**: `leader-core`, `leader-spring-boot`

## L1: `@Volatile` 필드는 복합 연산에서 원자성을 보장하지 않는다

### 문제
`LeaderLeaseAutoExtender.shutdown()`이 `@Volatile var scheduler`를 세 번 독립적으로 읽었다 — `shutdown()`, `awaitTermination()`, `shutdownNow()` 호출 사이에 `restart()`가 끼어들어 새 executor를 swapping하면, 새 executor가 즉시 `shutdownNow()`를 받는다.

### 교훈
두 번 이상 참조하는 공유 mutable 필드는 블로킹 시퀀스 시작 전에 `val current = field`로 로컬 캡처한다. `@Volatile`은 가시성만 보장하며 복합 연산의 원자성은 보장하지 않는다.

---

## L2: JVM-global singleton을 여러 Spring 컨텍스트가 공유할 때는 ref-counting이 필요하다

### 문제
`LeaderLeaseAutoExtenderLifecycle.destroy()`가 무조건 `LeaderLeaseAutoExtender.shutdown()`을 호출했다. 동일 JVM 내 여러 `ApplicationContext`(병렬 `@DirtiesContext` 테스트, 멀티-컨텍스트 배포) 중 하나가 닫힐 때 나머지 컨텍스트의 watchdog까지 함께 멈췄다.

### 교훈
JVM-scoped singleton을 여러 컨텍스트가 공유한다면, companion object의 `AtomicInteger` ref-count로 마지막 컨텍스트가 닫힐 때만 `shutdown()`을 호출하도록 제어한다. increment/decrement-then-action 시퀀스는 `synchronized` 블록으로 묶어 경쟁 조건을 막는다.

---

## L3: ref-count 인스턴스는 idempotency 보호가 필요하다

### 문제
Codex P2 리뷰: `destroy()`가 전역 카운터를 무조건 감소시키므로 같은 인스턴스에서 `destroy()` 중복 호출 시 카운터가 음수로 underflow — 이후 컨텍스트가 마지막에 닫힐 때 `shutdown()`이 호출되지 않는다.

### 교훈
ref-count에 기여하는 각 인스턴스는 자신이 실제로 카운터에 등록되었는지 추적하는 per-instance `AtomicBoolean registered` 플래그를 가져야 한다. `afterPropertiesSet()`은 `compareAndSet(false, true)` 성공 시에만 증가, `destroy()`는 `compareAndSet(true, false)` 성공 시에만 감소 — 중복 호출은 no-op이 된다.

---

## L4: Codex 리뷰는 ref-counting 구현의 edge case를 찾는다

### 문제
첫 구현에서 `synchronized` 블록 추가 후 Advisor가 잔여 race를 지적했고, Codex 리뷰에서 per-instance idempotency 누락(P2)을 찾았다.

### 교훈
동시성 버그 수정은 단계별로 Advisor + Codex 리뷰를 반복하는 것이 효과적이다. 특히 ref-count 패턴은 (1) 전역 원자성, (2) 인스턴스별 idempotency, (3) 언더플로우 방어 세 가지를 모두 검토해야 한다.
