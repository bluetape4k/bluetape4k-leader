# Plan — `@LeaderElection` / `@LeaderGroupElection` AOP (sync only)

**Spec**: `docs/superpowers/specs/2026-05-04-leader-aop-design.md`
**Issue**: #41 (#69 SpEL 흡수)
**Branch**: `feat/leader-aop`
**작성일**: 2026-05-04
**Scope 결정**: 본 PR 은 sync (`T?`) 어드바이스만 다룬다. suspend / `Mono` / `Flow` / Reactor / R2DBC factory 는 후속 [#80].

본 plan 은 spec §4–§11 의 채택 결정을 그대로 따른다. 각 task 는 `complexity: high|medium|low` 라벨 + 영향 파일 (worktree 상대경로) + 비고 (관련 spec §, R-id, 후속 이슈) 를 포함한다.

---

## Phase 1 — 코어 SPI 확장 (`leader-core` + 6 백엔드)

per-call options 를 깨끗하게 처리하기 위해 `LeaderElectionFactory` / `LeaderGroupElectionFactory` SPI 를 코어에 추가하고, 6 sync 백엔드에 factory 구현을 등록한다 [C1][R-20][R-26]. AOP 가 `FactoryCacheKey(factoryBeanName, options)` 로 election instance 를 캐싱한다.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T1.1 | `LeaderElectionFactory` interface — `fun create(options: LeaderElectionOptions): LeaderElection` | medium | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionFactory.kt` | sync only — suspend factory 는 [#80] |
| T1.2 | `LeaderGroupElectionFactory` interface — `fun create(options: LeaderGroupElectionOptions): LeaderGroupElection` | medium | `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElectionFactory.kt` | sync only |
| T1.3 | Local backend factory 2종 (in-process) | medium | `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElectionFactory.kt`, `.../local/LocalLeaderGroupElectionFactory.kt` | 기존 `Local*Election` 재사용 |
| T1.4 | Lettuce backend factory 2종 | medium | `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceLeaderElectionFactory.kt`, `.../LettuceLeaderGroupElectionFactory.kt` | 동일 connection 공유 |
| T1.5 | Redisson backend factory 2종 | medium | `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/RedissonLeaderElectionFactory.kt`, `.../RedissonLeaderGroupElectionFactory.kt` | `RedissonClient` 재사용 |
| T1.6 | MongoDB sync factory 2종 | medium | `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElectionFactory.kt`, `.../MongoLeaderGroupElectionFactory.kt` | `MongoClient` 재사용. R2DBC/reactive 는 [#80] |
| T1.7 | Hazelcast backend factory 2종 | medium | `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElectionFactory.kt`, `.../HazelcastLeaderGroupElectionFactory.kt` | `HazelcastInstance` 재사용 |
| T1.8 | Exposed JDBC backend factory 2종 | medium | `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectionFactory.kt`, `.../ExposedJdbcLeaderGroupElectionFactory.kt` | sync only — Exposed R2DBC factory 는 [#80] |
| T1.9 | 백엔드 unlock contract 회귀 테스트 base — 본문 throw 시 try-finally unlock 검증 (T5.12 의 base 클래스) | high | `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/AbstractLeaderUnlockContractTest.kt` | [C-4][R-19] DoD CRITICAL |

---

## Phase 2 — 공통 AOP 인프라 (`leader-spring-boot-common`)

어노테이션, SpEL evaluator, Aspect 골격, footgun 검출 BeanPostProcessor, metrics SPI, HealthIndicator. Spring 의존성 추가 (`spring-aop`, `spring-expression`, Caffeine).

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
| T2.13 | `AbstractLeaderElectionAspect` — `@Around` advice 골격 (sync `T?` only), pre-parse `SmartInitializingSingleton`, metrics fan-out, RETHROW/SKIP 분기, lease warn (`elapsed > leaseTime*0.8`) | high | `.../aop/AbstractLeaderElectionAspect.kt` | [H10] |
| T2.14 | `AbstractLeaderGroupElectionAspect` — 동일 패턴, `LeaderGroupElectionFactory` 사용 + `maxLeaders` | high | `.../aop/AbstractLeaderGroupElectionAspect.kt` | |
| T2.15 | `LeaderAnnotationValidatorBeanPostProcessor` — final/private/maxLeaders/suspend/reactive/SpEL pre-parse 검출 + best-effort self-invocation WARN | medium | `.../aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` | [H5][R-22][R-31][M2] |
| T2.16 | `LeaderAopProperties` — `enabled`, `strict`, `failureMode`, `defaultLeaseTime`, `defaultWaitTime` | low | `.../aop/properties/LeaderAopProperties.kt` | `default-min-lease-time` 미지원 [#77] |
| T2.17 | `LeaderAopHealthIndicator` — advice 메서드 수, SpEL cache size, 누적 카운터 | low | `.../aop/health/LeaderAopHealthIndicator.kt` | [M4] |
| T2.18 | `leader-spring-boot-common/build.gradle.kts` 의존성 추가 — `spring-context`, `spring-aop`, `spring-expression`, Caffeine, `spring-boot-actuator` (optional) | low | `leader-spring-boot-common/build.gradle.kts` | |

---

## Phase 3 — Boot 3 AOP 모듈 (`leader-spring-boot3-aop`) — 신규 module [M3]

flat naming 신규 모듈. Spring AOP 런타임 프록시. concrete Aspect + AutoConfiguration + `AutoConfiguration.imports`.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T3.1 | `settings.gradle.kts` 에 `:leader-spring-boot3-aop` include | low | `settings.gradle.kts` | [M3] |
| T3.2 | `build.gradle.kts` — common + Boot 3 BOM + `spring-aop` | low | `leader-spring-boot3-aop/build.gradle.kts` | |
| T3.3 | `LeaderElectionAspect` (concrete, `@Aspect @Component @Order(AOP_ORDER) @Role(ROLE_INFRASTRUCTURE)`) | medium | `leader-spring-boot3-aop/src/main/kotlin/io/bluetape4k/leader/spring/boot3/aop/LeaderElectionAspect.kt` | `extends AbstractLeaderElectionAspect` |
| T3.4 | `LeaderGroupElectionAspect` (concrete) | medium | `.../boot3/aop/LeaderGroupElectionAspect.kt` | |
| T3.5 | `LeaderAopAutoConfiguration` — `@AutoConfiguration @ConditionalOnClass(Aspect) @ConditionalOnBean(LeaderElectionFactory) @ConditionalOnProperty(enabled, matchIfMissing=true) @EnableAspectJAutoProxy(proxyTargetClass=true)` + 모든 빈 `@Role(ROLE_INFRASTRUCTURE)` | medium | `.../boot3/aop/autoconfigure/LeaderAopAutoConfiguration.kt` | [H2][R-29] factory-only |
| T3.6 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | low | `leader-spring-boot3-aop/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | |
| T3.7 | `META-INF/spring-configuration-metadata.json` (또는 `additional-spring-configuration-metadata.json`) | low | `leader-spring-boot3-aop/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json` | IDE 자동완성 |
| T3.8 | `leader-bom/build.gradle.kts` 에 신규 module 추가 | low | `leader-bom/build.gradle.kts` | |

---

## Phase 4 — Boot 4 AspectJ 모듈 (`leader-spring-boot4-aspectj`) — 신규 module [M3]

flat naming 신규 모듈. AspectJ post-compile-weaving (Freefair 플러그인) 으로 모듈 자체 클래스 advice 매칭 검증. 사용자 빈에 대한 advice 는 동일 Spring AOP 런타임 프록시.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T4.1 | `settings.gradle.kts` 에 `:leader-spring-boot4-aspectj` include | low | `settings.gradle.kts` | [M3] |
| T4.2 | `build.gradle.kts` — common + Boot 4 BOM + `io.freefair.aspectj.post-compile-weaving` 적용 + `spring-aspects` + `aspectjweaver`/`aspectjrt` | medium | `leader-spring-boot4-aspectj/build.gradle.kts` | Freefair 버전 호환성 verify (Boot 4 / Kotlin 2.3 / JVM 21) |
| T4.3 | `LeaderElectionAspect` (concrete, Boot 3 와 동일 코드) | medium | `leader-spring-boot4-aspectj/src/main/kotlin/io/bluetape4k/leader/spring/boot4/aop/LeaderElectionAspect.kt` | |
| T4.4 | `LeaderGroupElectionAspect` (concrete) | medium | `.../boot4/aop/LeaderGroupElectionAspect.kt` | |
| T4.5 | `LeaderAopAutoConfiguration` — Boot 3 와 동일 조건 | medium | `.../boot4/aop/autoconfigure/LeaderAopAutoConfiguration.kt` | Boot 4 GA 시점 `@AutoConfiguration` API verify |
| T4.6 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | low | `leader-spring-boot4-aspectj/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | |
| T4.7 | `META-INF/spring/additional-spring-configuration-metadata.json` | low | `leader-spring-boot4-aspectj/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json` | |
| T4.8 | `leader-bom/build.gradle.kts` 에 신규 module 추가 | low | `leader-bom/build.gradle.kts` | |

---

## Phase 5 — 테스트

단위 + 통합 + 회귀 테스트. Kover line coverage ≥ 80% (3 모듈: common, boot3-aop, boot4-aspectj). 테스트 인프라는 `bluetape4k-testcontainers` 표준 (`XxxServer.Launcher`) 사용.

### 5.1 단위 테스트 (`leader-spring-boot-common`)

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T5.1 | `SpelExpressionEvaluatorTest` — literal fast-path, plain SpEL (`#region`, `#user.tenantId`, `"'process-' + #region"`), `${...}` placeholder, pre-parse 실패 메시지 (메서드 FQN 포함), 따옴표 없는 리터럴 prefix (`process-#region`) → fail, `T(...)` / 빈 참조 차단, Caffeine cache 1024 evict | medium | `leader-spring-boot-common/src/test/kotlin/io/bluetape4k/leader/spring/aop/spel/SpelExpressionEvaluatorTest.kt` | [Q4][R-16][R-17][R-28][H6] |
| T5.2 | `DurationParserTest` — ISO-8601 + simple 양쪽, 음수/0 거부, `leaseTime < waitTime` WARN | low | `.../aop/util/DurationParserTest.kt` | |
| T5.3 | `LockNameValidatorTest` | low | `.../aop/util/LockNameValidatorTest.kt` | |
| T5.4 | `FactoryCacheKeyTest` — equals/hashCode + cross-backend collision 방지 | low | `.../aop/cache/FactoryCacheKeyTest.kt` | [R-26] |
| T5.5 | `LeaderAnnotationValidatorBeanPostProcessorTest` — final/private/maxLeaders/suspend/reactive 위반 매트릭스 + strict 모드 fail vs default WARN + best-effort self-invocation WARN | medium | `.../aop/validator/LeaderAnnotationValidatorBeanPostProcessorTest.kt` | [H5][R-22][R-31] |
| T5.6 | Aspect 단위 테스트 base — mock `ProceedingJoinPoint`, fake `LeaderElection` + fake `LeaderElectionFactory` | high | `.../aop/AbstractLeaderElectionAspectTest.kt` | [M6] mock-only |
| T5.7 | `failureMode` 매트릭스 테스트 — RETHROW × 백엔드 throw / 본문 throw, SKIP × 동일 | medium | `.../aop/LeaderAspectFailureModeTest.kt` | |
| T5.8 | `LeaderBeanSelectorTest` — 단일 / `@Primary` / ambiguous → fail | medium | `.../aop/LeaderBeanSelectorTest.kt` | [R-13] |
| T5.9 | `LeaderAopHealthIndicatorTest` | low | `.../aop/health/LeaderAopHealthIndicatorTest.kt` | |

### 5.2 백엔드 factory 단위 테스트

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T5.10 | 6 backend factory 단위 테스트 — `factory.create(options).runIfLeader(name) { ... }` 정상/skip 경로 | medium | `leader-core/.../local/LocalLeaderElectionFactoryTest.kt`, `leader-redis-lettuce/.../LettuceLeaderElectionFactoryTest.kt`, `leader-redis-redisson/.../RedissonLeaderElectionFactoryTest.kt`, `leader-mongodb/.../MongoLeaderElectionFactoryTest.kt`, `leader-hazelcast/.../HazelcastLeaderElectionFactoryTest.kt`, `leader-exposed-jdbc/.../ExposedJdbcLeaderElectionFactoryTest.kt` | Testcontainers `XxxServer.Launcher` |
| T5.11 | 6 backend group factory 단위 테스트 — `LeaderGroupElectionFactory.create(opts)` `maxLeaders=N` | medium | 위 위치 group 변형 | |
| T5.12 | 백엔드 unlock contract 회귀 테스트 (6 백엔드) — 본문 `throw RuntimeException("X")` 후 즉시 다른 client 가 동일 lock 획득 가능 검증 | high | 각 backend `*UnlockContractTest.kt` (T1.9 base 상속) | [C-4][R-19] CRITICAL DoD |

### 5.3 통합 테스트 (`@SpringBootTest`)

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T5.13 | Boot 3 × Local backend 통합 — `@LeaderElection`, `@LeaderGroupElection`, plain SpEL, `${...}` placeholder, simple Duration | medium | `leader-spring-boot3-aop/src/test/kotlin/.../integration/Boot3LocalAopIntegrationTest.kt` | infra 없음 |
| T5.14 | Boot 3 × Redisson backend 통합 — `RedisServer.Launcher.redis` | medium | `.../integration/Boot3RedissonAopIntegrationTest.kt` | |
| T5.15 | Boot 3 × MongoDB backend 통합 — `MongoDBServer.Launcher.mongoDB` | medium | `.../integration/Boot3MongoAopIntegrationTest.kt` | sync only |
| T5.16 | Boot 3 × Exposed JDBC backend 통합 — h2 inline | medium | `.../integration/Boot3ExposedJdbcAopIntegrationTest.kt` | |
| T5.17 | Boot 3 — `@ConditionalOnBean(LeaderElectionFactory)` 비활성 검증 — factory 미등록 시 AOP autoconfig disabled + 마이그레이션 가이드 메시지 | medium | `.../integration/Boot3FactoryConditionalTest.kt` | [H2][R-29] |
| T5.18 | Boot 3 — `bean` 필드 factory 명시 + 잘못된 bean 이름 → `NoSuchBeanDefinitionException` 명확한 에러 | medium | `.../integration/Boot3FactoryBeanNameTest.kt` | [H1][R-25] |
| T5.19 | Boot 3 — `failureMode` RETHROW + SKIP × 백엔드 throw 통합 매트릭스 | medium | `.../integration/Boot3FailureModeIntegrationTest.kt` | |
| T5.20 | Boot 3 — 메트릭스 best-effort 검증 — 6 콜백 invoke + `List<LeaderAopMetricsRecorder>` 멀티 빈 fan-out | low | `.../integration/Boot3MetricsIntegrationTest.kt` | [M1][R-30] best-effort |
| T5.21 | Boot 3 — `LeaderAopHealthIndicator` 노출 검증 (Actuator `/actuator/health`) | low | `.../integration/Boot3HealthIntegrationTest.kt` | |
| T5.22 | Boot 3 — proxy → target class annotation lookup 폴백 통합 (인터페이스 메서드 부착 + 구현체 분리) | medium | `.../integration/Boot3InterfaceAnnotationLookupTest.kt` | [R-24] |
| T5.23 | Boot 3 — 외부 advice 순서 검증 (`@LeaderElection` × `@Transactional`) — leader 락 획득 → tx 시작 → body → tx 커밋 → 락 해제 순서 | medium | `.../integration/Boot3AdviceOrderTest.kt` | [R-14][M3] |
| T5.24 | Boot 3 — 전역 default property fallback 통합 (어노테이션 빈 → property → 코어 Default) | low | `.../integration/Boot3DefaultPropertyTest.kt` | |
| T5.25 | Boot 4 × 동일 backend 매트릭스 (Local + Redisson + Mongo + Exposed JDBC 핵심 4) — Boot 3 통합과 동일 시나리오 | medium | `leader-spring-boot4-aspectj/src/test/kotlin/.../integration/Boot4*AopIntegrationTest.kt` | Boot 4 GA 호환 |
| T5.26 | Boot 4 — Freefair AspectJ post-compile-weaving 적용 검증 (모듈 클래스 advice 매칭) | medium | `.../integration/Boot4AspectjWeavingTest.kt` | [4.1] R-2 정직 처리 |

---

## Phase 6 — 문서

README (영문 + 한글), CLAUDE.md, KDoc.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T6.1 | `leader-spring-boot3-aop/README.md` — 사용법 + 설정 + ShedLock 매핑 표 + 0.x 마이그레이션 가이드 + 한계 명시 (sync only / 메타 어노테이션 미지원 / `minLeaseTime` 미지원 / `FAIL_OPEN_RUN` 미지원 / `TemplateParserContext` 미지원 / metrics best-effort / self-invocation 정확 검출 한계 / factory 미등록 환경 비활성) + 외부 advice 순서 표 + plain SpEL 문법 (리터럴 prefix 따옴표) + `bean` factory bean name 강조 | medium | `leader-spring-boot3-aop/README.md` | [#72][#77][#80][#81][#82][#84][#85] cross-ref |
| T6.2 | `leader-spring-boot3-aop/README.ko.md` (한글) | medium | `leader-spring-boot3-aop/README.ko.md` | |
| T6.3 | `leader-spring-boot4-aspectj/README.md` + `README.ko.md` (boot3-aop 와 동일 + Freefair 플러그인 안내) | medium | `leader-spring-boot4-aspectj/README.md`, `.ko.md` | |
| T6.4 | `leader-spring-boot-common/README.md` + `README.ko.md` 갱신 — 새 SPI/평가기/BeanPostProcessor 추가 안내 | low | `leader-spring-boot-common/README.md`, `.ko.md` | |
| T6.5 | `CLAUDE.md` Repository Layout 갱신 — 신규 두 module 추가 + AOP 사용 가이드 (어노테이션 부착 → factory bean → strict 모드) | low | `CLAUDE.md` | |
| T6.6 | KDoc — 모든 public class/annotation/SPI (`@param`, `@sample`, 반환 매트릭스). `@LeaderElection.bean` KDoc 에 "factory bean name" 강조, `@LeaderElection.name` KDoc 에 "plain SpEL — 리터럴 prefix 는 따옴표" 명시 | low | 모든 신규/변경 `.kt` (Phase 1–4) | [H1][R-25][R-28] |
| T6.7 | ShedLock 매핑 표 README (8건 차용 항목 + `minLeaseTime`/`leaderId` 미지원 사유) — boot3-aop README 섹션으로 통합 | low | `leader-spring-boot3-aop/README.md` 섹션 | T6.1 의 일부 |

---

## Phase 7 — CI

nightly 워크플로우에 신규 module job 추가.

| Task | Description | Complexity | Files | Notes |
|------|-------------|------------|-------|-------|
| T7.1 | `nightly.yml` 에 `leader-spring-boot3-aop` job 추가 (build + test) | low | `.github/workflows/nightly.yml` | |
| T7.2 | `nightly.yml` 에 `leader-spring-boot4-aspectj` job 추가 | low | `.github/workflows/nightly.yml` | |
| T7.3 | `ci.yml` 에 신규 module 빌드 포함 검증 (이미 `./gradlew build` 일괄 실행이면 변경 불필요) | low | `.github/workflows/ci.yml` | verify only |

---

## 의존성 그래프

```
Phase 1 (코어 SPI + 6 백엔드 factory)
  └─→ Phase 2 (공통 AOP 인프라)
        ├─→ Phase 3 (Boot 3 AOP)  ──┐
        └─→ Phase 4 (Boot 4 AspectJ)─┤
                                     ├─→ Phase 5 (테스트)
                                     │     └─→ Phase 6 (문서) + Phase 7 (CI)  [병렬]
```

- Phase 1 (T1.1, T1.2 → T1.3–T1.8 6 백엔드 병렬). T1.9 는 T1.3–T1.8 후 회귀 테스트 base 로 작성
- Phase 2 — T2.1, T2.2, T2.3, T2.4, T2.5, T2.6 병렬 가능 (단순 어노테이션/enum). T2.7, T2.8, T2.9, T2.11, T2.12 병렬. T2.10 (SpEL) 단독 high. T2.13, T2.14 는 T2.10 + T2.12 의존. T2.15 는 T2.10 의존. T2.17, T2.18 단독
- Phase 3 / Phase 4 병렬 (서로 독립). 각 Phase 내 T3.3/T3.4 병렬, T3.5 후 T3.6/T3.7
- Phase 5 단위 (T5.1–T5.11) → 통합 (T5.12–T5.26). T5.10/T5.11/T5.12 6 백엔드 병렬
- Phase 6 / Phase 7 병렬 (Phase 5 완료 후)

---

## 작업량 추정

- **신규 파일**: 약 86개
  - Phase 1 코어/백엔드 factory: 14개 (12 factory + interface 2 + contract base 1)
  - Phase 2 공통: 18개
  - Phase 3 Boot 3 AOP module: 6개 (build + 4 src + 2 META-INF)
  - Phase 4 Boot 4 AspectJ module: 6개
  - Phase 5 테스트: 약 30개 (단위 9 + factory 12 + 통합 9 + Boot4 4)
  - Phase 6 문서: 6개 (README × 4 + KDoc inline)
  - Phase 7 CI: 0 (yml 갱신만)
- **수정 파일**: 약 10개 (`settings.gradle.kts`, `leader-bom`, `CLAUDE.md`, `nightly.yml`, `ci.yml`, common README, build.gradle.kts × 6 백엔드 — factory 등록 시점 빈 등록)
- **코드 라인**: 약 6,500
  - 구현 약 2,800 (코어 SPI 200 + 백엔드 factory 6×80 = 480 + 공통 AOP 1,500 + Boot3/4 concrete 600)
  - 테스트 약 3,200
  - 문서/주석 약 500
- **예상 시간**: 28–35 시간 (high 8 task ≈ 16h, medium 30 task ≈ 12h, low 28 task ≈ 4h, 통합 + 디버깅 + 6중 리뷰 + verify ≈ 8h)

---

## 후속 이슈 cross-ref (본 PR 미포함)

| 이슈 | 본 PR 미포함 항목 | 본 PR 영향 |
|------|------------------|-----------|
| #72 | `@LeaderElection.leaderId` 필드 + 코어 group `runIfLeader(name, leaderId, action)` 시그니처 | 어노테이션 필드 자체 미정의 |
| #73 | watchdog / lease auto-extend | `elapsed > leaseTime*0.8` WARN 만 [H10] |
| #74 | `Flux<T>` / `Flow<T>` 반환 — [#80] 의 일부로 흡수 | BeanPostProcessor reactive 검출 + #80 안내 |
| #75 | Micrometer 실제 구현 + ObservationRegistry Span | 본 PR `LeaderAopMetricsRecorder` 6 콜백 SPI + `NoOp` 만 |
| #76 | spring-boot 디렉토리 재구조화 (`spring-boot3/{aop,autoconfig}` 등) | 기존 `leader-spring-boot3`/`-boot4` 그대로 유지 [Q5][Q6] |
| #77 | `minLeaseTime` 백엔드 TTL 위임 + 코어 `LeaderElectionOptions.minLeaseTime` | 어노테이션 필드 자체 미정의 [R-23] |
| #78 | 클래스/패키지 레벨 `@LeaderElectionBackend` 메타 어노테이션 | 본 PR 미지원 |
| #79 | `LockExtender` / `LockAssert` (reentrant + 명시적 lease 연장 API) | 본 PR advice 단일 진입만 보장 |
| **#80** | **suspend / `Mono<T>` / `Flow<T>` 반환 + R2DBC 백엔드 factory + `SuspendLeaderElectionFactory` SPI + `kotlinx-coroutines-reactor` 의존성** | 본 PR sync `T?` only — BeanPostProcessor 가 startup fail/WARN with #80 안내 |
| **#81** | `FAIL_OPEN_RUN` failureMode + `LeaderResult<T>` sealed wrapper | enum `RETHROW`, `SKIP` 만 |
| **#82** | SpEL `TemplateParserContext` 혼합 표현식 (`"prefix-#{userId}-suffix"`) 옵션 | plain SpEL 만 |
| **#84** | 메타 어노테이션 (`@AliasFor`) — `@JobLock` 같은 사용자 정의 — `AnnotationMatchingPointcut` advisor | pointcut `@annotation(LeaderElection)` 직접 부착만 매칭 [C2][R-27] |
| **#85** | 백엔드 SPI `LeaderRunResult<T> { Elected(value), Skipped }` sealed wrapper — 정확한 elected vs skipped 분리 | metrics `onLockNotAcquired(CONTENTION)` best-effort 만 [M1][R-30] |

v2 후속: `nameProvider: KClass` 컴파일타임 검증 [Q2][M5], AspectJ LTW (self-invocation 근본 해결, R-3).

---

## 리스크 (PR 진행 중 verify)

전체 위험 표는 spec §2. 본 plan 은 빌드/구현 시 verify 필요한 항목만 — 완화는 spec 결정 그대로.

| 리스크 | 완화 task |
|--------|----------|
| Freefair AspectJ Gradle 플러그인 호환성 (Boot 4 / Kotlin 2.3 / JVM 21) | T4.2 verify |
| Boot 4 GA `@AutoConfiguration` API 변경 | T4.5 verify |
| `SimpleEvaluationContext.withMethodResolvers()` Spring 6.2 시그니처 | T2.10 verify (Boot 3.5 BOM) |
| 기존 6 백엔드 본문 throw 시 unlock 미보장 (DoD CRITICAL) | T1.9 base + T5.12 회귀 — 발견 시 백엔드 수정 task 추가 |
| 다중 factory 환경 `@ConditionalOnBean` 매칭 | T3.5/T4.5 — factory 1+ 시 활성, 다중은 `bean` 필드로 선택 |
| `kotlin-reflect` 미존재 시 `#argName` 평가 실패 | T2.15 startup 검출, 의존성 명시 |
| metrics CONTENTION best-effort [M1][R-30] | README LIMITATIONS + #85 안내 |
| self-invocation 정확 검출 불가 [M2][R-31] | best-effort WARN + README LIMITATIONS, 별도 빈 추출 권장 |
| AOP order × `@Transactional`/`@Retry` 충돌 | T5.23 통합 + README 외부 advice 순서 표 |

DoD 체크리스트는 spec §12 그대로 따른다 — Phase 5/6/7 task 가 모든 DoD 항목을 커버.
