# Lessons Learned — Freefair CTW double-fire 검증 (#87) (2026-05-06)

**관련 PR**: #113
**영향 모듈**: leader-spring-boot

## L1: Freefair CTW와 ApplicationContextRunner — advice 카운팅 불가 환경

### 문제
`CountingLeaderElectorFactory`로 `runIfLeader` 호출 수를 검증하려 했으나, `runIfLeaderCalls.get() == 0`이었다.
처음에는 CTW aspect 싱글톤과 Spring bean 분리 문제로 추정했지만, 실제 원인은 달랐다.

### 교훈
Freefair `io.freefair.aspectj.post-compile-weaving` 플러그인은 **main sourceSet만 weave**하고,
test sourceSet에 선언된 클래스는 weaving 대상이 아니다. 따라서 `AdviceFireTestService`가
test 코드 안에 있으면 `@LeaderElection` advice가 bytecode에 전혀 없다 — `doWork()`가 직접 실행되므로 "done"을 반환하지만, factory는 한 번도 호출되지 않는다.

**How to apply:** CTW weaving 동작을 단위 테스트 레벨에서 검증하려면 main sourceSet에 weaved된
클래스를 사용하거나, `@SpringBootTest` 통합 테스트에서 실제 Spring 컨텍스트를 사용해야 한다.

---

## L2: double-fire 방지 검증의 핵심 — internalAutoProxyCreator 부재

### 문제
CTW + Spring AOP 이중 적용(double-fire) 방지를 런타임 advice 카운팅으로 검증하려 했지만,
위(L1)의 이유로 불가능했다.

### 교훈
double-fire 방지의 구조적 보증은 `internalAutoProxyCreator` 빈 부재로 충분하다.
- `@EnableAspectJAutoProxy`가 없으면 → Spring이 `internalAutoProxyCreator` 빈을 등록하지 않음
- `internalAutoProxyCreator` 없음 → Spring runtime proxy 미생성 → CTW advice만 1회 적용
- 이 구조적 검증이 런타임 카운팅보다 더 안정적이다

**How to apply:** CTW 모듈의 AutoConfiguration에 `@EnableAspectJAutoProxy` 미포함 검증 시
`ctx.containsBeanDefinition("org.springframework.aop.config.internalAutoProxyCreator") shouldBeEqualTo false`
하나로 충분하다.

---

## L3: LeaderElector 인터페이스 — AsyncLeaderElector 상속

### 문제
`CountingLeaderElectorFactory`에서 anonymous object가 `LeaderElector`를 구현할 때,
`runAsyncIfLeader` 미구현으로 컴파일 에러 발생.

### 교훈
`LeaderElector`는 `AsyncLeaderElector`를 상속한다. anonymous object로 구현 시
`runIfLeader`뿐 아니라 `runAsyncIfLeader`도 구현해야 한다.
`CompletableFuture.supplyAsync({ action().join() }, executor)` 패턴으로 단순 위임 구현.

**How to apply:** 테스트에서 `LeaderElector` anonymous object 생성 시 두 메서드 모두 구현.
