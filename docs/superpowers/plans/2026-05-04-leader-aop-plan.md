# Plan — `@LeaderElection` / `@LeaderGroupElection` AOP (sync only)

**Spec**: `docs/superpowers/specs/2026-05-04-leader-aop-design.md`
**Issue**: #41 (#69 SpEL 흡수)
**Branch**: `feat/leader-aop`
**작성일**: 2026-05-04
**Step 3-R 갱신**: 사용자 결정 Q-P1/Q-P2/Q-P3/Q-P4 + Critic HIGH 12 + MEDIUM 15 + LOW 4 + 추가 5 모두 적용
**Scope 결정**: 본 PR 은 sync (`T?`) 어드바이스만 다룬다. suspend / `Mono` / `Flow` / Reactor / R2DBC factory 는 후속 [#80].

본 plan 은 spec §4–§11 의 채택 결정 + Step 3-R critic 결정을 그대로 따른다. 각 task 는 `complexity: high|medium|low` 라벨 + 영향 파일 (worktree 상대경로) + 비고 (관련 spec §, R-id, 후속 이슈, critic 라벨) 를 포함한다.

## Step 3-R 사용자 결정 요약

| Q | 결정 | 영향 |
|---|------|------|
| **Q-P1** | (b) — 6 backend 의 factory `@Bean` 등록은 **AOP autoconfig** (`leader-spring-boot3-aop` / `leader-spring-boot4-aspectj`) 에서 직접. backend 모듈은 `spring-context` 의존 미추가 — 순수 Kotlin 유지 | T1.4-T1.8 Notes, T3.5b/T4.5b |
| **Q-P2** | (a) — `AbstractLeaderElectionAspect` / `AbstractLeaderGroupElectionAspect` abstract class 를 common 모듈에 본체로 두고, Boot 3 / Boot 4 concrete Aspect 는 **빈 subclass + `@Aspect @Component @Order @Role` 어노테이션만**. Freefair weaving 호환성 verify task 추가 | T2.19/T2.20, T3.3/T3.4, T4.3/T4.4 |
| **Q-P3** | (b) — common 모듈 `spring-context:6.2.x` `compileOnly` lock + Boot 4 module 이 7.x runtime 제공. Spring 6 vs 7 API surface 차이 점검 task 신설 | T2.18, T2.21 |
| **Q-P4** | 변경 없음 — pointcut `@annotation(LeaderElection)` 직접 부착만, 메타 어노테이션 [#84] 후속 | spec §11.2 |

---

## Phase 1 — 코어 SPI 확장 (`leader-core` + 6 백엔드)

per-call options 를 깨끗하게 처리하기 위해 `LeaderElectionFactory` / `LeaderGroupElectionFactory` SPI 를 코어에 추가하고, 6 sync 백엔드에 factory 구현을 등록한다 [C1][R-20][R-26]. AOP 가 `FactoryCacheKey(factoryBeanName, options)` 로 election instance 를 캐싱한다.

**Q-P1 (b)**: backend 모듈은 factory 클래스 정의만, Spring `@Bean` 등록은 AOP autoconfig (T3.5b / T4.5b) 에서 수행 — backend 모듈은 `spring-context` 의존 미추가 (순수 Kotlin 유지).

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T1.1 | `LeaderElectionFactory` interface — `fun create(options: LeaderElectionOptions): LeaderElection` | medium | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionFactory.kt` | sync only — suspend factory 는 [#80] |
| T1.2 | `LeaderGroupElectionFactory` interface — `fun create(options: LeaderGroupElectionOptions): LeaderGroupElection` | medium | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElectionFactory.kt` | sync only |
| T1.3 | Local backend factory 2종 (in-process) | **low** [M-1] | `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElectionFactory.kt`, `.../local/LocalLeaderGroupElectionFactory.kt` | 기존 `Local*Election` 재사용. 단순 `create(opts) = LocalLeaderElection(opts)` 위임 — 복잡도 낮음. **factory 빈 등록 위치 = AOP autoconfig (T3.5b/T4.5b)** [Q-P1 (b)] |
| T1.4 | Lettuce backend factory 2종 | medium | `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceLeaderElectionFactory.kt`, `.../LettuceLeaderGroupElectionFactory.kt` | connection 공유. backend 모듈 `spring-context` 의존 미추가 [Q-P1 (b)] |
| T1.5 | Redisson backend factory 2종 | medium | `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/RedissonLeaderElectionFactory.kt`, `.../RedissonLeaderGroupElectionFactory.kt` | `RedissonClient` 재사용 [Q-P1 (b)] |
| T1.6 | MongoDB sync factory 2종 | medium | `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElectionFactory.kt`, `.../MongoLeaderGroupElectionFactory.kt` | `MongoClient` 재사용. R2DBC 는 [#80] |
| T1.7 | Hazelcast backend factory 2종 | medium | `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElectionFactory.kt`, `.../HazelcastLeaderGroupElectionFactory.kt` | `HazelcastInstance` 재사용 |
| T1.8 | Exposed JDBC backend factory 2종 | medium | `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectionFactory.kt`, `.../ExposedJdbcLeaderGroupElectionFactory.kt` | sync only — Exposed R2DBC 는 [#80] |
| T1.9 | 백엔드 unlock contract 회귀 테스트 base — 본문 throw 시 try-finally unlock 검증 (T5.12 의 base 클래스). **fault injection 매트릭스 24 case 명시** — 6 backend × {RETHROW, SKIP} × {본문 throw, 본문 정상 + runIfLeader 반환 null} | **medium** [M-2][M-8] | `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/AbstractLeaderUnlockContractTest.kt` | [C-4][R-19] DoD CRITICAL. base 클래스 자체는 medium — 6 backend 인스턴스화 (T5.12) 에서 high |

---

## Phase 2 — 공통 AOP 인프라 (`leader-spring-boot-common`)

어노테이션, SpEL evaluator, Aspect 본체 (abstract class), footgun 검출 BeanPostProcessor, metrics SPI, HealthIndicator. Spring 의존성 추가 (`spring-aop`, `spring-expression`, Caffeine, kotlin-reflect).

**Q-P2 (a)**: Aspect 본체 = common abstract class. Boot 3/4 concrete = 빈 subclass + 어노테이션 1줄 (코드 중복 제거 + Freefair weaving 호환).

**Q-P3 (b)**: common = `spring-context:6.2.x compileOnly` lock, Boot 4 module = `7.x runtime`. Spring 6/7 API 차이 verify = T2.21.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T2.1 | `@LeaderElection` 어노테이션 (`@Target(METHOD)` only, `@Retention(RUNTIME)`) — `name` (필수, plain SpEL), `waitTime`, `leaseTime`, `bean` (factory bean name [H1][R-25]), `failureMode` | low | `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElection.kt` | `leaderId`/`minLeaseTime` 필드 없음 [#72][#77]. 메타 어노테이션 컴포지션 [#84] |
| T2.2 | `@LeaderGroupElection` 어노테이션 — 위 동일 + `maxLeaders: Int = -1` (≤1 → startup fail [H7]) | low | `.../aop/LeaderGroupElection.kt` | 동일 제외 사항 |
| T2.3 | `LeaderAspectFailureMode` enum (`RETHROW`, `SKIP`) | low | `.../aop/LeaderAspectFailureMode.kt` | `FAIL_OPEN_RUN` 은 [#81] |
| T2.4 | `LeaderAspectOrder` 상수 (`AOP_ORDER = HIGHEST_PRECEDENCE + 100`) | low | `.../aop/LeaderAspectOrder.kt` | 외부 advice 순서 표 README |
| T2.5 | `SkipReason` enum (`CONTENTION`, `BACKEND_ERROR`) | low | `.../aop/metrics/SkipReason.kt` | `FAIL_OPEN_FORCED` [#81] |
| T2.6 | `LeaderAopMetricsRecorder` interface (6 콜백 + `NoOp` object default) | low | `.../aop/metrics/LeaderAopMetricsRecorder.kt` | best-effort [M1][R-30] |
| T2.7 | `DurationParser` — ISO-8601 (`PT10S`) + simple (`10s`/`5m`/`1h`/`500ms`) 양쪽 지원, 음수/0 검증 | medium | `.../aop/util/DurationParser.kt` | ShedLock 차용 |
| T2.8 | `LockNameValidator` — length / charset 검증 | low | `.../aop/util/LockNameValidator.kt` | [R-4] |
| T2.9 | `FactoryCacheKey` data class + `GroupFactoryCacheKey` data class | low | `.../aop/cache/FactoryCacheKey.kt` | [C1][R-26] cross-backend collision 방지 |
| T2.10 | `SpelExpressionEvaluator` — `SimpleEvaluationContext.forPropertyAccessors().withMethodResolvers()`, Caffeine cache (`maximumSize=1024, expireAfterAccess=1h`), literal fast-path, `${...}` placeholder, plain SpEL only (no `TemplateParserContext`) | high | `.../aop/spel/SpelExpressionEvaluator.kt` | [Q4][R-16][R-17][R-28] |
| T2.11 | `LeaderBeanSelector` — factory bean 선택 (`bean` 명시 → 단일 → `@Primary` → ambiguous fail) | medium | `.../aop/LeaderBeanSelector.kt` | [R-13] |
| T2.12 | `findAnnotationWithTargetFallback()` 헬퍼 (proxy → target class lookup) | medium | `.../aop/util/AnnotationLookup.kt` | [R-24] ShedLock 차용 |
| **T2.13** [H-4] | `AbstractLeaderElectionAspect.aroundLeader(pjp)` `@Around` 본체 — sync `T?` only, metrics fan-out, RETHROW/SKIP, lease warn (`elapsed > leaseTime*0.8`), `findAnnotationWithTargetFallback()`. `private val factoryCache = ConcurrentHashMap<FactoryCacheKey, LeaderElection>()` + KLogging 4 events (`leader.aop.elected/skipped/failed/lease-warn`) [M-9][L-1] | high | `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/aop/AbstractLeaderElectionAspect.kt` | [H10] |
| **T2.14** [H-4] | `AbstractLeaderElectionAspect` SpEL pre-parse hook — `SmartInitializingSingleton.afterSingletonsInstantiated()` → `@LeaderElection` 메서드 스캔 → SpEL pre-parse, 실패 시 startup fail (method FQN 포함) | medium | 동일 파일 | [H6] |
| **T2.15** [H-4] | `AbstractLeaderGroupElectionAspect.aroundLeader(pjp)` `@Around` 본체 — `LeaderGroupElectionFactory` + `maxLeaders`, 그 외 T2.13 동일. `ConcurrentHashMap<GroupFactoryCacheKey, LeaderGroupElection>` + KLogging 4 events [M-9][L-1] | high | `.../aop/AbstractLeaderGroupElectionAspect.kt` | [H10] |
| **T2.16** [H-4] | `AbstractLeaderGroupElectionAspect` SpEL pre-parse hook (T2.14 동일 패턴) | medium | 동일 파일 | [H6] |
| T2.17 | `LeaderAnnotationValidatorBeanPostProcessor` — final/private/maxLeaders/suspend/reactive/SpEL pre-parse 검출 + best-effort self-invocation WARN | medium | `.../aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` | [H5][R-22][R-31][M2] |
| T2.18 | `LeaderAopProperties` — `enabled`, `strict`, `failureMode`, `defaultLeaseTime`, `defaultWaitTime` | low | `.../aop/properties/LeaderAopProperties.kt` | `default-min-lease-time` 미지원 [#77] |
| **T2.19** [H-10] | `AbstractLeaderElectionAspect` abstract class 선언 (비-`@Aspect`) — Boot 3/4 subclass 가 어노테이션 부착. `open fun aroundLeader(pjp): Any?` 노출 [Q-P2 (a)] | medium | `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/aop/AbstractLeaderElectionAspect.kt` | T2.13/T2.14 의 클래스 선언 |
| **T2.20** [H-10] | `AbstractLeaderGroupElectionAspect` abstract class 선언 (비-`@Aspect`) | medium | `.../aop/AbstractLeaderGroupElectionAspect.kt` | T2.15/T2.16 의 클래스 선언 |
| **T2.21** [H-11] | Spring 6/7 API 호환성 verify — `SimpleEvaluationContext.withMethodResolvers()` 시그니처, `BeanFactoryResolver` deprecated, `@AutoConfiguration` 패키지 변동, Caffeine API 동일 여부. 산출물: verify 노트 + 차이 발견 시 conditional path | medium | `docs/superpowers/notes/2026-05-04-spring6-vs-spring7-verify.md` | [Q-P3 (b)] |
| T2.22 | `LeaderAopHealthIndicator` — advice 메서드 수, SpEL cache size, 누적 카운터 | low | `.../aop/health/LeaderAopHealthIndicator.kt` | [M4] |
| T2.23 | `build.gradle.kts` 의존성 — `spring-context:6.2.x compileOnly` [Q-P3], `spring-aop`, `spring-expression`, Caffeine, `kotlin-reflect` [CR-2], `spring-boot-actuator` (optional) | low | `leader-spring-boot-common/build.gradle.kts` | Boot 4 module 이 7.x runtime 제공 |

**T2.13–T2.16/T2.19/T2.20 의존성 그래프** [H-3]:

| Task | 선행 task |
|------|----------|
| T2.13 (sync 본체) | T1.1, T2.1, T2.3, T2.6, T2.7, T2.8, T2.9, T2.10, T2.11, T2.12 |
| T2.14 (sync pre-parse) | T2.13, T2.10 |
| T2.15 (group 본체) | T1.2, T2.2, T2.3, T2.6–T2.12 |
| T2.16 (group pre-parse) | T2.15, T2.10 |
| T2.19 (sync abstract class) | T2.13, T2.14 |
| T2.20 (group abstract class) | T2.15, T2.16 |

---

## Phase 3 — Boot 3 AOP 모듈 (`leader-spring-boot3-aop`) — 신규 module [M3]

flat naming 신규 모듈. Spring AOP 런타임 프록시. concrete Aspect (빈 subclass) + AutoConfiguration + `AutoConfiguration.imports`.

**Q-P2 (a)**: concrete Aspect = 빈 subclass + 어노테이션 1줄. **Q-P1 (b)**: AutoConfig 가 6 backend factory `@Bean` 등록까지 담당.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T3.1 | `settings.gradle.kts` 에 `:leader-spring-boot3-aop` include | low | `settings.gradle.kts` | [M3] |
| **T3.2** | `build.gradle.kts` — common + Boot 3 BOM (`spring-context:6.2.x`) + `spring-aop` + 6 backend `compileOnly`. 표준 자원: `junit-platform.properties` (PER_CLASS + parallel=false) + `logback-test.xml` [M-6] | low | `leader-spring-boot3-aop/build.gradle.kts`, `.../src/test/resources/junit-platform.properties`, `.../logback-test.xml` | CLAUDE.md 표준 |
| **T3.3** [Q-P2] | `LeaderElectionAspect` — 빈 subclass + `@Aspect @Component @Order(AOP_ORDER) @Role(ROLE_INFRASTRUCTURE)` (extends `AbstractLeaderElectionAspect`) | low | `leader-spring-boot3-aop/src/main/kotlin/io/bluetape4k/leader/spring/boot3/aop/LeaderElectionAspect.kt` | 본체는 common |
| **T3.4** [Q-P2] | `LeaderGroupElectionAspect` — 빈 subclass | low | `.../boot3/aop/LeaderGroupElectionAspect.kt` | |
| **T3.5a** [H-1] | `LeaderAopAutoConfiguration` shell — `@AutoConfiguration @ConditionalOnClass(Aspect) @ConditionalOnBean(LeaderElectionFactory) @ConditionalOnProperty(enabled, matchIfMissing=true) @EnableAspectJAutoProxy(proxyTargetClass=true)` + 조건부 import | medium | `.../boot3/aop/autoconfigure/LeaderAopAutoConfiguration.kt` | [H2][R-29] factory-only |
| **T3.5b** [H-1][H-9][Q-P1] | AutoConfig 빈 wiring — Aspect 2 + Validator + Health + Properties + SpEL + **6 backend × 2 factory `@Bean` (12개)** 각 `@ConditionalOnClass/Bean(BackendClient)` 가드. 모두 `@Role(ROLE_INFRASTRUCTURE)` | high | 동일 파일 | Q-P1 (b) — backend 모듈 spring-context 의존 미추가 |
| T3.6 | `AutoConfiguration.imports` | low | `leader-spring-boot3-aop/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | |
| T3.7 | `additional-spring-configuration-metadata.json` | low | `leader-spring-boot3-aop/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json` | IDE 자동완성. `aot.factories` Notes only [CR-3] (scope 외) |
| T3.8 | `leader-bom/build.gradle.kts` 에 신규 module 추가 | low | `leader-bom/build.gradle.kts` | |

---

## Phase 4 — Boot 4 AspectJ 모듈 (`leader-spring-boot4-aspectj`) — 신규 module [M3]

flat naming 신규 모듈. AspectJ post-compile-weaving (Freefair 플러그인) 으로 모듈 자체 클래스 advice 매칭 검증. 사용자 빈에 대한 advice 는 동일 Spring AOP 런타임 프록시. **Spring `7.x` runtime 제공** [Q-P3 (b)].

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T4.1 | `settings.gradle.kts` 에 `:leader-spring-boot4-aspectj` include | low | `settings.gradle.kts` | [M3] |
| **T4.2** | `build.gradle.kts` — common + Boot 4 BOM (`spring-context:7.x` runtime) + `io.freefair.aspectj.post-compile-weaving` + `spring-aspects` + `aspectjweaver`/`aspectjrt` + 6 backend `compileOnly`. Freefair 호환성 verify (Boot 4 / Kotlin 2.3 / JVM 21 / Spring 7 + abstract class subclass weaving) [Q-P2]. 표준 자원: `junit-platform.properties` + `logback-test.xml` [M-6] | medium | `leader-spring-boot4-aspectj/build.gradle.kts`, `.../src/test/resources/junit-platform.properties`, `.../logback-test.xml` | CLAUDE.md 표준 |
| **T4.3** [Q-P2] | `LeaderElectionAspect` — 빈 subclass (Boot 3 동일 패턴) | low | `leader-spring-boot4-aspectj/src/main/kotlin/io/bluetape4k/leader/spring/boot4/aop/LeaderElectionAspect.kt` | |
| **T4.4** [Q-P2] | `LeaderGroupElectionAspect` — 빈 subclass | low | `.../boot4/aop/LeaderGroupElectionAspect.kt` | |
| **T4.5a** [H-1] | AutoConfig shell — Boot 3 동일 조건 | medium | `.../boot4/aop/autoconfigure/LeaderAopAutoConfiguration.kt` | |
| **T4.5b** [H-1][H-9][Q-P1] | AutoConfig 빈 wiring — Boot 3 동일 패턴 (6 backend × 2 factory `@Bean` 포함) | high | 동일 파일 | |
| T4.6 [신규] | Boot 4 GA `@AutoConfiguration` API 변경 verify — `@AutoConfiguration` 패키지 위치, `AutoConfiguration.imports` 경로, `@ConditionalOnClass/Bean` 동작 변경 여부 | low | `docs/superpowers/notes/2026-05-04-boot4-autoconfig-verify.md` | GA milestone 시 재확인 |
| T4.7 | `AutoConfiguration.imports` | low | `leader-spring-boot4-aspectj/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | |
| T4.8 | `additional-spring-configuration-metadata.json` | low | `leader-spring-boot4-aspectj/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json` | `aot.factories` Notes only [CR-3] |
| T4.9 | `leader-bom/build.gradle.kts` 에 신규 module 추가 | low | `leader-bom/build.gradle.kts` | |

---

## Phase 5 — 테스트

단위 + 통합 + 회귀. Kover ≥ 80% (3 모듈). Local 만 unit, 5 backend = `@Tag("integration")` + `XxxServer.Launcher` [H-5].

### 5.1 단위 테스트 (`leader-spring-boot-common`)

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| **T5.1** | `SpelExpressionEvaluatorTest` — literal fast-path, plain SpEL (`#region`, `"'process-' + #region"`), `${...}` placeholder, pre-parse 실패 (메서드 FQN), 따옴표 없는 리터럴 prefix (`process-#region`) → fail, `T(...)` / 빈 참조 차단, Caffeine 1024 evict. SpEL `null` 결과 → `IllegalStateException(메서드 FQN + 표현식)` [H-7]. PBT — fast-path 정규식 invariant [M-5] | high | `leader-spring-boot-common/src/test/kotlin/io/bluetape4k/leader/spring/aop/spel/SpelExpressionEvaluatorTest.kt` | [Q4][R-16][R-17][R-28][H6] |
| **T5.2** | `DurationParserTest` — ISO-8601 + simple, 음수/0 거부, `leaseTime < waitTime` WARN. PBT — round-trip + 음수/0 invariant + simple↔ISO 동치 [M-5] | medium | `.../aop/util/DurationParserTest.kt` | |
| T5.3 | `LockNameValidatorTest` | low | `.../aop/util/LockNameValidatorTest.kt` | |
| T5.4 | `FactoryCacheKeyTest` — equals/hashCode + cross-backend collision 방지 | low | `.../aop/cache/FactoryCacheKeyTest.kt` | [R-26] |
| **T5.5** [M-3] | Validator footgun 12 case (final/private/`maxLeaders≤1`/suspend/Mono/Flow/Flux/`@Scheduled`+`@EnableScheduling` 누락/`@Async`/self-inv/`proxyTargetClass=false`/return-Unit). strict fail vs default WARN | high | `.../aop/validator/LeaderAnnotationValidatorBeanPostProcessorTest.kt` | [H5][R-22][R-31] |
| **T5.6** [M-15] | Aspect 단위 base — mock `ProceedingJoinPoint`, fake factory. mock clock 으로 `elapsed > leaseTime*0.8` lease-warn emit 검증 | high | `.../aop/AbstractLeaderElectionAspectTest.kt` | [M6] mock-only |
| **T5.7** [M-15] | `failureMode` 매트릭스 — RETHROW/SKIP × {백엔드 throw, 본문 throw} + mock clock lease-warn | medium | `.../aop/LeaderAspectFailureModeTest.kt` | |
| T5.8 | `LeaderBeanSelectorTest` — 단일 / `@Primary` / ambiguous → fail | medium | `.../aop/LeaderBeanSelectorTest.kt` | [R-13] |
| T5.9 | `LeaderAopHealthIndicatorTest` | low | `.../aop/health/LeaderAopHealthIndicatorTest.kt` | |

### 5.2 백엔드 factory 단위/통합 테스트

**Local = unit, 5 backend = `@Tag("integration")`** [H-5] + `bluetape4k-testcontainers` `XxxServer.Launcher` (CLAUDE.md 표준).

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| **T5.10** [H-5] | 6 backend factory 단위/통합 — `factory.create(opts).runIfLeader(name) { ... }`. Local = `@Tag("unit")`, 5 backend = `@Tag("integration")` + `XxxServer.Launcher` | medium | `leader-core/.../LocalLeaderElectionFactoryTest.kt` (unit), 5 backend `*FactoryTest.kt` (integration) | CLAUDE.md 표준 |
| **T5.11** [H-5] | 6 backend group factory — `maxLeaders=N`. 분류 동일 | medium | 위 위치 group 변형 | |
| **T5.12** [M-2] | 백엔드 unlock contract 회귀 (6 backend) — fault injection 24 case 인스턴스화 (T1.9 base 상속) | high | 각 backend `*UnlockContractTest.kt` | [C-4][R-19] CRITICAL |

### 5.3 통합 테스트 (`@SpringBootTest`) — 6 backend cover [CR-1]

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T5.13 | Boot 3 × Local backend — `@LeaderElection`, `@LeaderGroupElection`, plain SpEL, `${...}` placeholder, simple Duration | medium | `leader-spring-boot3-aop/src/test/kotlin/.../integration/Boot3LocalAopIntegrationTest.kt` | infra 없음 |
| **T5.13a** [CR-1] | **Boot 3 × Lettuce backend** — `RedisServer.Launcher.redis` | medium | `.../integration/Boot3LettuceAopIntegrationTest.kt` | 6 backend cover |
| T5.14 | Boot 3 × Redisson backend — `RedisServer.Launcher.redis` | medium | `.../integration/Boot3RedissonAopIntegrationTest.kt` | |
| **T5.14a** [CR-1] | **Boot 3 × Hazelcast backend** — `HazelcastServer.Launcher.hazelcast` (있으면) 또는 embedded `HazelcastInstance` | medium | `.../integration/Boot3HazelcastAopIntegrationTest.kt` | 6 backend cover |
| T5.15 | Boot 3 × MongoDB backend — `MongoDBServer.Launcher.mongoDB` | medium | `.../integration/Boot3MongoAopIntegrationTest.kt` | sync only |
| T5.16 | Boot 3 × Exposed JDBC backend — Testcontainers `H2` | medium | `.../integration/Boot3ExposedJdbcAopIntegrationTest.kt` | |
| **T5.17** [M-10] | Boot 3 — `@ConditionalOnBean(LeaderElectionFactory)` 비활성 검증 (factory 미등록 시 AOP disabled + 마이그레이션 가이드). 빈 이름 표준 검증 통합 (12 빈 이름) | medium | `.../integration/Boot3FactoryConditionalTest.kt` | [H2][R-29] |
| **T5.18** [H-6] | Boot 3 — `bean` 명시 + 잘못된 bean 이름 → `NoSuchBeanDefinitionException`. factory bean name 이 election 빈 (`LeaderElection` 타입) 가리킬 때 `BeanNotOfRequiredTypeException` 케이스 [H-6] | medium | `.../integration/Boot3FactoryBeanNameTest.kt` | [H1][R-25] |
| T5.19 | Boot 3 — `failureMode` RETHROW + SKIP × 백엔드 throw 매트릭스 | medium | `.../integration/Boot3FailureModeIntegrationTest.kt` | |
| **T5.20** [M-4] | Boot 3 — 메트릭스 best-effort. 6 callback 순서: elected (`Attempt→Acquired→Started→Finished`) / skipped (`Attempt→NotAcquired`) / failed (`Attempt→Acquired→Started→Failed`). `List<Recorder>` 1개 throw 시 다른 recorder 영향 없음 (try-catch isolation) | medium | `.../integration/Boot3MetricsIntegrationTest.kt` | [M1][R-30] |
| **T5.21a** | Boot 3 — `LeaderAopHealthIndicator` 노출 검증 (Actuator `/actuator/health`) | low | `.../integration/Boot3HealthIntegrationTest.kt` | |
| **T5.21b** [L-3] | Boot 4 — Actuator `@WebMvcTest` security (Spring Security 7 변동 대응) — `/actuator/health` permitAll, `/actuator/beans` 인증 필요 패턴 | low | `leader-spring-boot4-aspectj/src/test/kotlin/.../integration/Boot4ActuatorSecurityTest.kt` | Boot 4 GA 재확인 |
| T5.22 | Boot 3 — proxy → target class annotation lookup 폴백 통합 (인터페이스 메서드 부착 + 구현체 분리) | medium | `.../integration/Boot3InterfaceAnnotationLookupTest.kt` | [R-24] |
| T5.23 | Boot 3 — 외부 advice 순서 검증 (`@LeaderElection` × `@Transactional`) — leader 락 → tx 시작 → body → tx 커밋 → 락 해제 순서 | medium | `.../integration/Boot3AdviceOrderTest.kt` | [R-14][M3] |
| T5.24 | Boot 3 — 전역 default property fallback 통합 (어노테이션 빈 → property → 코어 Default) | low | `.../integration/Boot3DefaultPropertyTest.kt` | |
| **T5.25** [H-8] | **Boot 4 × 6 backend 매트릭스** — Local + Lettuce + Redisson + Mongo + Hazelcast + Exposed JDBC. Boot 3 통합과 동일 시나리오 | medium | `leader-spring-boot4-aspectj/src/test/kotlin/.../integration/Boot4*AopIntegrationTest.kt` (6 파일) | Boot 4 GA 호환 |
| T5.26 | Boot 4 — Freefair AspectJ post-compile-weaving 적용 검증 (모듈 클래스 advice 매칭 + abstract class subclass weaving) | medium | `.../integration/Boot4AspectjWeavingTest.kt` | [4.1] R-2 정직 처리 + [Q-P2] |

---

## Phase 6 — 문서

README (영문 + 한글), CLAUDE.md, KDoc.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| **T6.1a** [H-2] | README 섹션 (a) — 사용법 + 설정 + plain SpEL 문법 (리터럴 prefix 따옴표). Quick Start, 어노테이션 reference, properties reference, Duration 두 형식, `${...}` placeholder | medium | `leader-spring-boot3-aop/README.md` | [H1][R-28] |
| **T6.1b** [H-2] | README 섹션 (b) — 한계 + 0.x 마이그레이션. sync only / 메타 어노테이션 [#84] / `minLeaseTime` [#77] / `FAIL_OPEN_RUN` [#81] / `TemplateParserContext` [#82] / metrics best-effort [#85] / self-invocation 한계 / factory 미등록 비활성 | medium | 동일 파일 | cross-ref |
| **T6.1c** [H-2][M-13][CR-4] | README 섹션 (c) — ShedLock 매핑 표 (8건 + `minLeaseTime`/`leaderId` 미지원) + 외부 advice 순서 표 (`@Transactional`, `@Retry`, `@Cacheable`) + factory bean name 강조 | medium | 동일 파일 | T6.7 흡수 [M-13] |
| T6.2 | `README.ko.md` — T6.1a/b/c 한글 번역 | medium | `leader-spring-boot3-aop/README.ko.md` | |
| T6.3 | Boot 4 README + `.ko.md` (boot3-aop + Freefair + Spring 7 안내) | medium | `leader-spring-boot4-aspectj/README.md`, `.ko.md` | |
| T6.4 | Common README + `.ko.md` 갱신 — 신규 SPI + abstract class Aspect 안내 | low | `leader-spring-boot-common/README.md`, `.ko.md` | |
| **T6.5a** [H-12] | 6 backend README 갱신 — factory class 위치 + 빈 이름 (`{backend}LeaderElectionFactory`) + 사용 예시 | low | 6 backend README + `.ko.md` (12개) | [Q-P1 (b)] |
| **T6.5b** [H-12] | 루트 README + `.ko.md` 갱신 — 신규 두 module + AOP 사용법 요약 | low | `README.md`, `README.ko.md` | |
| **T6.5c** [H-12] | `CLAUDE.md` 갱신 — stale 항목 정정 (`(planned)` 제거: exposed/mongodb/hazelcast), `leader-spring-boot-common` 등록, 신규 두 module 추가, AOP 가이드 | low | `CLAUDE.md` | |
| **T6.6** [M-12] | KDoc 한국어 + 신규 public API 매핑 표 (어노테이션 2/enum 3/SPI 4/Aspect 2/properties 1/recorder 1/health 1). `bean`="factory bean name", `name`="plain SpEL 리터럴 prefix 따옴표" 강조 | medium | 모든 신규/변경 `.kt` | [H1][R-25][R-28] |

(T6.7 제거 — T6.1c 로 흡수 [M-13])

---

## Phase 7 — CI

nightly 워크플로우에 신규 module job 추가 + Kover threshold.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| **T7.0** [M-14] | **`nightly.yml` 기존 job pattern + module job naming convention 확인 task** — `:leader-redis-redisson` job 이 어떤 trigger / matrix / setup-java 패턴을 쓰는지 grep 후 신규 module job 일관성 보장 | low | `.github/workflows/nightly.yml` (read-only verify) | |
| T7.1 | `nightly.yml` 에 `leader-spring-boot3-aop` job 추가 (build + test) — T7.0 의 패턴 그대로 | low | `.github/workflows/nightly.yml` | |
| T7.2 | `nightly.yml` 에 `leader-spring-boot4-aspectj` job 추가 | low | `.github/workflows/nightly.yml` | |
| T7.3 | `ci.yml` 에 신규 module 빌드 포함 검증 (이미 `./gradlew build` 일괄 실행이면 변경 불필요) | low | `.github/workflows/ci.yml` | verify only |
| **T7.4** [M-7] | **Kover line coverage 80% threshold 3 모듈 적용** — `leader-spring-boot-common`, `leader-spring-boot3-aop`, `leader-spring-boot4-aspectj`. `koverHtmlReport` task 활성화 + `koverVerify` 실패 시 build fail | low | 위 3 모듈 `build.gradle.kts` | Kover plugin 이미 root 적용 가정 |

---

## Phase 8 — Pre-PR Quality Gate (신규) [CR-5]

PR 생성 직전 6중 코드 리뷰 + bluetape4k-patterns 점검 + detekt/Kover threshold 일괄 검증. memory `feedback_pr_code_review` 강제 준수.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| **T8.1** [CR-5] | 6중 코드 리뷰 trigger — Step 6-R 6-Tier (`code-reviewer`, `security-reviewer`, `kotlin-expert`, `senior-architect`, `coroutines-kotlin`, `kotlin-spring`) 병렬. CRITICAL/HIGH 0 후 PR. memory `feedback_pr_code_review` | high | 산출물: 리뷰 리포트 6개 | 영구 규칙 |
| **T8.2** [CR-5][M-11] | bluetape4k-patterns checklist — argument validation, KLogging companion, AtomicFU (class-property), DSL builder, value object, magic literal. 신규/변경 `.kt` 모두 대상 | medium | 산출물: checklist 결과 | M-11 흡수 |
| **T8.3** [CR-5] | `./gradlew detekt koverVerify` 일괄 검증 — 실패 시 PR 차단. baseline 갱신 금지 | low | 산출물: 통과 로그 | T7.4 정합 |

---

## 의존성 그래프

```
Phase 1 (코어 SPI + 6 백엔드 factory)
  └─→ Phase 2 (공통 AOP 인프라 + abstract class Aspect 본체)
        ├─→ Phase 3 (Boot 3 AOP) ───┐
        └─→ Phase 4 (Boot 4 AspectJ)─┤
                                     └─→ Phase 5 (테스트)
                                          └─→ Phase 6 (문서) + Phase 7 (CI)  [병렬]
                                                                        └─→ Phase 8 (Pre-PR Quality Gate)
```

- Phase 1: T1.1/T1.2 → T1.3–T1.8 병렬, T1.9 마지막
- Phase 2: T2.1–T2.6 병렬, T2.7–T2.12/T2.18 병렬, T2.10 단독 high. T2.13–T2.16 = T2.10+T2.12 의존 (별도 표). T2.19/T2.20 = T2.13–T2.16 후. T2.21 단독
- Phase 3 / Phase 4: 병렬. T3.5a → T3.5b 순차, T3.6/T3.7 후속. T4.6 verify 별도
- Phase 5: 단위 → 통합. 6 backend 병렬. Boot 3/4 통합 병렬
- Phase 6 / Phase 7: 병렬 (Phase 5 후)
- Phase 8: Phase 6+7 후 PR gate

---

## 작업량 추정

- **신규 파일**: 약 102개 (Phase 1: 14, Phase 2: 22, Phase 3: 8, Phase 4: 9, Phase 5: 38, Phase 6: 14, Phase 7/8: 0)
- **수정 파일**: 약 16개 (`settings.gradle.kts`, `leader-bom`, `CLAUDE.md`, `nightly.yml`, `ci.yml`, common README, 3 신규 모듈 build + Kover, root README × 2)
- **코드 라인**: 약 7,200 (구현 3,100 + 테스트 3,500 + 문서 600)
- **총 task 수**: **96개** (high 10 / medium 45 / low 41)
- **예상 시간**: 32–40 시간 (통합 + 디버깅 + 6중 리뷰 + verify 포함)

---

## 후속 이슈 cross-ref (본 PR 미포함)

| 이슈 | 본 PR 미포함 | 본 PR 영향 |
|------|------------|-----------|
| #72 | `leaderId` 필드 + 코어 group 시그니처 | 어노테이션 필드 미정의 |
| #73 | watchdog / lease auto-extend | `elapsed > leaseTime*0.8` WARN 만 [H10] |
| #74 | `Flux`/`Flow` 반환 — [#80] 흡수 | BeanPostProcessor 검출 + #80 안내 |
| #75 | Micrometer 구현 + Observation Span | `LeaderAopMetricsRecorder` 6 콜백 SPI + `NoOp` 만 |
| #76 | spring-boot 디렉토리 재구조화 | 기존 모듈 유지 [Q5][Q6] |
| #77 | `minLeaseTime` + 코어 옵션 | 어노테이션 필드 미정의 [R-23] |
| #78 | 클래스 레벨 `@LeaderElectionBackend` | 본 PR 미지원 |
| #79 | `LockExtender`/`LockAssert` | advice 단일 진입만 |
| **#80** | suspend/`Mono`/`Flow` + R2DBC factory + `SuspendLeaderElectionFactory` SPI + reactor 의존성 | sync `T?` only — BPP 가 startup fail/WARN |
| **#81** | `FAIL_OPEN_RUN` + `LeaderResult<T>` sealed | `RETHROW`/`SKIP` 만 |
| **#82** | SpEL `TemplateParserContext` 혼합 표현식 | plain SpEL 만 |
| **#84** | 메타 어노테이션 (`@AliasFor` + `AnnotationMatchingPointcut`) | `@annotation(LeaderElection)` 직접 부착만 [C2][R-27] |
| **#85** | sealed `LeaderRunResult { Elected, Skipped }` | metrics best-effort [M1][R-30] |
| **#NEW** [CR-3] | `META-INF/spring/aot.factories` (Boot 3.5+ AOT) | scope 외, Notes only |

v2 후속: `nameProvider: KClass` 컴파일타임 검증 [Q2][M5], AspectJ LTW (self-invocation 근본 해결, R-3).

---

## 리스크 (PR 진행 중 verify)

전체 위험 표는 spec §2. 본 plan 은 빌드/구현 시 verify 필요 항목만.

| 리스크 | 완화 task |
|--------|----------|
| Freefair AspectJ 호환성 (Boot 4 / Kotlin 2.3 / JVM 21 / Spring 7 + abstract class weaving) | T4.2 verify [Q-P2] |
| Boot 4 GA `@AutoConfiguration` API 변경 | T4.6 verify |
| Spring 6/7 API drift (`SimpleEvaluationContext.withMethodResolvers()`, `BeanFactoryResolver`) | T2.21 verify [Q-P3] |
| 6 backend 본문 throw 시 unlock 미보장 (CRITICAL) | T1.9 base + T5.12 회귀 |
| 다중 factory `@ConditionalOnBean` | T3.5a/T4.5a + `bean` 필드 |
| `kotlin-reflect` 미존재 시 `#argName` 실패 | T2.17 검출 + T2.23 의존성 [CR-2] |
| metrics CONTENTION best-effort | T6.1b LIMITATIONS + #85 |
| self-invocation 정확 검출 불가 | best-effort WARN + T6.1b LIMITATIONS |
| AOP order × `@Transactional`/`@Retry`/`@Cacheable` | T5.23 + T6.1c 표 |
| AOT compilation (`aot.factories`) 미지원 | T3.7/T4.8 Notes [CR-3] |

DoD 체크리스트는 spec §12 + 아래 신규 task DoD 항목 추가.

---

## DoD — 신규 task 추가 항목 (spec §12 보완)

- [ ] T2.13–T2.16 4 task 구현 + 단위 테스트 [H-4]
- [ ] T2.19/T2.20 abstract class Aspect — Boot 3/4 빈 subclass 어노테이션만 [Q-P2]
- [ ] T2.21 Spring 6/7 API verify 노트 작성 [Q-P3]
- [ ] T3.5b/T4.5b 6 backend × 2 factory = 12 `@Bean` 등록 + `@ConditionalOnClass(BackendClient)` [Q-P1][H-9]
- [ ] T4.6 Boot 4 GA `@AutoConfiguration` verify
- [ ] T5.13a/T5.14a Boot 3 6 backend cover (Lettuce + Hazelcast) [CR-1]
- [ ] T5.21b Boot 4 Actuator `@WebMvcTest` security [L-3]
- [ ] T5.25 Boot 4 매트릭스 6 backend [H-8]
- [ ] T6.1a/b/c README 3 섹션 [H-2]
- [ ] T6.5a/b/c 6 backend README + 루트 + CLAUDE.md [H-12]
- [ ] T6.6 KDoc 한국어 + 신규 public API 매핑 표 [M-12]
- [ ] T7.0 nightly pattern 일관성 확인 [M-14]
- [ ] T7.4 Kover 80% threshold + `koverVerify` [M-7]
- [ ] T8.1 6중 코드 리뷰 (CRITICAL/HIGH 0) [CR-5]
- [ ] T8.2 bluetape4k-patterns checklist [CR-5][M-11]
- [ ] T8.3 `detekt + koverVerify` PR gate [CR-5]

분리/신규 task 산출물 요약:

| Task | 산출물 |
|------|--------|
| T2.13/T2.15 | `Abstract*Aspect.aroundLeader()` + factoryCache + 4 KLogging events |
| T2.14/T2.16 | `Abstract*Aspect.afterSingletonsInstantiated()` SpEL pre-parse |
| T2.19/T2.20 | `abstract class Abstract*Aspect : SmartInitializingSingleton` 선언 (비-`@Aspect`) |
| T2.21 | `docs/superpowers/notes/2026-05-04-spring6-vs-spring7-verify.md` |
| T3.5a / T4.5a | AutoConfig shell (`@AutoConfiguration` + 조건부) |
| T3.5b / T4.5b | 12 backend factory `@Bean` + Aspect/Validator/Health/Properties/SpEL `@Bean` |
| T4.6 | `docs/superpowers/notes/2026-05-04-boot4-autoconfig-verify.md` |
| T6.1a/b/c | README 3 섹션 (사용법 / 한계+마이그레이션 / ShedLock+advice 순서) |
| T6.5a/b/c | 6 backend README + 루트 README + CLAUDE.md |
| T8.1 | 6 sub-agent 리뷰 리포트 |
| T8.2 | bluetape4k-patterns checklist 결과 |
| T8.3 | `./gradlew detekt koverVerify` 통과 로그 |

---

## Step 3-R critic 결정 적용 요약

| 카테고리 | 건수 | 주요 변경 |
|---------|-----|----------|
| HIGH (12) | 12 | T3.5/T4.5/T6.1/T6.5 split, T2.13/T2.14 → 4 task, factory 위치 (Q-P1), abstract class Aspect (Q-P2), Spring 6/7 verify (Q-P3), Boot 4 6 backend cover, `BeanNotOfRequiredTypeException`, SpEL null |
| MEDIUM (15) | 15 | complexity (T1.3, T1.9) 조정, footgun 12 case, metrics 6 callback + isolation, PBT 도입, junit-platform/logback 표준, Kover threshold, fault 24 case, factoryCache 필드, 빈 이름 표준, mock clock lease-warn |
| LOW (4) | 4 | KLogging 4 events, T5.21b Boot 4 Actuator security, snapshot 선택, sync 명시 |
| Critic 추가 (5) | 5 | CR-1 6 backend (Lettuce/Hazelcast), CR-2 kotlin-reflect, CR-3 aot.factories Notes, CR-4 advice 표 확장, CR-5 Phase 8 신규 |
| 사용자 결정 | 4 | Q-P1 (b), Q-P2 (a), Q-P3 (b), Q-P4 (변경 없음) |

**Spec 일관성**: sync only / `RETHROW+SKIP` / plain SpEL / 메타 어노테이션 [#84] / factory bean name 명확 / `FactoryCacheKey` / factory-only 조건 / best-effort metrics & self-inv / flat naming — 모두 유지.

후속 이슈 cross-ref 13건 (#72-#82, #84, #85) + #NEW (`aot.factories`) Notes 유지.
