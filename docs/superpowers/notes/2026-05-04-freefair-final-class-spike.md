# T4.1a Freefair × final class Spike

날짜: 2026-05-04
이슈: #41 leader-aop / Q-P2 (a) decision 재평가
결론: **Option A 채택** — 단일 `final class @Aspect` in common + `@Bean` 등록

## 1. Freefair plugin 동작 (검증됨)

`io.freefair.aspectj.post-compile-weaving` v9.5.0 기준:

- `kotlinc` 산출물(bytecode)에 ajc가 추가로 weaving — Kotlin 소스도 지원
- `@Aspect` annotation 스타일 완전 지원 (.aj 파일 불필요)
- aspect 의존성은 전용 `aspect` configuration 사용:
  ```kotlin
  dependencies {
    aspect(project(":leader-spring-boot-common"))
  }
  ```
- 위 설정이 ajc의 `-aspectpath` 인자로 전달 → dependency JAR의 컴파일된 `@Aspect` 클래스 사용 가능 (discussion #493)
- `io.freefair.aspectj` 와 `post-compile-weaving` 은 mutually exclusive (issue #445)
- aspect 클래스의 final/abstract/visibility 제약 명시 없음

## 2. Spring AOP `@Aspect` 패키징 요구사항

Spring Framework 공식 문서 결정적 인용:

> "The `@Aspect` annotation on a class marks it as an aspect and, hence, **excludes it from auto-proxying**."

> CGLib `final` 제약은 **target bean 한정** — aspect 클래스 자체에 적용 안 됨

귀결:
- `@Aspect class` 는 Spring proxy 대상이 아님 → Kotlin default `final` 무관
- `@Component` 없이 `@Bean` 등록하면 component-scan 중복 등록 회피
- `@Order` 는 `@Bean` 메서드에 부착 가능

## 3. Decision Matrix

| 옵션 | Spring AOP (Boot 3) | Freefair (Boot 4) | 비용 | 비고 |
|---|---|---|---|---|
| A: `final class @Aspect` in common + `@Bean` | OK (aspect 미프록시) | OK (aspectpath via `aspect` config) | 최저 | **권장** |
| B: `abstract class` + 빈 subclass `@Aspect @Component @Order` | OK | OK | 클래스 2× | 현 plan, 빈 서브클래스 = 보일러플레이트 |
| C: `open class @Aspect` in common + `@Bean` | OK | OK | 동일 | A 대비 이득 없음 (proxy 대상 아니므로) |

C는 A와 동일한 효과 → 의미 없음. B는 동작하지만 모듈당 Empty subclass 보일러플레이트.

## 4. 권고: Option A

설계:
```kotlin
// leader-spring-boot-common
@Aspect
class LeaderElectionAspect(  // final 허용 (proxy 대상 아님)
    private val beanFactory: BeanFactory,
    private val props: LeaderAopProperties,
    private val spel: SpelExpressionEvaluator,
    private val recorders: List<LeaderAopMetricsRecorder>,
) {
    @Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderElection)")
    fun aroundLeader(pjp: ProceedingJoinPoint): Any? = ...
}
// 주의: @Component 절대 부착 금지 — autoconfig만 등록 책임

// leader-spring-boot3-aop / leader-spring-boot4-aspectj autoconfig
@AutoConfiguration(after = [LeaderAopFactoryAutoConfiguration::class])
@ConditionalOnBean(LeaderElectionFactory::class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
class LeaderAopAutoConfiguration {
    @Bean
    @Order(LeaderAspectOrder.AOP_ORDER)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    fun leaderElectionAspect(
        beanFactory: BeanFactory,
        props: LeaderAopProperties,
        spel: SpelExpressionEvaluator,
        recordersProvider: ObjectProvider<List<LeaderAopMetricsRecorder>>,
    ) = LeaderElectionAspect(
        beanFactory, props, spel,
        recordersProvider.getIfAvailable { emptyList() },
    )
}
```

근거:
1. Spring docs: `@Aspect` 는 auto-proxy 제외 → final OK
2. Freefair: `aspect` configuration 으로 dependency `@Aspect` weaving 검증
3. 빈 subclass 보일러플레이트 제거 (DRY)
4. `@Bean` + `@Order` 로 ordering 동일하게 보장
5. `@Component` 미부착 → 두 부트 모듈에서 component-scan 중복 위험 차단

## 5. Plan 영향 — task 폐기/수정 매트릭스

| Task | 현재 (B안) | A안 채택 시 | 변경 |
|------|-----------|------------|------|
| T2.19 | `AbstractLeaderElectionAspect` abstract 선언 | → `LeaderElectionAspect` final + `@Aspect` | 클래스 이름 변경 |
| T2.20 | `AbstractLeaderGroupElectionAspect` abstract 선언 | → `LeaderGroupElectionAspect` final + `@Aspect` | 클래스 이름 변경 |
| T2.13 | `Abstract*Aspect.aroundLeader()` 본체 | → `LeaderElectionAspect.aroundLeader()` 본체 | 위치 동일 |
| T2.15 | `Abstract*Aspect.aroundLeader()` 본체 (group) | → `LeaderGroupElectionAspect.aroundLeader()` 본체 | 위치 동일 |
| T2.14 | `Abstract*Aspect` SpEL pre-parse hook | → `LeaderElectionAspect` 본체에 `SmartInitializingSingleton` 구현 | 동일 |
| T2.16 | `Abstract*Aspect` SpEL pre-parse hook (group) | → 동일 | 동일 |
| T3.3 | Boot 3 `LeaderElectionAspect` 빈 subclass | → **폐기** (autoconfig `@Bean` 직접 등록) | 6 task 제거 |
| T3.4 | Boot 3 `LeaderGroupElectionAspect` 빈 subclass | → **폐기** | |
| T4.3 | Boot 4 `LeaderElectionAspect` 빈 subclass | → **폐기** | |
| T4.4 | Boot 4 `LeaderGroupElectionAspect` 빈 subclass | → **폐기** | |
| T3.5b | Boot 3 AutoConfig (Aspect/BPP/Health/Properties 빈) | → 동일 + Aspect `@Bean` 메서드 추가 | 갱신 |
| T4.5b | Boot 4 AutoConfig (동일) | → 동일 + Aspect `@Bean` 메서드 추가 | 갱신 |
| 추가 | — | Boot 4 `build.gradle.kts` 에 `aspect(project(":leader-spring-boot-common"))` 명시 | 신규 |

총 task: 104 → **100** (T3.3/T3.4/T4.3/T4.4 폐기, 4 task 감소)

## 6. 빌드 검증 필요 항목 (구현 단계 flag)

- [ ] **B-1** Boot 4: `aspect(project(":leader-spring-boot-common"))` 설정으로 `kotlinc` 산출 `@Aspect` 클래스가 ajc aspectpath 도달 + advice 삽입 검증 (Kotlin 케이스만 unverified)
- [ ] **B-2** Boot 4: `spring-boot-starter-aop` (Spring AOP 런타임 프록시) + Freefair (compile-time weaving) 동시 활성 시 advice 2회 발화 위험 (issue #1050) — 정책 결정 필요. 우선 starter-aop 의존성 미추가 + `@EnableAspectJAutoProxy` 만 활성화로 1회 발화 확보 시도
- [ ] **B-3** `@Bean` method `@Order` 와 class-level `@Order` 의 aspect 우선순위 동치성 1회 smoke test
- [ ] **B-4** Spring Boot 3.x context 기동 시 final `@Aspect` 클래스 `@Bean` 등록 + advice 적용 smoke test

## 7. 인용 (Sources)

- [Spring Framework — Declaring an Aspect](https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/at-aspectj.html) — "@Aspect ... excludes it from auto-proxying"
- [Spring Framework — Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html) — final 제약은 target bean 한정
- [Spring — Enabling @AspectJ Support](https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/aspectj-support.html)
- [FreeFair Gradle Plugins reference](https://docs.freefair.io/gradle-plugins/current/reference/) — post-compile-weaving + `aspect` config
- [freefair/gradle-plugins discussion #493](https://github.com/freefair/gradle-plugins/discussions/493) — aspectpath / inpath / library aspects
- [freefair/gradle-plugins issue #445](https://github.com/freefair/gradle-plugins/issues/445) — `io.freefair.aspectj` vs `post-compile-weaving` 상호배타
- [freefair/gradle-plugins issue #1050](https://github.com/freefair/gradle-plugins/issues/1050) — Spring Boot + post-compile-weaving 함정
- [FreeFair releases](https://github.com/freefair/gradle-plugins/releases) — 9.5.0 / Spring Boot 4.0.x / aspectj 1.9.25.1
