# Design — `@LeaderElection` / `@LeaderGroupElection` AOP

**작업 유형**: Type-A Full Design (신규 기능 + 모듈 추가)
**관련 이슈**: #41 (어노테이션 기반 AOP, #69 SpEL 흡수)
**작성일**: 2026-05-04
**브랜치**: `feat/leader-aop`
**Step 1-R 검증**: 완료 (위험 8건, 인터페이스 시그니처, AspectJ CTW/LTW 차이, SpEL parser 캐싱)
**Step 2-R 검증**: 완료 (Critic 9건 + Codex 6건 + CRITICAL 추가 2건 모두 사용자 승인)
**Step 3-R 갱신 (ShedLock 차용 8건 + `minLeaseTime` 제거)**: 완료
**FINAL 결정 — sync only**: 본 PR은 **순수 sync `T?` 반환**만 다룬다. suspend / `Mono<T>` / `Flow<T>` / Reactor 분기, `kotlinx-coroutines-reactor` 의존성, `LeaderElectionInfo` CoroutineContext, `SuspendLeaderElectionFactory` SPI, R2DBC 백엔드 — **모두 본 PR 제외 → 후속 [#80]** (사용자 승인). `FAIL_OPEN_RUN` failureMode 도 [#81] 후속, `TemplateParserContext` 혼합 표현식 도 [#82] 후속
**Codex 2nd round 7건 갱신 (사용자 승인)**: [C1] factory cache key → `FactoryCacheKey(factoryBeanName, options)`, [C2] 메타 어노테이션 (`@AliasFor`/`@JobLock`) 본 PR 제거 → 후속 [#84], [H1] SpEL plain 문법 — 리터럴 prefix는 따옴표 (`"'process-' + #region"`), [H2] `@ConditionalOnBean(LeaderElectionFactory)` factory-only 고정, [M1] metrics best-effort + 정확한 elected/skipped 분리 → 후속 [#85], [M2] self-invocation best-effort WARN (정확 검출 불가 — AspectJ LTW 또는 ASM 필요), [M3] 신규 module flat naming `leader-spring-boot3-aop`, `leader-spring-boot4-aspectj`

---

## 1. 문제 재정의

ShedLock의 `@SchedulerLock` 같은 어노테이션 기반 분산 락 사용성을 본 라이브러리에 도입한다. 사용자는 `@Scheduled`, `@KafkaListener`, REST 컨트롤러, 일반 서비스 메서드 어디든 `@LeaderElection(name = "...")` 또는 `@LeaderGroupElection(name = "...", maxLeaders = N)` 한 줄을 추가하여 분산 환경에서 단일/다중 리더 진입만 실제 본문을 수행하도록 강제할 수 있어야 한다.

### 사용자 facing API (sync only)

- `@LeaderElection(name, leaseTime, waitTime, bean, failureMode)` — 단일 리더, `name`만 SpEL 평가 [C5][C6]. `minLeaseTime` 본 PR 제거 → 후속 [#77]. `leaderId` 본 PR 제거 → 후속 [#72]
- `@LeaderGroupElection(name, maxLeaders, leaseTime, waitTime, bean, failureMode)` — 다중 리더, `maxLeaders` 필수 (≥2)
- **반환 타입 매트릭스: `T?` (sync) only** — suspend / `Mono<T>` / `Flux<T>` / `Flow<T>` 모두 본 PR 제외, 후속 [#80]
- 미선출 시 매핑: `T?` → `null` (ShedLock skip-on-contention 동등)

### 통합 지점

- **본 PR 신규 module 2개 — flat naming** [M3]:
  - `leader-spring-boot3-aop` (Spring AOP 런타임 프록시)
  - `leader-spring-boot4-aspectj` (AspectJ 빌드 시 도구)
  - artifact ID: `io.github.bluetape4k.leader:leader-spring-boot3-aop` / `...:leader-spring-boot4-aspectj` (flat naming 일관성)
  - `settings.gradle.kts` 에 `include(":leader-spring-boot3-aop", ":leader-spring-boot4-aspectj")` 추가
- 공통 모듈 `leader-spring-boot-common` 확장 (어노테이션, Aspect 골격, SpEL 평가기, Duration 검증, SPI factory)
- 기존 `leader-spring-boot3`, `leader-spring-boot4` 모듈 그대로 유지 [Q6]
- 디렉토리 재구조화 (`spring-boot3/{aop,autoconfig}` 등) 는 본 PR 분리 → 별도 [#76]

### 미지수와 가정

- (가정) AspectJ `@Aspect` 클래스는 Spring AOP에서도 동일 코드로 동작 — Step 1-R에서 확인
- (가정) 6 백엔드(`local`, `lettuce`, `redisson`, `mongodb` sync, `hazelcast`, `exposed-jdbc`)의 `runIfLeader` 본문 throw 시 try-finally로 unlock 보장 — Section 4.2 백엔드 unlock 계약 + 회귀 테스트 DoD에서 강제

### 본 PR scope 결정 근거

suspend / Reactor 분기는 (a) `kotlinx-coroutines-reactor` 누출 (R-6), (b) `Mono.empty()` vs `null` 매핑 모호 (R-1), (c) subscribe당 락 1회 보장 (R-18) — 세 critical risk 가 모두 sync 어드바이스와 독립이며 별도 설계 토론이 필요. 본 PR은 sync 어드바이스 + 6 백엔드 SPI 캐싱만으로 ShedLock-equivalent 사용성 확보 → suspend/Mono는 [#80] 에서 별도 설계.

---

## 2. 위험 / 실패 모드

본 PR sync only 결정으로 R-1 / R-6 / R-10 / R-18 (subscribe당 락) 은 **#80 후속으로 이전**, R-23 (Aspect Thread.sleep) 은 minLeaseTime 제거로 무관해짐.

| ID | 위험 | 출처 | 영향 | 완화 |
|----|------|------|------|------|
| R-2 | "Boot4 = AspectJ CTW" 결정의 기술적 한계: CTW는 aspect 모듈만 위빙. 사용자 빈 advice는 런타임 Spring AOP 프록시 사용 | Step 1-R + advisor | CRITICAL | Section 4.1에 정직 명시. 순수 CTW 강제 안 함 |
| R-3 | Self-invocation 한계 (같은 빈 내부 메서드 호출 시 프록시 우회) | Step 1-R #2 | MEDIUM | Section 10 BeanPostProcessor 검출 (선언 클래스 내 호출 정적 분석) + README 가이드 |
| R-4 | SpEL 결과로 동적 lock name 생성 시 길이/문자셋 위험 | Step 1-R #3 | MEDIUM | `LockNameValidator`를 SpEL 결과에도 적용 |
| R-5 | Kotlin 메서드 파라미터 이름이 `-parameters` 미적용 시 `arg0` 폴백 → SpEL `#userId` 미해결 | Step 1-R #4 | HIGH | `kotlin-reflect` 의존 + `#a0`/`#p0` 인덱스 fallback 항상 노출 |
| R-7 | `leaseTime < waitTime` 사용자 실수 | Step 1-R #6 | LOW | aspect 초기화 시점 `log.warn` |
| R-8 | AspectJ LTW 운영 부담 | Step 1-R #7 | HIGH (LTW 채택 시) | LTW 미채택 — Spring AOP 프록시 기본 |
| R-9 | Spring 6/7 간 `proxyTargetClass` 기본값 변경 가능성 | Step 1-R #8 | LOW | `@EnableAspectJAutoProxy(proxyTargetClass = true)` 명시 |
| R-11 | 정적 리터럴 매 호출 SpEL 평가 오버헤드 | advisor | LOW | 리터럴 fast-path + 캐시 |
| R-12 | 디렉토리 재구조화가 본 PR 비대화 | advisor | MEDIUM | [#76]로 분리 [Q5] |
| R-13 | 다중 백엔드 `getBean(LeaderElection::class)` ambiguous | advisor | HIGH | `bean: String = ""` literal-only [M1] + `@Primary` 폴백 |
| R-14 | `@LeaderElection` × `@Transactional` 우선순위 | advisor | HIGH | `LeaderAspectOrder.AOP_ORDER` 상수 [M3] + 외부 advice 표 |
| R-15 | Flux 의사코드 `toList()` 버퍼링 OOM | advisor | OBSOLETE | sync only 결정으로 Flux 자체 제외 [#80] |
| **R-16** | **SpEL injection — 시스템 타입/빈 호출로 임의 코드 실행** | Critic Q4 | **CRITICAL** | `SimpleEvaluationContext.forPropertyAccessors().withMethodResolvers()` 사용. `BeanFactoryResolver`/`StandardEvaluationContext` 금지 [Q4][C1] |
| **R-17** | **SpEL cache DoS — 동적 표현식 폭증으로 OOM** | Critic Q4 | **CRITICAL** | Caffeine `maximumSize(1024) + expireAfterAccess(1h)` [C2] |
| **R-19** | **본문 throw 시 백엔드 unlock 누락 → lease 만료까지 데드락** | Critic A2 | **CRITICAL** | Section 4.2 contract 행 + 백엔드 회귀 테스트 DoD [C-4]. 6 백엔드 모두 try-finally로 unlock 보장 검증 |
| **R-20** | **per-call options 코어 API 부재 — 어노테이션 옵션을 매 호출 전달할 수단 없음** | Codex C1 | **CRITICAL** | `LeaderElectionFactory(options): LeaderElection` SPI 도입 [C1]. AOP가 어노테이션 옵션마다 backend instance 생성 + `ConcurrentHashMap<Options, LeaderElection>` 캐싱 |
| **R-21** | ~~`minLeaseTime` 코어 옵션 부재 — Aspect sleep 패턴~~ | Codex C2 | OBSOLETE | **본 PR 제거** — 후속 [#77] |
| **R-22** | **Footgun: final/private 메서드, self-invocation, `maxLeaders ≤ 1`** | Critic Q9 | HIGH | `LeaderAnnotationValidatorBeanPostProcessor` 검출 [H5]. property `bluetape4k.leader.aop.strict=true` 시 fail-fast, default WARN [Q9] |
| **R-24** | **인터페이스 메서드에만 어노테이션 부착 시 `method.getAnnotation()` 결과 누락 가능 — 프록시 메서드는 인터페이스 메서드라 직접 lookup 실패** | ShedLock `SpringLockConfigurationExtractor.java:212-229` | HIGH | `findAnnotationWithTargetFallback()` — 1차: `AnnotatedElementUtils.findMergedAnnotation(method)`, 2차 폴백: `AopUtils.getTargetClass(target).getMethod(name, paramTypes)` 재탐색. ShedLock 패턴 차용 |
| **R-25 NEW** | **`bean` 필드를 election 빈 이름으로 오해 — 실제로는 factory 빈 이름** | Codex H1 | HIGH | spec / KDoc / README 모두 "factory bean name" 명시. 예: `bean = "redissonLeaderElectionFactory"` (✅) / `bean = "redissonLeaderElection"` (✗) [H1] |
| **R-26 NEW** | **factory cache key collision — `ConcurrentHashMap<LeaderElectionOptions, LeaderElection>` 는 다중 백엔드 시 다른 factory 의 동일 옵션 instance 를 잘못 재사용** | Codex 2nd round C1 | **CRITICAL** | cache key 를 `data class FactoryCacheKey(val factoryBeanName: String, val options: LeaderElectionOptions)` 로 변경. 동일 옵션이라도 factory bean 이 다르면 별도 instance 캐싱 [C1] |
| **R-27 NEW** | **메타 어노테이션 pointcut 매칭 한계 — `@Around("@annotation(LeaderElection)")` 는 `@JobLock` 같은 메타 어노테이션을 매칭 못함. `findMergedAnnotation` + `@AliasFor` 만으로는 advisor 등록 자체가 안 됨 → `AnnotationMatchingPointcut` 기반 advisor 별도 설계 필요** | Codex 2nd round C2 | **CRITICAL** | 본 PR 메타 어노테이션 (`@JobLock` 예시) **제거**. 본 PR advice 는 `@LeaderElection` / `@LeaderGroupElection` 직접 부착만 매칭. 메타 어노테이션 컴포지션 (`@AliasFor`) 지원은 후속 [#84] |
| **R-28 NEW** | **SpEL plain 문법 — 리터럴 prefix 가 따옴표 없이 사용되면 식 평가 실패 또는 startup pre-parse 실패** | Codex 2nd round H1 | HIGH | 모든 예시를 plain SpEL 문법으로 명시 — `"'process-' + #region"` (✅), `"process-#region"` (✗ — `process-` 가 식별자로 해석되어 fail). README/KDoc sample DoD 항목에도 "리터럴 prefix 는 따옴표로 감싸기" 명시 [H1] |
| **R-29 NEW** | **`@ConditionalOnBean(LeaderElectionFactory or LeaderElection)` 모호 — 사용자가 기존 `LeaderElection` 빈만 등록하고 factory 미등록 시 runtime factory lookup 실패** | Codex 2nd round H2 | HIGH | `@ConditionalOnBean(LeaderElectionFactory::class)` **factory-only 고정**. 6 backend factory 가 본 PR scope 에 포함되므로 backend autoconfig 가 활성화된 사용자는 factory 도 자동 등록됨. 사용자 측에서 `LeaderElection` 빈만 직접 등록한 경우 (factory 없음) AOP 비활성 — README 명시 + 마이그레이션 가이드 [H2] |
| **R-30 NEW** | **metrics elected vs body-null 구분 한계 — 코어 SPI `runIfLeader(name, action): T?` 의 `null` 이 본문 null 반환 vs 미선출 구분 불가** | Codex 2nd round M1 | MEDIUM | 본 PR `onLockNotAcquired` 은 best-effort (null 반환 → CONTENTION 기록). 정확한 elected vs skipped 분리는 후속 [#85] sealed `LeaderRunResult` SPI 도입 시 가능. README/KDoc 한계 명시 [M1] |
| **R-31 NEW** | **self-invocation 정확 검출 한계 — reflection 으로는 메서드 본문 호출 그래프 분석 불가. ASM/Javassist 또는 AspectJ LTW 도입은 본 PR scope 외** | Codex 2nd round M2 | MEDIUM | 본 PR self-invocation 검출은 best-effort: 같은 클래스에 `@LeaderElection` 또는 `@LeaderGroupElection` 부착된 메서드 2+ 존재 시 WARN. 정확한 검출은 README LIMITATIONS 섹션 명시 (AspectJ LTW 또는 컴파일 타임 분석 도구 필요) [M2] |
| **R-32 NEW** | **SpEL `withMethodResolvers()` 가 임의 인스턴스 메서드 호출 허용 (CVE-2022-22947 회색지대) — `#root.target.shutdown()` 같은 부작용 가능** | Step 3-P Security persona | **CRITICAL** | `withMethodResolvers()` default 제거. property `bluetape4k.leader.aop.spel.allow-method-invocation=false` (default) 로 명시적 opt-in. `#root.target` 노출도 default 제거 [Step 3-P-Sec-1] |
| **R-33 NEW** | **RETHROW 모드 backend 예외 (Lettuce/Redisson/Mongo) 가 host/credentials/topology hint 누출 — REST handler 가 default ExceptionHandler 로 노출 시 인프라 정보 disclosure** | Step 3-P Security persona | HIGH | `LeaderBackendException(lockName, cause)` wrapping — message = `"leader backend error for lock '$lockName'"` 일반화, `getLocalizedMessage()` override 로 cause message 누출 차단. cause 는 보존 (logging/디버깅용) [Step 3-P-Sec-3] |
| **R-34 NEW** | **Lock-namespace pollution — SpEL 결과가 다른 비즈니스 락과 충돌 가능 (e.g., `tenantId="daily-settlement"` 가 정확히 동일한 비즈니스 락과 충돌)** | Step 3-P Security persona | HIGH | `LockNameValidator` charset 화이트리스트 (`^[A-Za-z0-9_:.\-]+$`) + max length 256 (Redis < 1KB, MongoDB index 1024). property `bluetape4k.leader.aop.lock-name-prefix` (default `"${spring.application.name}:"`) 자동 prefix — empty string opt-out [Step 3-P-Sec-2] |

---

## 3. 설계 접근 (≥3안)

### A안 — Spring AOP 단일 모듈로 Boot 3/4 통합 (거부)

사용자 명시 결정 (Boot 3 = Spring AOP, Boot 4 = AspectJ tooling) 위반.

### B안 — Boot3 `spring-boot3/aop` + Boot4 `spring-boot4/aspectj` + 공통 `leader-spring-boot-common` (✅ 채택)

- B + D 통합: 공통 모듈에 `LeaderElectionFactory` SPI 추가 [C1] — 어노테이션 옵션마다 backend instance 캐싱
- 채택 사유: 사용자 결정 + Boot 4 빌드 시 advice 매칭 검증 + per-call options 깨끗한 처리
- **AspectJ 모듈도 sync 어드바이스만 처리** — suspend/Mono 분기 없음

### C안 — B안 + AspectJ LTW (거부)

운영 부담 (R-8) 대비 이점 부족. v2 옵션.

### D안 — Aspect SPI + 사용자 직접 등록 (부분 채택)

zero-boilerplate 위반 → B안에 흡수. `LeaderElectionFactory` SPI 부분만 D안에서 차용 [C1].

---

## 4. 채택 안 = B + D-SPI. 상세 컴포넌트 설계

### 4.1 "Boot 4 = AspectJ" 의 정확한 정의 (R-2 정면 처리)

본 설계에서 **AspectJ는 빌드 시 도구**로만 사용한다 — `io.freefair.aspectj.post-compile-weaving` Gradle 플러그인이 모듈 자체 클래스에 advice 매칭/위빙을 적용하여 컴파일 시 정합성을 검증한다. **사용자 빈에 대한 advice는 런타임 Spring AOP 프록시**로 동일하게 적용된다. 사용자는 CTW 플러그인을 강제로 적용할 필요가 없다. 순수 CTW나 LTW는 v2 옵션.

### 4.2 모듈 구조 + 의존성

본 PR 신규 module 2개 — **flat naming** [M3]:

| 모듈 | 의존성 | 비고 |
|------|--------|------|
| `leader-spring-boot-common` | `spring-context:6.2.x`, `spring-aop:6.2.x`, `spring-expression:6.2.x` 모두 **`compileOnly`** [Q-P3 (b)][Codex-M1] · `com.github.ben-manes.caffeine:caffeine` `implementation` · `kotlin-reflect` `implementation` (SpEL `#argName` 평가) | sync only — `kotlinx-coroutines-reactor` 의존성 **제거** [#80]. compileOnly = consumer 가 자체 Spring BOM (Boot 3 / Boot 4) 제공 → Boot 4 사용자에게 Spring 6.2 transitive 누출 회피 |
| `leader-spring-boot3-aop` | common + `spring-aop:6.2.x` (Boot 3 BOM `runtime`) | 본 PR 신규 module — flat naming [M3]. Boot 3 BOM 으로 Spring 6.2 lock |
| `leader-spring-boot4-aspectj` | common + `spring-aspects:7.x` + `aspectjweaver` + Freefair Gradle 플러그인 (Boot 4 BOM `runtime`) | 본 PR 신규 module — flat naming [M3]. Boot 4 BOM 으로 Spring 7.x runtime 제공 |

기존 `leader-spring-boot3`, `leader-spring-boot4` 모듈은 그대로 유지. 디렉토리 재구조화 (`spring-boot3/{aop,autoconfig}` 구조) 는 후속 [#76]. `settings.gradle.kts` 변경:

```kotlin
include(":leader-spring-boot3-aop")
include(":leader-spring-boot4-aspectj")
```

`kotlinx-coroutines-reactor` 의존성 모두 제거 — sync 어드바이스만 처리하므로 reactor 변환 불필요.

### 4.3 어노테이션 명세 (`leader-spring-boot-common`)

| 어노테이션 / 클래스 | 책임 |
|--------|------|
| `@LeaderElection` | `@Target(AnnotationTarget.FUNCTION)` — **본 PR 직접 부착만 매칭** [C2][R-27]. `@Target(ANNOTATION_TYPE)` 메타-어노테이션 컴포지션은 후속 [#84] (`AnnotationMatchingPointcut` advisor 별도 설계 필요). 필드: `name: String` (required, SpEL + `${...}` placeholder, **plain SpEL — 리터럴 prefix 는 따옴표** [H1][R-28]. 예: `"'process-' + #region"`), `waitTime: String = ""` (빈 → property `bluetape4k.leader.aop.default-wait-time` → 코어 `Default.waitTime`), `leaseTime: String = ""` (빈 → property `bluetape4k.leader.aop.default-lease-time` → 코어 `Default.leaseTime`), `bean: String = ""` (literal only — **factory bean name** [H1][R-25], 다중 백엔드 시 사용. 예: `"redissonLeaderElectionFactory"`), `failureMode: LeaderAspectFailureMode = RETHROW` [Q1]. **`leaderId` 필드 없음** [#72]. **`minLeaseTime` 필드 없음** [#77] |
| `@LeaderGroupElection` | `@Target(AnnotationTarget.FUNCTION)` 동일 — 본 PR 직접 부착만 [C2][R-27]. 위 동일 + `maxLeaders: Int = -1` (≤1 → startup fail [H7]). **`leaderId` 필드 없음** [#72]. **`minLeaseTime` 필드 없음** [#77] |
| `LeaderAspectFailureMode` (enum) | `RETHROW` (default), `SKIP` (예외 흡수 + null 매핑) [Q-X1]. **`FAIL_OPEN_RUN` 본 PR 제거** → 후속 [#81] (LeaderResult sealed wrapper 와 함께 도입) |
| `AbstractLeaderElectionAspect` | `@Around` 공통 advice 골격 (sync only) + `findAnnotationWithTargetFallback()` 헬퍼 [R-24] |
| `AbstractLeaderGroupElectionAspect` | 동일, group 분기 |
| `SpelExpressionEvaluator` | `SimpleEvaluationContext.forPropertyAccessors().withMethodResolvers()` [Q4][R-16]. Caffeine `maximumSize(1024) + expireAfterAccess(1h)` 캐시 [R-17]. 리터럴 fast-path. `${...}` placeholder → `embeddedValueResolver.resolveStringValue()` 선행 (ShedLock 차용). **`TemplateParserContext` 미사용** — plain SpEL 만 (`#region`, `#user.tenantId`) [Q-X2]. 혼합 표현식은 후속 [#82] |
| `LockNameValidator` | **[Step 3-P-Sec-2][R-34 NEW] charset 화이트리스트 강화 — `^[A-Za-z0-9_:.\-]+$` + max length 256 (Redis < 1KB, MongoDB index 1024 byte)**. 위반 시 `IllegalArgumentException` startup 또는 호출 fail-fast. property `bluetape4k.leader.aop.lock-name-prefix` (default `"${spring.application.name}:"`) 자동 prefix 로 lock-namespace pollution 방어 — empty string opt-out 가능 |
| `LeaderBackendException` | **[Step 3-P-Sec-3][R-33 NEW]** RETHROW 모드 backend 예외 wrapper. message = `"leader backend error for lock '$lockName'"` (host/credentials 누출 차단). cause 보존하되 `getLocalizedMessage()` override 로 default Spring `ExceptionHandler` 가 cause message 노출 안 하도록. README 보안 가이드에 `@ExceptionHandler(LeaderBackendException::class)` 등록 권장 |
| `LeaderBeanSelector` | ① `bean` 명시 → `getBean(name, LeaderElectionFactory::class)`, ② 단일 factory 빈, ③ `@Primary`, ④ ambiguous → `NoUniqueBeanDefinitionException` |
| `DurationParser` | **두 형식 양쪽 지원** (ShedLock `StringToDurationConverter.java:54-97`): (a) ISO-8601 `"PT10S"`, `"PT1H"` → `Duration.parse()`, (b) simple `"10s"`, `"5m"`, `"1h"`, `"500ms"` → 정규식 + 단위 매핑. 음수/0 검증 + `leaseTime < waitTime` WARN |
| `LeaderAnnotationValidatorBeanPostProcessor` | Section 10 — Footgun 검출, strict 모드 fail [H5][Q9] |

**`bean` 필드 의미 명확화** [H1][R-25]: `bean` 필드는 **`LeaderElectionFactory` 빈 이름** (또는 `LeaderGroupElectionFactory` 빈 이름) 을 가리킨다. `LeaderElection` election 인스턴스 빈 이름이 아니다 — AOP 는 어노테이션 옵션을 키로 factory 에서 election instance 를 생성/캐싱한다.

```kotlin
// (✅) factory bean name
@LeaderElection(name = "audit", bean = "redissonLeaderElectionFactory")
fun audit() { ... }

// (✗) election bean name — 동작 안 함
@LeaderElection(name = "audit", bean = "redissonLeaderElection")
fun audit() { ... }
```

**백엔드 unlock 계약 (CRITICAL)** [C-4][R-19]: 모든 `LeaderElection` / `LeaderGroupElection` 구현체의 `runIfLeader`는 본문 예외 발생 시에도 try-finally로 unlock 호출을 보장한다. 본 PR DoD에 6개 백엔드(`local`, `lettuce`, `redisson`, `mongodb` sync, `hazelcast`, `exposed-jdbc`) 회귀 테스트 추가 — 본문 `throw RuntimeException("X")` 후 즉시 다른 클라이언트가 동일 lock 획득 가능해야 함.

**suspend/Mono/Flow 본 PR 미지원** → 후속 [#80]: 사용자가 suspend 메서드에 `@LeaderElection` 부착 시 BeanPostProcessor 가 startup fail (strict 모드) / WARN (default) — 분명한 에러 메시지로 [#80] 안내.

**메타 어노테이션 컴포지션 본 PR 제외** [C2][R-27] → 후속 [#84]: `@JobLock` 같은 사용자 정의 메타 어노테이션 (`@AliasFor` 기반) 은 본 PR 미지원. 본 PR advice pointcut 은 `@annotation(LeaderElection)` 만 매칭하므로 `findMergedAnnotation` 만으로는 advisor 등록 자체가 안 됨 — `AnnotationMatchingPointcut` 기반 advisor 별도 설계 필요. 후속 [#84] 에서 도입.

**allopen 가이드**: `kotlin-spring` 플러그인 이용 시 `allOpen { annotation("io.bluetape4k.leader.spring.LeaderElection") }` 명시 — final 메서드 footgun (R-22) 회피.

### 4.4 자동 구성 (`leader-spring-boot3-aop`, `leader-spring-boot4-aspectj`) [M3]

| 클래스 | 비고 |
|--------|------|
| `LeaderElectionAspect` | `extends AbstractLeaderElectionAspect`, `@Aspect @Component`, `@Order(LeaderAspectOrder.AOP_ORDER)` [M3], `@Role(BeanDefinition.ROLE_INFRASTRUCTURE)` |
| `LeaderGroupElectionAspect` | 동일, group 분기, `@Role(ROLE_INFRASTRUCTURE)` |
| `LeaderAopFactoryAutoConfiguration` | **AutoConfig 분리 — Phase 1** [Codex-H3]. `@AutoConfiguration` + `@ConditionalOnClass(Aspect::class)` + `@ConditionalOnProperty(bluetape4k.leader.aop.enabled, matchIfMissing=true)`. **factory @Bean 6쌍 등록** (각 `@ConditionalOnBean(RedissonClient::class)` 등 backend client 조건). factory 빈 등록은 unconditional 으로 평가되어야 다음 단계에서 `@ConditionalOnBean(LeaderElectionFactory)` 가 충족됨 |
| `LeaderAopAutoConfiguration` | **AutoConfig 분리 — Phase 2** [Codex-H3]. `@AutoConfiguration(after = LeaderAopFactoryAutoConfiguration::class)` + `@ConditionalOnBean(LeaderElectionFactory::class)` + `@EnableAspectJAutoProxy(proxyTargetClass = true)` [R-9]. Aspect/BPP/Health/MetricsRecorder 빈 등록. 모두 `@Role(ROLE_INFRASTRUCTURE)`. **사용자가 backend client 빈 미등록 시 factory 비활성 → 본 autoconfig `@ConditionalOnBean` 미충족 → AOP 전체 비활성** (안전한 기본 동작) — README 명시 + 마이그레이션 가이드 |
| `LeaderAspectOrder` (object) | 상수: `AOP_ORDER = Ordered.HIGHEST_PRECEDENCE + 100`. 외부 advice 순서 표 README에 명시 [M3] |
| `LeaderAopHealthIndicator` | advice 메서드 수, SpEL cache size 노출 [M4], `@Role(ROLE_INFRASTRUCTURE)` |
| `LeaderAopProperties` | `@ConfigurationProperties("bluetape4k.leader.aop")` — `enabled`, `strict`, `failureMode`, `defaultLeaseTime`, `defaultWaitTime` |
| `META-INF/spring/...AutoConfiguration.imports` | 두 줄: `LeaderAopFactoryAutoConfiguration` + `LeaderAopAutoConfiguration` (Phase 1 → Phase 2 순서) |

**외부 advice 순서 표** [M3]:

| Aspect | Order |
|--------|-------|
| `LeaderElectionAspect` (본 라이브러리) | `HIGHEST_PRECEDENCE + 100` |
| `@Transactional` | `LOWEST_PRECEDENCE` (자동 inner) |
| `@Retry` (Resilience4j) | 기본 `LOWEST_PRECEDENCE - 3` |
| `@CircuitBreaker` (Resilience4j) | 기본 `LOWEST_PRECEDENCE - 4` |
| `@Cacheable` | `LOWEST_PRECEDENCE - 1` |

**의도**: 락 획득 → (Cache 확인) → CircuitBreaker → Retry → Transaction → 본문 → 트랜잭션 커밋 → 락 해제. ShedLock 권장 동등.

**Boot 4 추가**: `id("io.freefair.aspectj.post-compile-weaving")` Gradle 플러그인 + `aspectjrt` + `spring-aspects`. 런타임 프록시는 동일.

### 4.5 `LeaderElectionFactory` SPI [C1][R-20] — 신규

```kotlin
// leader-core
interface LeaderElectionFactory {
    fun create(options: LeaderElectionOptions): LeaderElection
}
interface LeaderGroupElectionFactory {
    fun create(options: LeaderGroupElectionOptions): LeaderGroupElection
}
```

**suspend factory SPI 본 PR 제외** [#80] — sync only 결정. `SuspendLeaderElectionFactory` / `SuspendLeaderGroupElectionFactory` 는 suspend 어드바이스 도입 시 [#80] 에서 함께 추가.

**본 PR 6 백엔드 sync factory 구현** [M2]:

| 백엔드 | 모듈 | factory 빈 이름 |
|--------|------|-----------------|
| Local | `leader-core` | `localLeaderElectionFactory`, `localLeaderGroupElectionFactory` |
| Lettuce (Redis) | `leader-redis-lettuce` | `lettuceLeaderElectionFactory`, `lettuceLeaderGroupElectionFactory` |
| Redisson (Redis) | `leader-redis-redisson` | `redissonLeaderElectionFactory`, `redissonLeaderGroupElectionFactory` |
| MongoDB (sync) | `leader-mongodb` | `mongoLeaderElectionFactory`, `mongoLeaderGroupElectionFactory` |
| Hazelcast | `leader-hazelcast` | `hazelcastLeaderElectionFactory`, `hazelcastLeaderGroupElectionFactory` |
| Exposed JDBC | `leader-exposed-jdbc` | `exposedJdbcLeaderElectionFactory`, `exposedJdbcLeaderGroupElectionFactory` |

**Exposed R2DBC 본 PR 제외**: R2DBC 백엔드는 suspend-only 시그니처 (`suspend fun runIfLeader(...)`) 만 제공 → AOP factory 대상 아님 → 후속 [#80] 에서 suspend 어드바이스 + R2DBC factory 동시 도입.

**Factory 빈 등록 위치 = AOP autoconfig** (`leader-spring-boot3-aop` / `-boot4-aspectj` 의 `LeaderAopAutoConfiguration`). 각 backend 모듈은 factory **클래스만** 정의 (Spring 의존 없음, 순수 Kotlin 유지). 실제 `@Bean` 등록은 AOP autoconfig 가 담당하며, `@ConditionalOnBean(RedissonClient::class)` 등으로 사용자가 backend client bean 등록 시에만 factory 활성화 [Q-P1 (b)].

AOP는 **`FactoryCacheKey(factoryBeanName, options)`** 를 키로 한 캐싱 사용 [C1][R-26]. 동일 옵션이라도 factory bean 이 다르면 별도 instance 캐싱 — 다중 백엔드 시 cross-backend collision 방지:

```kotlin
data class FactoryCacheKey(val factoryBeanName: String, val options: LeaderElectionOptions)

private val cache = ConcurrentHashMap<FactoryCacheKey, LeaderElection>()
fun resolve(factoryBeanName: String, options: LeaderElectionOptions): LeaderElection {
    val factory = beanFactory.getBean(factoryBeanName, LeaderElectionFactory::class)
    val key = FactoryCacheKey(factoryBeanName, options)
    return cache.computeIfAbsent(key) { factory.create(options) }
}
```

`LeaderGroupElectionFactory` 도 동일 패턴 — `data class GroupFactoryCacheKey(val factoryBeanName: String, val options: LeaderGroupElectionOptions)` 사용.

---

## 5. ProceedingJoinPoint Advice 의사코드 (sync only)

```kotlin
@Around("@annotation(io.bluetape4k.leader.spring.LeaderElection)")
fun around(pjp: ProceedingJoinPoint): Any? {
    val method = (pjp.signature as MethodSignature).method
    // [R-24] proxy → target class fallback (ShedLock SpringLockConfigurationExtractor.java:212-229)
    val ann = findAnnotationWithTargetFallback(method, pjp.target, LeaderElection::class.java)
        ?: error("@LeaderElection not found on ${method.declaringClass.name}#${method.name}")

    // [Q-X2] plain SpEL — TemplateParserContext 미사용
    val resolved = embeddedValueResolver.resolveStringValue(ann.name) ?: ann.name  // ${...} placeholder
    val lockName = if (LITERAL_PATTERN.matches(resolved)) resolved
                   else spel.evaluate(resolved, method, pjp.args, pjp.target)
    LockNameValidator.require(lockName)

    val opts = LeaderElectionOptions(
        waitTime = DurationParser.parseOrDefault(ann.waitTime, props.defaultWaitTime),
        leaseTime = DurationParser.parseOrDefault(ann.leaseTime, props.defaultLeaseTime),
    )

    // [C1][H1][R-26] factory bean lookup — bean 필드는 factory 빈 이름. cache key 는 (factoryBeanName, options)
    val factoryBeanName = ann.bean.ifBlank { defaultFactoryBeanName }
    val factory = beanFactory.getBean(factoryBeanName, LeaderElectionFactory::class)
    val cacheKey = FactoryCacheKey(factoryBeanName, opts)
    val election = factoryCache.computeIfAbsent(cacheKey) { factory.create(opts) }

    // [Codex-M3] metrics fan-out 은 항상 runCatching 으로 isolation — 한 recorder throw 가 leader 실행 또는 다른 recorder 영향 없음
    fun fanOut(action: (LeaderAopMetricsRecorder) -> Unit) {
        metrics.forEach { runCatching { action(it) }.onFailure { log.warn(it) { "metrics recorder threw" } } }
    }

    fanOut { it.onLockAttempt(lockName, opts) }
    val start = System.nanoTime()
    // [Step 3-P-Rel-1] body 예외 vs backend 예외 분리 — 사용자 본문 throw 는 RETHROW 모드에서 그대로 재던짐, backend throw 만 LeaderBackendException wrapping
    return try {
        val result = election.runIfLeader(lockName) {
            fanOut {
                it.onLockAcquired(lockName, opts, (System.nanoTime() - start).nanoseconds)
                it.onTaskStarted(lockName)
            }
            try {
                pjp.proceed()
            } catch (e: CancellationException) {
                // [Step 3-P-Rel-2][CLAUDE.md memory] CancellationException 항상 우선 재throw
                fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, e) }
                throw e
            } catch (bodyEx: Throwable) {
                // 본문 예외 — backend wrapping 없이 그대로 전파 (RETHROW/SKIP 분기는 outer catch 가 처리하지 않음 — body 예외는 항상 그대로)
                fanOut { it.onTaskFailed(lockName, (System.nanoTime() - start).nanoseconds, bodyEx) }
                throw bodyEx
            }
        }
        // [M1][R-30] best-effort — 코어 SPI 한계로 본문 null vs 미선출 구분 불가. 정확한 분리는 후속 [#85]
        if (result == null) {
            fanOut { it.onLockNotAcquired(lockName, opts, SkipReason.CONTENTION) }
        } else {
            fanOut { it.onTaskFinished(lockName, (System.nanoTime() - start).nanoseconds) }
            warnIfNearLease(start, opts.leaseTime)  // [H10]
        }
        result  // T? — null이면 미선출 또는 본문 null (best-effort 한계)
    } catch (e: CancellationException) {
        // [Step 3-P-Rel-2] inner 가 이미 처리하지만 backend 자체 cancel 도 방어
        throw e
    } catch (backendEx: Throwable) {
        // [Step 3-P-Sec-3][R-33 NEW] backend 예외만 LeaderBackendException wrapping — host/credentials 등 인프라 메시지 누출 차단
        val wrapped = LeaderBackendException(lockName, backendEx)
        fanOut { it.onLockNotAcquired(lockName, opts, SkipReason.BACKEND_ERROR) }
        when (ann.failureMode) {
            LeaderAspectFailureMode.RETHROW -> throw wrapped
            LeaderAspectFailureMode.SKIP -> {
                log.warn(backendEx) { "leader.aop.skipped lockName=$lockName reason=BACKEND_ERROR" }
                null
            }
        }
    }
}

// [R-24] ShedLock 패턴 차용
private fun <A : Annotation> findAnnotationWithTargetFallback(
    method: Method, target: Any, type: Class<A>,
): A? {
    AnnotatedElementUtils.findMergedAnnotation(method, type)?.let { return it }
    val targetClass = AopUtils.getTargetClass(target)
    val targetMethod = runCatching { targetClass.getMethod(method.name, *method.parameterTypes) }.getOrNull()
        ?: return null
    return AnnotatedElementUtils.findMergedAnnotation(targetMethod, type)
}

private fun warnIfNearLease(startNanos: Long, leaseTime: Duration) {
    val elapsed = (System.nanoTime() - startNanos).nanoseconds
    if (elapsed > leaseTime * 0.8) log.warn { "leader.aop.lease-warn elapsed=$elapsed leaseTime=$leaseTime" }  // [H10]
}
```

`LeaderGroupElection` 분기는 `LeaderGroupElectionFactory` 사용 + `maxLeaders` 인자만 다르며 동일 패턴.

**suspend / Mono / Flux / Flow 분기 모두 제거** → 후속 [#80].
**`failureMode = FAIL_OPEN_RUN` 분기 제거** → 후속 [#81] (LeaderResult sealed wrapper 와 함께).
**elapsed > leaseTime*0.8 WARN** 은 lease/본문 mismatch 감지용으로 유지 (#73 watchdog 과 별개).

---

## 6. SpEL 평가 컨텍스트 (Q4 보안 강화 + plain SpEL)

### 6.1 노출 변수

- 메서드 파라미터: `#argName` (kotlin-reflect), 폴백 `#a0`, `#p0`
- `#root.method`, `#root.methodName`, `#root.args`
- **`#root.target` 제거 default** [Step 3-P-Sec-1][R-32] — 메서드 호출 차단과 함께 default 제거. `allow-method-invocation=true` 시 노출
- **`@beanName` 제거** [Q4][R-16] — `BeanFactoryResolver` 미사용. 사용자가 빈 선택 필요 시 어노테이션 `bean` literal 필드만 [M1]

### 6.2 평가 파이프라인 (plain SpEL — TemplateParserContext 미사용 [Q-X2])

ShedLock `SpringLockConfigurationExtractor.java:131-138` 의 placeholder 처리만 차용. ShedLock 의 `TemplateParserContext("#{","}")` 모드는 사용자 친숙도가 낮아 **plain SpEL** (`#region`, `#user.tenantId`, `"'process-' + #region"`) 만 채택 — ShedLock 의 `@SchedulerLock(name = "#userId")` 와 동일한 사용자 경험.

**[H1][R-28] plain SpEL 문법 — 리터럴 prefix 는 따옴표 필수**: `"process-#region"` (✗ — `process-` 가 식별자로 해석되어 fail) / `"'process-' + #region"` (✅ — 리터럴 prefix 를 작은따옴표로 감싸고 `+` 로 연결). README/KDoc sample 에 명시.

**[Step 3-P-Sec-1][R-32 NEW] 메서드 호출 차단 default — `withMethodResolvers()` 제거**: 락 이름 평가는 read-only property access 만으로 충분 (`#user.tenantId`, `#root.args[0]`). `withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())` 는 `#root.target.shutdown()`, `#user.deleteAccount()` 같은 부작용 메서드 호출을 허용 (CVE-2022-22947 회색지대) → **default 제거**. 메서드 호출이 정말 필요하면 property `bluetape4k.leader.aop.spel.allow-method-invocation=true` (default `false`) 로 명시적 opt-in. `#root.target` 노출도 제거 — `#root.method`, `#root.methodName`, `#root.args` 만 노출.

1. **`${...}` placeholder 해석** — `embeddedValueResolver.resolveStringValue(name)` 로 Spring property placeholder 평가 (예: `"${spring.application.name}-job"` → `"myapp-job"`). ShedLock 차용
2. **literal fast-path 정규식 검사** — 평문 잠금 이름은 SpEL 우회
3. **plain SpEL parsing** — `parser.parseExpression(resolved)` (TemplateParserContext 없음)
4. **`SimpleEvaluationContext` 평가** — `forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess()).build()` (메서드 호출 차단 default)

```kotlin
// default — 메서드 호출 차단 (CVE-2022-22947 회색지대 방어)
val ctx = SimpleEvaluationContext.forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess())
    .withRootObject(RootCtx(method, args))  // target 제거 [Step 3-P-Sec-1]
    .build()

// opt-in (property `bluetape4k.leader.aop.spel.allow-method-invocation=true` 시)
val ctx = SimpleEvaluationContext.forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess())
    .withMethodResolvers(DataBindingMethodResolver.forInstanceMethodInvocation())
    .withRootObject(RootCtx(method, args, target))
    .build()
```

`T(...)` 타입 참조 표현식 차단, Spring 빈 참조 표현식 차단, **메서드 호출 차단 (default)**. read-only property access만 허용.

`TemplateParserContext` 혼합 표현식 (`"prefix-#{userId}-suffix"`) 은 옵션으로 후속 [#82] — `bluetape4k.leader.aop.spel.template-mode=true` property 형태 검토.

### 6.3 리터럴 fast-path + Caffeine 캐시 + parser 싱글턴

```kotlin
private val LITERAL_PATTERN = Regex("^[A-Za-z0-9_\\-:.]+$")
private val parser = SpelExpressionParser()  // 싱글턴, thread-safe
private val cache: Cache<String, Expression> = Caffeine.newBuilder()
    .maximumSize(1024)                       // [R-17][C2]
    .expireAfterAccess(Duration.ofHours(1))  // [R-17][C2]
    .build()

fun evaluate(expr: String, method: Method, args: Array<*>, target: Any): String {
    // 1) ${...} placeholder 해석 (ShedLock 차용) — 호출자 측에서 선행
    // 2) literal fast-path
    if (LITERAL_PATTERN.matches(expr)) return expr  // 100ns 절감
    // 3) plain SpEL parse + 캐시 (TemplateParserContext 없음)
    val parsed = cache.get(expr) { parser.parseExpression(it) }
    // 4) SimpleEvaluationContext 평가
    return parsed.getValue(buildCtx(method, args, target), String::class.java)
        ?: error("SpEL '$expr' returned null at ${method.declaringClass.name}#${method.name}")  // [H6]
}
```

### 6.4 SmartInitializingSingleton pre-parse [H6]

`AbstractLeaderElectionAspect`가 `SmartInitializingSingleton` 구현 → 컨텍스트 시작 시 모든 `@LeaderElection`/`@LeaderGroupElection` 메서드 스캔 → SpEL `name` 필드 사전 parse. 잘못된 표현식은 startup에서 메서드 FQN 포함 에러 메시지로 fail-fast.

---

## 7. 사용자 사용 예 (sync only)

```kotlin
// 단일 리더 + 정적 이름 (fast-path)
@Scheduled(cron = "0 0 2 * * *")
@LeaderElection(name = "daily-settlement", leaseTime = "PT1H")
fun dailySettlement() { ... } // 미선출 → 본문 미실행 (Unit)

// 단일 리더 + plain SpEL — 리터럴 prefix 는 따옴표 [H1][R-28][Q-X2]
@LeaderElection(name = "'process-' + #region")
fun process(region: String): Result? { ... }
// 미선출 → null
// (✗) name = "process-#region" — `process-` 가 식별자로 해석되어 SpEL 평가 실패

// SpEL property 경로 (plain SpEL — 따옴표 prefix)
@LeaderElection(name = "'rebuild-' + #user.tenantId")
fun rebuild(user: User): ReportSummary? = service.rebuild(user.tenantId)

// simple Duration 형식 (ShedLock 차용)
@LeaderElection(name = "cron-job", leaseTime = "30s", waitTime = "2s")
fun cronJob() { ... }

// ${...} property placeholder (ShedLock 차용) — 평가 후 plain SpEL
@LeaderElection(name = "\${spring.application.name} + ':warmup'")
fun warmup() { ... }
// 결과 (spring.application.name = "myapp"): "myapp:warmup"

// 그룹 리더
@LeaderGroupElection(name = "batch-shard", maxLeaders = 3, leaseTime = "PT5M")
fun batch() { ... }

// 다중 백엔드 명시 — bean 필드는 factory 빈 이름 [H1][R-25]
@LeaderElection(name = "audit-job", bean = "redissonLeaderElectionFactory")
fun audit() { ... }

// failureMode = SKIP — 백엔드 throw 시 정상 null 반환
@LeaderElection(name = "best-effort", failureMode = LeaderAspectFailureMode.SKIP)
fun bestEffort(): Result? = service.process()
```

**메타 어노테이션 (`@JobLock` `@AliasFor` 패턴) 본 PR 제외** [C2][R-27] → 후속 [#84]: pointcut `@annotation(LeaderElection)` 은 메타 어노테이션을 매칭 못함. `AnnotationMatchingPointcut` 기반 advisor 별도 설계 필요.

**suspend / Mono / Flux 예시 모두 제거** → 후속 [#80].

---

## 8. Observability — Hook 인터페이스 + 본 PR 로그/Health [H4]

본 PR은 인터페이스 + 기본 로그 + Health만 포함. 실제 Micrometer 구현은 후속 [#75].

**[M1][R-30] 정확도 한계 — best-effort**: 코어 SPI `runIfLeader(name, action): T?` 는 본문 `null` 반환과 미선출을 구분하지 못한다. 본 PR `onLockNotAcquired(...CONTENTION)` 은 best-effort 로 `null` 반환 시 호출되며, "본문이 정상적으로 `null` 을 반환한 경우" 도 CONTENTION 으로 카운트될 수 있다. 정확한 elected vs skipped 분리는 후속 [#85] 에서 sealed `LeaderRunResult { Elected(value), Skipped }` SPI 도입 시 가능. README/KDoc 한계 명시.

### 8.1 로그 spec

| 이벤트 | 레벨 | 로거 |
|--------|------|------|
| `leader.aop.elected` (lockName, elapsed) | DEBUG | `io.bluetape4k.leader.aop` |
| `leader.aop.skipped` (lockName, reason=CONTENTION/BACKEND_ERROR) | DEBUG (CONTENTION) / WARN (BACKEND_ERROR) | 동일 |
| `leader.aop.failed` (lockName, throwable) | WARN | 동일 |
| `leader.aop.lease-warn` (lockName, elapsed, leaseTime, ratio) [H10] | WARN | 동일 |

### 8.2 Hook 인터페이스 (6 콜백, no-op default, 실제 구현 [#75])

```kotlin
enum class SkipReason { CONTENTION, BACKEND_ERROR }
// FAIL_OPEN_FORCED 제거 → 후속 [#81]

interface LeaderAopMetricsRecorder {
    fun onLockAttempt(name: String, options: LeaderElectionOptions) {}
    fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {}
    fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {}
    fun onTaskStarted(name: String) {}
    fun onTaskFinished(name: String, executionTime: Duration) {}
    fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {}
    object NoOp : LeaderAopMetricsRecorder
}
```

**멀티 빈 주입** (Spring `ObjectProvider` 패턴):

```kotlin
@Bean
fun leaderElectionAspect(
    recordersProvider: ObjectProvider<List<LeaderAopMetricsRecorder>>,
    ...
): LeaderElectionAspect {
    val recorders = recordersProvider.getIfAvailable { emptyList() }
    return LeaderElectionAspect(recorders, ...)
}
```

Aspect는 `recorders.forEach { it.onXxx(...) }` 패턴으로 모든 등록 빈에 fan-out — Micrometer + 사용자 정의 + 트레이싱 동시 가능. 향후 ObservationRegistry Span 통합 [#75].

### 8.3 Health [M4]

`LeaderAopHealthIndicator` — `details`: advice 메서드 수, SpEL cache size, 누적 elected/skipped/failed 카운터.

---

## 9. Failure mode + Kill-switch [H1][H2][Q9]

### 9.1 Failure mode

```kotlin
enum class LeaderAspectFailureMode {
    RETHROW,         // default — 백엔드/본문 throw 그대로 전파 [Q1]
    SKIP,            // 예외 흡수 후 null
    // FAIL_OPEN_RUN — 본 PR 제외 → 후속 [#81] LeaderResult sealed wrapper 와 함께
}
```

- 어노테이션 필드 `failureMode: LeaderAspectFailureMode = RETHROW`
- 전역 default override: property `bluetape4k.leader.aop.failure-mode=SKIP`

`FAIL_OPEN_RUN` 은 락 실패 시에도 본문 실행 여부를 호출자가 정확히 알아야 안전 → `LeaderResult<T> { Elected(value), Skipped, FailedOpenRun(value) }` sealed wrapper 가 선결 조건. 본 PR sync `T?` 시그니처로는 구분 불가능 → [#81] 에서 동시 도입.

### 9.2 Kill-switch / 모드

- `bluetape4k.leader.aop.enabled` (default true, `matchIfMissing=true`) — 전체 disable [H2]
- `bluetape4k.leader.aop.strict` (default false) — Footgun 검출 fail-fast [Q9]
  - true: BeanPostProcessor 검출 위반 시 startup fail
  - false: WARN 로그만

### 9.3 전역 default property (ShedLock 차용)

어노테이션의 `leaseTime`/`waitTime` 빈 문자열 (`""`) 시 fallback 우선순위: 어노테이션 → property → 코어 `Default`.

| Property | 기본값 | 설명 |
|----------|--------|------|
| `bluetape4k.leader.aop.default-lease-time` | `"PT1M"` | 어노테이션 `leaseTime` 미지정 시 모든 메서드 공통 적용 |
| `bluetape4k.leader.aop.default-wait-time` | `"PT5S"` | 어노테이션 `waitTime` 미지정 시 모든 메서드 공통 적용 |
| `bluetape4k.leader.aop.spel.allow-method-invocation` | `false` | **[Step 3-P-Sec-1][R-32]** SpEL `withMethodResolvers()` 활성화 — default `false` (CVE-2022-22947 회색지대 차단). `true` 시 `#root.target` 도 노출 |
| `bluetape4k.leader.aop.lock-name-prefix` | `"${spring.application.name}:"` | **[Step 3-P-Sec-2][R-34]** SpEL 평가 결과 앞에 자동 prefix — lock-namespace pollution 방어. empty string (`""`) 시 opt-out |

`DurationParser.parseOrDefault(annotationValue, propertyDefault)` 헬퍼가 빈 문자열 우선 폴백. **`default-min-lease-time`은 본 PR 미지원** — 후속 [#77].

---

## 10. Footgun 테이블 + BeanPostProcessor [H5][R-22]

### 10.1 Footgun 검출 표

| Footgun | 검출 방법 | strict=true | strict=false |
|---------|-----------|-------------|--------------|
| `final fun` 메서드 | 리플렉션 `Modifier.isFinal` | startup fail | WARN |
| `private fun` 메서드 | `Modifier.isPrivate` | startup fail | WARN |
| **같은 클래스에 `@LeaderElection`/`@LeaderGroupElection` 메서드 2+ (self-invocation 가능성)** [M2][R-31] | reflection — declared methods 중 어노테이션 부착 메서드 카운트 ≥ 2 | WARN (정확 검출 불가능) | WARN |
| `maxLeaders ≤ 1` (`@LeaderGroupElection`) | 어노테이션 검사 [H7] | startup fail | startup fail (항상) |
| **suspend fun 부착** (sync only 위반) | `method.parameterTypes.lastOrNull() == Continuation::class.java` | startup fail with #80 안내 | WARN with #80 안내 |
| **`Mono` / `Flux` / `Flow` 반환** (sync only 위반) | returnType 검사 | startup fail with #80 안내 | WARN with #80 안내 |
| SpEL 잘못된 표현식 | pre-parse [H6] | startup fail | startup fail (항상) |
| `kotlin-reflect` 미존재 + 파라미터 SpEL | classpath 검사 | startup fail | WARN |
| **`@Scheduled` 부착인데 `@EnableScheduling` 누락** | `ApplicationContext.getBeansWithAnnotation(EnableScheduling::class)` empty 검사 | startup fail | WARN |

**[M2][R-31] self-invocation 검출 한계**: reflection 으로는 메서드 본문 호출 그래프를 분석할 수 없으므로 정확한 self-invocation 검출은 불가능하다. 본 PR 검출은 best-effort — 같은 클래스에 `@LeaderElection` 또는 `@LeaderGroupElection` 부착 메서드가 2개 이상 존재하면 "self-invocation 가능성" WARN 만 출력. 정확한 검출은 다음 중 하나 필요:
- AspectJ LTW (load-time weaving) 채택 — 본 PR scope 외, v2 옵션 (R-8 운영 부담)
- ASM/Javassist 바이트코드 분석 도구 도입 — 본 PR scope 외
- 컴파일 타임 KSP 분석기 — v2 옵션

README LIMITATIONS 섹션에 명시 — 사용자가 정확한 self-invocation 회피하려면 별도 빈으로 추출 권장.

### 10.2 BeanPostProcessor 구현

```kotlin
class LeaderAnnotationValidatorBeanPostProcessor(
    private val strict: Boolean,
    private val spel: SpelExpressionEvaluator,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        bean.javaClass.declaredMethods
            .filter { it.isAnnotationPresent(LeaderElection::class.java) || it.isAnnotationPresent(LeaderGroupElection::class.java) }
            .forEach { validate(it) }  // final/private/maxLeaders/suspend/reactive/SpEL 검증
        return bean
    }
}
```

자기 클래스 내 호출 정확 분석은 ASM/Javassist 또는 AspectJ LTW 필요 — 본 PR scope 외 [M2][R-31]. 본 PR 은 reflection 기반 best-effort: "같은 클래스에 어노테이션 부착 메서드 2+ 있으면 WARN" 만 검출. 한계 README LIMITATIONS 섹션 명시.

---

## 11. 본 PR scope + 후속 이슈 cross-ref

### 11.1 본 PR scope (sync only)

- 어노테이션 (`@LeaderElection`, `@LeaderGroupElection`, **`@Target(AnnotationTarget.FUNCTION)` 직접 부착만** — 메타 어노테이션 컴포지션 [#84] 후속 [C2][R-27]) + `LeaderAspectFailureMode { RETHROW, SKIP }` enum
- `AbstractLeaderElectionAspect` / `AbstractLeaderGroupElectionAspect` (sync `T?` only) + `findAnnotationWithTargetFallback()` (ShedLock 차용)
- `SpelExpressionEvaluator` (SimpleEvaluationContext + Caffeine cache + literal fast-path + pre-parse + `${...}` placeholder + plain SpEL — TemplateParserContext 미사용 — **리터럴 prefix 는 따옴표** [H1][R-28])
- `DurationParser` — simple `"10s"` + ISO-8601 `"PT10S"` 양쪽 (ShedLock 차용)
- `LeaderElectionFactory` / `LeaderGroupElectionFactory` SPI 2종 + **6 백엔드 sync 구현** (Local, Lettuce, Redisson, MongoDB sync, Hazelcast, Exposed JDBC) + AOP **`ConcurrentHashMap<FactoryCacheKey, LeaderElection>` 캐싱** ([C1][R-26] cross-backend collision 방지)
- `LeaderAnnotationValidatorBeanPostProcessor` (Footgun 검출 — final/private/maxLeaders/suspend/reactive 위반 검출 + #80 안내 + best-effort self-inv WARN [M2][R-31])
- `LeaderAopAutoConfiguration` (Boot3/4 동일 + **`@ConditionalOnBean(LeaderElectionFactory)` factory-only 고정** [H2][R-29] + `@ConditionalOnProperty` + `@Role(ROLE_INFRASTRUCTURE)`)
- `LeaderAopProperties` (`default-lease-time`, `default-wait-time`, `enabled`, `strict`, `failure-mode`)
- `LeaderAspectOrder` 상수 + 외부 advice 표 README
- `LeaderAopHealthIndicator`
- 로그 spec + `LeaderAopMetricsRecorder` 6 콜백 인터페이스 + `List<...>` 멀티 빈 주입 (best-effort 한계 [M1][R-30])
- 백엔드 unlock 계약 회귀 테스트 (6 백엔드)
- ShedLock 매핑 표 + 0.x 마이그레이션 가이드 README
- **신규 module 2개 — flat naming** [M3]: `leader-spring-boot3-aop`, `leader-spring-boot4-aspectj` (`settings.gradle.kts` include 추가)

### 11.2 본 PR 미포함 + 후속 이슈

| 이슈 | 내용 | 사유 |
|------|------|------|
| **#72** | `@LeaderElection`/`@LeaderGroupElection` `leaderId` 지원 | Group API 코어가 `runIfLeader(lockName, action)` 시그니처만 가져 자리 없음 — 코어 + AOP 동시 변경 필요 [C5][C6] |
| **#73** | watchdog / lease auto-extend | 본 PR은 elapsed > leaseTime*0.8 WARN만 [Q3][H10] |
| **#74** | `Flux<T>` / `Flow<T>` 반환 지원 | lease 갱신 의미 정의 + watchdog 의존. [#80] 의 일부로 포함 검토 |
| **#75** | Micrometer metrics 통합 + ObservationRegistry Span | 본 PR은 Hook 6 콜백 인터페이스 + Health만 |
| **#76** | spring-boot 디렉토리 재구조화 (`spring-boot3/{aop,autoconfig}` 등) | artifact ID 유지 [Q5][Q6] |
| **#77** | `minLeaseTime` 백엔드 TTL 위임 (ShedLock 패턴) | 본 PR `minLeaseTime` 어노테이션 필드 제거 — 코어 `LeaderElectionOptions.minLeaseTime` + 백엔드 `runIfLeader` finally TTL 위임 동시 도입 [R-23] |
| **#78** | 클래스/패키지 레벨 `@LeaderElectionBackend` 메타 어노테이션 | 메서드별 `bean = "..."` 반복 회피, 클래스 단위로 부착 시 메서드 어노테이션은 백엔드 미지정 가능 |
| **#79** | `LockExtender` / `LockAssert` (reentrant + 명시적 lease 연장 API) | ShedLock `LockExtender.extendActiveLock()` / `LockAssert.assertLocked()` 패턴 — 본 PR은 advice 단일 진입만 보장 |
| **#80 NEW** | **suspend / `Mono<T>` / `Flow<T>` 반환 지원 + R2DBC 백엔드 factory** | 본 PR sync only 결정으로 분리. (a) `kotlinx-coroutines-reactor` 의존성 (b) `Mono.empty()` vs `null` 매핑 (c) subscribe당 락 1회 (`Mono.defer`) (d) `LeaderElectionInfo` CoroutineContext (e) `SuspendLeaderElectionFactory` SPI (f) Exposed R2DBC factory — 모두 #80에서 동시 도입 |
| **#81 NEW** | **`FAIL_OPEN_RUN` failureMode + `LeaderResult<T>` sealed wrapper** | sync `T?` 시그니처로는 "락 실패 후 강제 실행한 결과" 구분 불가 → `LeaderResult { Elected(v), Skipped, FailedOpenRun(v) }` 도입 후 `FAIL_OPEN_RUN` 활성화. R-1 (Mono.empty() vs null) 도 같은 wrapper 로 근본 해결 |
| **#82 NEW** | **SpEL `TemplateParserContext` 혼합 표현식 옵션** | 본 PR plain SpEL 만 — 사용자 친숙도 우선 (ShedLock `@SchedulerLock(name = "#userId")` 와 동등). `"prefix-#{userId}-suffix"` 혼합 표현식은 `bluetape4k.leader.aop.spel.template-mode=true` property 형태로 후속 옵션 추가 |
| **#84 NEW** | **메타 어노테이션 (`@AliasFor`) 지원 — `@JobLock` 같은 사용자 정의 메타 어노테이션** [C2][R-27] | 본 PR pointcut `@annotation(LeaderElection)` 은 메타 어노테이션을 매칭 못함. `findMergedAnnotation` + `@AliasFor` 만으로는 advisor 등록 자체가 안 됨 → `AnnotationMatchingPointcut` 기반 advisor 별도 설계 필요. 본 PR 은 `@LeaderElection`/`@LeaderGroupElection` 직접 부착만 매칭 |
| **#85 NEW** | **백엔드 SPI elected vs skipped 명확 구분 — sealed `LeaderRunResult<T> { Elected(value), Skipped }` 도입** [M1][R-30] | 코어 `runIfLeader(name, action): T?` 는 본문 `null` 반환과 미선출 구분 불가 → metrics `onLockNotAcquired(CONTENTION)` 부정확 (best-effort). `LeaderRunResult` sealed wrapper 도입 시 정확한 elected/skipped 분리 가능. `FAIL_OPEN_RUN` [#81] 과 함께 도입 검토 (sealed wrapper 공유) |

### 11.3 v2 후속

- `nameProvider: KClass` 컴파일타임 검증 옵션 [Q2][M5]
- AspectJ LTW 옵션 (self-invocation 근본 해결, R-3)

---

## 12. DoD

- [ ] `@LeaderElection`, `@LeaderGroupElection` 어노테이션 (common) — `leaderId`/`minLeaseTime` 미포함 확인 [C5][#77]
- [ ] **`@Target(AnnotationTarget.FUNCTION)` 직접 부착만 매칭 검증** — 메타 어노테이션 (`@JobLock`) 시 advice 미적용 확인 [C2][R-27][#84]
- [ ] `LeaderAspectFailureMode { RETHROW, SKIP }` enum + property `bluetape4k.leader.aop.failure-mode` [H1][Q1][Q-X1]
- [ ] `SpelExpressionEvaluator` — `SimpleEvaluationContext` 사용 + 시스템 타입 / 빈 참조 차단 단위 테스트 [Q4][R-16]
- [ ] **[Step 3-P-Sec-1][R-32] SpEL 메서드 호출 차단 default 테스트** — `"#root.target.shutdown()"` / `"#someArg.close()"` → `SpelEvaluationException` 또는 fail-fast (default `allow-method-invocation=false`)
- [ ] **[Step 3-P-Sec-1][R-32] `allow-method-invocation=true` opt-in 테스트** — property `true` 시 메서드 호출 허용 + `#root.target` 노출 검증
- [ ] **[Step 3-P-Sec-2][R-34] `LockNameValidator` charset 화이트리스트 테스트** — `^[A-Za-z0-9_:.\-]+$` 외 문자 (e.g., space, `/`, `;`) 시 fail. max length 256 초과 시 fail
- [ ] **[Step 3-P-Sec-2][R-34] `lock-name-prefix` 자동 prefix 테스트** — default `${spring.application.name}:` 적용 + empty string opt-out 동작
- [ ] **[Step 3-P-Sec-3][R-33] `LeaderBackendException` wrapping 테스트** — RETHROW 모드 backend throw → wrapped exception, message 에 host/credentials 미포함 검증. cause 는 보존
- [ ] **[Step 3-P-Rel-1] body 예외 vs backend 예외 분리 테스트** — RETHROW 모드에서 본문 throw 시 wrapping 없이 그대로 전파 (LeaderBackendException 으로 wrap 되지 않음). backend throw 시에만 wrapping
- [ ] **[Step 3-P-Rel-2][CLAUDE.md memory] CancellationException 우선 재throw 테스트** — SKIP 모드에서도 CancellationException 흡수되지 않고 재throw
- [ ] **plain SpEL 평가 테스트** — `#region`, `#user.tenantId`, `"'prefix-' + #arg"` (TemplateParserContext 없음 검증, 리터럴 prefix 따옴표 검증) [H1][R-28][Q-X2]
- [ ] **plain SpEL 잘못된 문법 startup fail 테스트** — `"process-#region"` (따옴표 없는 리터럴 prefix) → SpEL pre-parse 실패 with method FQN [H1][R-28][H6]
- [ ] Caffeine cache 상한 검증 (1024 초과 시 evict) [R-17]
- [ ] SpEL pre-parse 테스트 — 잘못된 표현식 → startup fail with method FQN [H6]
- [ ] **`${...}` Spring property placeholder 평가 통합 테스트** (ShedLock 차용)
- [ ] **`DurationParser` 두 형식 단위 테스트** — `"10s"` / `"5m"` / `"1h"` simple + `"PT10S"` / `"PT1H"` ISO-8601 (ShedLock 차용)
- [ ] **proxy → target class annotation lookup 폴백 테스트** — 인터페이스 메서드에만 `@LeaderElection` 부착 + 구현체 분리 케이스 [R-24]
- [ ] `AbstractLeaderElectionAspect`, `AbstractLeaderGroupElectionAspect` (sync `T?` only)
- [ ] `LeaderElectionFactory` / `LeaderGroupElectionFactory` SPI 2종 + **6 백엔드 sync 구현** (Local, Lettuce, Redisson, MongoDB sync, Hazelcast, Exposed JDBC) [C1][R-20][M2]
- [ ] **`FactoryCacheKey(factoryBeanName, options)` 캐싱 검증** — 다중 백엔드 같은 옵션 → 다른 instance 캐싱 (cross-backend collision 방지) [C1][R-26]
- [ ] **`bean` 필드 factory bean 이름 명시 + 잘못된 bean 이름 → 명확한 에러** (`NoSuchBeanDefinitionException` 또는 `BeanNotOfRequiredTypeException`) [H1][R-25]
- [ ] `LeaderAspectFailureMode` 매트릭스 테스트 (RETHROW/SKIP × 백엔드 throw × 본문 throw)
- [ ] 본문 throw 시 백엔드 unlock 회귀 테스트 (6 백엔드) [C-4][R-19]
- [ ] `LeaderAnnotationValidatorBeanPostProcessor` 검출 + strict 모드 매트릭스 [H5][R-22] — final/private/maxLeaders/**suspend/reactive 위반 검출 + #80 안내**
- [ ] **best-effort self-invocation WARN 테스트** — 같은 클래스에 `@LeaderElection`/`@LeaderGroupElection` 메서드 2+ 있으면 WARN (정확 검출 X — strict 모드도 WARN) [M2][R-31]
- [ ] `maxLeaders ≤ 1` startup fail 검증 [H7]
- [ ] `LeaderAopHealthIndicator` 노출 [M4]
- [ ] **elapsed > leaseTime*0.8 WARN 로그 검증** [H10]
- [ ] **`LeaderAopMetricsRecorder` 6 콜백 invoke 검증 — best-effort** (`onLockAttempt`/`onLockAcquired`/`onLockNotAcquired`/`onTaskStarted`/`onTaskFinished`/`onTaskFailed`) + **멀티 빈 주입 검증** (`List<...>` ObjectProvider). 본문 null vs 미선출 정확 분리는 [#85] 후속 — best-effort 검증만 [M1][R-30]
- [ ] **`@Role(ROLE_INFRASTRUCTURE)` 빈 Actuator `/beans` 엔드포인트 노출 검증** (선택)
- [ ] **전역 default property fallback 테스트** — 어노테이션 빈 → property → 코어 `Default` 우선순위 (`default-lease-time`, `default-wait-time`)
- [ ] Aspect 단위 테스트 base class (mock `ProceedingJoinPoint` + fake `LeaderElection`) [M6]
- [ ] `leader-spring-boot3-aop` 모듈 + `LeaderAopAutoConfiguration` + `AutoConfiguration.imports` + **`@ConditionalOnBean(LeaderElectionFactory)` factory-only 고정** + `@ConditionalOnProperty` [H2][R-29][M3]
- [ ] `leader-spring-boot4-aspectj` 모듈 + Freefair AspectJ Gradle 플러그인 + 동일 자동설정 [M3]
- [ ] **`settings.gradle.kts` 신규 module include** — `:leader-spring-boot3-aop`, `:leader-spring-boot4-aspectj` [M3]
- [ ] **`@ConditionalOnBean(LeaderElectionFactory)` factory 미등록 환경 비활성 검증** — 사용자가 `LeaderElection` 빈만 직접 등록 (factory 없음) → AOP autoconfig 비활성 + 명확한 마이그레이션 가이드 [H2][R-29]
- [ ] `@SpringBootTest` 통합 테스트 (Boot3, Boot4 — sync `T?` only, group, bean 명시, failureMode RETHROW/SKIP)
- [ ] Kover line coverage ≥ 80% (3개 모듈 — common, boot3-aop, boot4-aspectj)
- [ ] README.md + README.ko.md (반환 타입 매트릭스 sync only + #80 안내 / `bean` 필드는 factory 빈 이름 / **plain SpEL 문법 — 리터럴 prefix 는 따옴표** [H1][R-28] / failureMode RETHROW/SKIP / strict / 외부 advice 순서 / allopen / 0.x 마이그레이션 가이드 / Duration 두 형식 / `${...}` placeholder / **메타 어노테이션 미지원 + #84 안내** / **metrics best-effort 한계 + #85 안내** / **self-invocation 정확 검출 한계 — AspectJ LTW 권장** [M2][R-31] / `minLeaseTime` 미지원 + #77 안내 / `FAIL_OPEN_RUN` 미지원 + #81 안내 / `TemplateParserContext` 미지원 + #82 안내 / **factory 미등록 환경 AOP 비활성 + 마이그레이션 가이드** [H2][R-29])
- [ ] KDoc — `@param`, `@sample`, 반환 매트릭스 명시 (모든 public API) — `bean` 필드 KDoc 에 "factory bean name" 강조 + `name` 필드 KDoc 에 "plain SpEL — 리터럴 prefix 는 따옴표" 명시
- [ ] ShedLock 매핑 표 README (8건 차용 항목 + `minLeaseTime`/`leaderId` 미지원 사유 명시)
- [ ] `CLAUDE.md` Repository Layout 갱신 (신규 모듈 `leader-spring-boot3-aop`/`leader-spring-boot4-aspectj` 추가, AOP 사용 가이드)
- [ ] `nightly.yml` 신규 모듈 job 추가

**제거된 DoD** (sync only + Codex 2nd round 결정):
- ~~suspend / Mono 통합 테스트~~ → [#80]
- ~~`LeaderElectionInfo` CoroutineContext 검증~~ → [#80]
- ~~`kotlinx-coroutines-reactor` 의존성 격리~~ → [#80] (의존성 자체 제거)
- ~~reactive subscribe당 락 1회 (`Mono.defer`)~~ → [#80]
- ~~Reactor `Hooks.enableAutomaticContextPropagation()` 가이드~~ → [#80]
- ~~`SuspendLeaderElectionFactory` SPI~~ → [#80]
- ~~`FAIL_OPEN_RUN` failureMode 매트릭스~~ → [#81]
- ~~`TemplateParserContext` 혼합 표현식 테스트~~ → [#82]
- ~~`leaderId` SpEL 테스트~~ → [#72]
- ~~Flux/Flow 통합 테스트~~ → [#74] / [#80]
- ~~Micrometer 실제 구현~~ → [#75]
- ~~디렉토리 재구조화~~ → [#76]
- ~~`minLeaseTime` AOP sleep 공식 검증~~ → [#77] (Aspect sleep 패턴 자체 폐기)
- ~~`@Target(METHOD, ANNOTATION_TYPE)` 메타-어노테이션 컴포지션 동작 검증~~ → [#84] [C2][R-27]
- ~~`@AliasFor` 메타 어노테이션 컴포지션 테스트 (`@JobLock` → `@LeaderElection`)~~ → [#84] [C2][R-27]
- ~~정확한 self-invocation 호출 그래프 분석 테스트~~ → best-effort WARN 으로 변경 [M2][R-31]
- ~~metrics 정확한 elected/skipped 분리 테스트~~ → best-effort 로 완화 [#85] [M1][R-30]

---

## Grounding 라벨 요약

- **Step 1-R 검증**: AspectJ × Spring AOP 호환, `SpelExpressionParser` thread-safe
- **Step 2-R Critic**: Q1 RETHROW default, Q2 nameProvider v2 보류, Q3 watchdog [#73] 분리, Q4 SimpleEvaluationContext, Q5 디렉토리 [#76] 분리, Q6 artifact ID 유지, Q7 metrics [#75] 분리, Q8 Flow [#74]/[#80] 분리, Q9 strict 모드 default WARN
- **Step 2-R Codex**: C1 `LeaderElectionFactory` SPI, ~~C2 minLeaseTime AOP sleep~~ → [#77] 백엔드 TTL 위임, C4 Mono만 → sync only로 변경 [#80], C5 leaderId 본 PR 제거 [#72], C6 어노테이션 본문 일관성
- **Step 3-R ShedLock 차용 (8건)**: (1) `findAnnotationWithTargetFallback` 인터페이스 메서드 lookup 폴백 [R-24], (2) DurationParser simple `"10s"` + ISO-8601 `"PT10S"` 양쪽, (3) `${...}` Spring property placeholder, (4) ~~`TemplateParserContext("#{","}")` 템플릿 모드~~ → 본 PR plain SpEL 만 [Q-X2][#82], (5) 전역 default property `default-lease-time`/`default-wait-time`, (6) `@Role(ROLE_INFRASTRUCTURE)` Aspect/AutoConfig 빈, (7) Listener 6 콜백, (8) `List<LeaderAopMetricsRecorder>` 멀티 빈 주입
- **CRITICAL 추가**: ~~A1/C-3 subscribe당 락 1회~~ → [#80], A2/C-4 백엔드 unlock 계약
- **HIGH 적용**: H1 (factory bean name 명확화) [R-25], ~~H2 (`@ConditionalOnBean(LeaderElectionFactory or LeaderElection)`)~~ → factory-only 고정 [R-29], H3~H10 spec 반영 (~~H9 `LeaderElectionInfo`~~ → [#80])
- **MEDIUM 적용**: ~~M1 (메타 어노테이션 `@AliasFor` 패턴) [Q-X3]~~ → 본 PR 제거 [#84], M2 (6 백엔드 명시), M3, M4, M6 spec 반영
- **Codex 2nd round 7건 (사용자 승인)**:
  - **C1** [R-26]: factory cache key → `FactoryCacheKey(factoryBeanName, options)` (cross-backend collision 방지)
  - **C2** [R-27]: 메타 어노테이션 (`@JobLock`/`@AliasFor`) 본 PR 제거 → 후속 [#84] (`AnnotationMatchingPointcut` advisor 별도 설계 필요)
  - **H1** [R-28]: SpEL plain 문법 — 리터럴 prefix 는 따옴표 (`"'process-' + #region"`)
  - **H2** [R-29]: `@ConditionalOnBean(LeaderElectionFactory)` factory-only 고정 (factory 미등록 환경 AOP 비활성)
  - **M1** [R-30]: metrics best-effort + 정확한 elected/skipped 분리 → 후속 [#85] sealed `LeaderRunResult` SPI
  - **M2** [R-31]: self-invocation best-effort WARN (정확 검출은 AspectJ LTW 또는 ASM/Javassist 필요 — 본 PR scope 외)
  - **M3**: 신규 module **flat naming** — `leader-spring-boot3-aop`, `leader-spring-boot4-aspectj` (`settings.gradle.kts` include)
- **FINAL 결정 (사용자 승인)**:
  - **sync only** — suspend/Mono/Flow/Reactor 모두 [#80] 후속
  - **Q-X1**: `LeaderAspectFailureMode { RETHROW, SKIP }` — `FAIL_OPEN_RUN` [#81] 후속
  - **Q-X2**: plain SpEL — `TemplateParserContext` [#82] 후속
  - ~~**Q-X3**: 메타 어노테이션 `@AliasFor` 패턴 명시~~ → 본 PR 제거 [C2][R-27][#84]
  - **C1**: 의사코드 `factory.create(opts).runIfLeader(name) { proceed() }` 패턴 명확화 + cache key `FactoryCacheKey` 갱신 [R-26]
  - **H1**: `bean` 필드는 **factory bean name** [R-25]
- **후속 이슈 cross-ref (총 13건)**: #72, #73, #74, #75, #76, #77, #78, #79 (기존 8건) + #80, #81, #82 (이전 갱신 3건) + **#84 메타 어노테이션 (`@AliasFor`) 지원**, **#85 백엔드 SPI elected vs skipped 분리** (Codex 2nd round 신규 2건)
- **보류 — 구현 시 verify**: Freefair AspectJ Gradle 플러그인 버전 호환성 (Boot 4 / Kotlin 2.3 / JVM 21), Boot 4 GA 시점 `@AutoConfiguration` API, `SimpleEvaluationContext` 의 `withMethodResolvers()` Spring 6.2 호환 정확한 메서드 시그니처
