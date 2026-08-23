# Scheduled Task Property 기반 Leader Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `@Scheduled` method을 YAML의 exact `beanName#methodName` selector로 선택해 기존 Spring scheduler와 `LeaderElectionAspect` 경로를 재사용하는 property 기반 leader policy를 추가한다.

**Architecture:** `LeaderScheduledPolicyProperties`가 별도 configuration model을 제공하고, `LeaderScheduledPolicyBeanPostProcessor`가 startup에 `@Scheduled` method와 policy를 exact-match해 immutable metadata registry를 만든다. `LeaderElectionAspect`는 명시적 annotation을 먼저 해석하고, annotation이 없을 때만 registry policy를 `AdviceMetadata`로 변환하며, 두 metadata가 없으면 즉시 `pjp.proceed()`한다. Spring `ScheduledAnnotationBeanPostProcessor`, `ScheduledTaskRegistrar`, trigger, subscription, Observation lifecycle은 변경하지 않는다.

**Tech Stack:** Kotlin/JVM, Spring Boot 4, Spring Framework 7.0.8, AspectJ compile-time weaving, Spring `ApplicationContextRunner`, JUnit 5, MockK, Reactor, Kotlin Coroutines, Micrometer Observation.

---

## 계획 전제와 파일 경계

작업 기준은 다음 승인 산출물과 현재 source이다.

- Spec: `docs/superpowers/specs/2026-08-23-issue-603-scheduled-policy-design.md`
- Repository: `bluetape4k-leader`
- Worktree: `/Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-epic-spring-s-02-scheduled-policy`
- Branch: `feat/epic-spring-s-02-scheduled-policy`
- Base: `origin/develop` at `f5e1062c815b2c743ad5ecabd5105467224203cc`
- Existing baseline: `./gradlew :bluetape4k-leader-spring-boot:test` passed before implementation.

### 생성 파일

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyProperties.kt`
  - `bluetape4k.leader.scheduling` binding model과 nested `Policy`를 정의한다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyRegistry.kt`
  - startup에 등록된 target/method/policy binding을 immutable lookup으로 제공한다. scheduler task나 trigger를 만들지 않는다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyBeanPostProcessor.kt`
  - user bean의 merged `@Scheduled` method를 수집하고 policy validation, exact selector match, unmatched selector fail-fast를 담당한다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyAutoConfiguration.kt`
  - enabled 조건, properties, registry, BPP를 등록하고 `LeaderAopAutoConfiguration`보다 먼저 적용한다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderMethodValidationSupport.kt`
  - annotation policy와 property policy가 공유하는 final/private, Future, stream, min-lease, SpEL validation을 분리한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyPropertiesTest.kt`
  - defaults와 YAML-style binding 계약을 검증한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyRegistryTest.kt`
  - exact selector, explicit annotation skip, duplicate/overload/unmatched validation과 target identity lookup을 검증한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyBeanPostProcessorTest.kt`
  - policy BPP의 scheduled scan, explicit precedence, startup failure, ordering을 검증한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyAutoConfigurationTest.kt`
  - ApplicationContextRunner 조건, startup failure, auto-configuration ordering을 검증한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectScheduledPolicyTest.kt`
  - plain `@Scheduled` bypass, property precedence, sync/suspend/Mono/Flux/Flow metadata path를 검증한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledTaskLifecycleTest.kt`
  - Spring task cardinality, scheduler close, Observation 중복 등록 방지를 검증한다.

### 수정 파일

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt`
  - plain `@Scheduled` pointcut, optional registry, explicit/property/bypass resolution, target-aware metadata cache를 추가한다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
  - optional `LeaderScheduledPolicyRegistry`를 aspect에 주입한다.
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
  - 공통 method validation support를 사용하도록 바꾼다.
- `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - `LeaderScheduledPolicyAutoConfiguration`을 factory 이후, AOP 이전에 등록한다.
- `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
  - scheduling group와 policy property metadata를 추가한다.
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`
  - 새 enabled/default property metadata를 검증한다.
- `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
  - YAML-only selector 예시, precedence, stable bean-name 선택, rollback과 제한 사항을 양쪽 locale에 추가한다.

공개 `LeaderProperties`의 constructor/copy ABI, `@LeaderScheduled` contract, 새 module/dependency, versioned manual은 변경하지 않는다.

## Plan Writer DoD (SPW-01..SPW-05)

| Check | Status | Evidence |
| --- | --- | --- |
| SPW-01 scope, audience, source ledger, identifiers, unknowns | PASS | 승인된 Issue #603 spec, current source paths, base SHA, worktree/branch, explicit non-goals recorded above |
| SPW-02 executable implementation plan | PASS | Tasks 1-12 pin exact files, failing-first tests, implementation order, commands, rollback points, and approval gates |
| SPW-03 Korean reader-facing plan quality | PASS | Korean naturalness checklist applied; `audit-korean-terms.mjs` reports `findings=0` |
| SPW-04 spec-to-plan traceability | PASS | AC-01..12, AC-10a, and spec DoD map to owning tasks and evidence in the traceability table |
| SPW-05 final Markdown readback | PASS | Full plan read back after edits; placeholder scan and `git diff --no-index --check` are clean |

## 구현 전 승인 게이트

- [x] 이 계획의 6개 관점 통합 검토가 `P0=0`, `P1=0`으로 수렴한다.
- [x] 사용자가 이 계획을 승인한다.
- [x] 계획 승인 후에만 다음 Lore 형식으로 spec과 plan을 함께 커밋한다.

```text
scheduled policy 구현 순서와 검증 경계를 고정한다

승인된 scheduled policy 설계를 properties, registry, AOP fallback, lifecycle 검증으로 분해한다.

Constraint: 기존 Spring scheduler와 LeaderProperties ABI를 유지해야 한다.
Rejected: scheduler 교체와 dynamic task registry | task/Observation lifecycle 중복 위험
Confidence: high
Scope-risk: moderate
Directive: registry는 policy metadata만 소유하고 task/trigger를 생성하지 않는다.
Tested: spec SPW-01..05, baseline Spring Boot test, spec mutation-check
Not-tested: 구현 전 targeted policy tests
```

명령:

```bash
git add docs/superpowers/specs/2026-08-23-issue-603-scheduled-policy-design.md \
        docs/superpowers/plans/2026-08-23-issue-603-scheduled-policy-plan.md
git commit -F - <<'EOF'
scheduled policy 구현 순서와 검증 경계를 고정한다

승인된 scheduled policy 설계를 properties, registry, AOP fallback, lifecycle 검증으로 분해한다.

Constraint: 기존 Spring scheduler와 LeaderProperties ABI를 유지해야 한다.
Rejected: scheduler 교체와 dynamic task registry | task/Observation lifecycle 중복 위험
Confidence: high
Scope-risk: moderate
Directive: registry는 policy metadata만 소유하고 task/trigger를 생성하지 않는다.
Tested: spec SPW-01..05, baseline Spring Boot test, spec mutation-check
Not-tested: 구현 전 targeted policy tests
EOF
```

Expected: 두 문서가 같은 commit에 있고 `git status --short`에는 runtime ignored state를 제외한 product 변경만 나타난다.

## Task 1: Property model과 binding 실패 테스트 작성

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyPropertiesTest.kt`
- Reference: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/properties/LeaderAopProperties.kt`
- Reference: `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderAspectFailureMode.kt`

- [x] **Step 1: 기본값과 full binding을 먼저 테스트한다.**

```kotlin
@Test
fun `scheduling policy defaults and yaml keys bind`() {
    val source = MapConfigurationPropertySource(
        mapOf(
            "bluetape4k.leader.scheduling.enabled" to "true",
            "bluetape4k.leader.scheduling.policies[0].selector" to "orderJob#reconcile",
            "bluetape4k.leader.scheduling.policies[0].name" to "orders:reconcile",
            "bluetape4k.leader.scheduling.policies[0].wait-time" to "0s",
            "bluetape4k.leader.scheduling.policies[0].lease-time" to "30s",
            "bluetape4k.leader.scheduling.policies[0].min-lease-time" to "5s",
            "bluetape4k.leader.scheduling.policies[0].bean" to "redisLeaderElectionFactory",
            "bluetape4k.leader.scheduling.policies[0].auto-extend" to "false",
            "bluetape4k.leader.scheduling.policies[0].stream-bounded" to "false",
            "bluetape4k.leader.scheduling.policies[0].failure-mode" to "SKIP",
        ),
    )

    val props = Binder(source)
        .bindOrCreate(LeaderScheduledPolicyProperties.PREFIX, LeaderScheduledPolicyProperties::class.java)

    props.enabled.shouldBeTrue()
    props.policies.single().selector shouldBeEqualTo "orderJob#reconcile"
    props.policies.single().leaseTime shouldBeEqualTo Duration.ofSeconds(30)
    props.policies.single().failureMode shouldBeEqualTo LeaderAspectFailureMode.SKIP
}

@Test
fun `empty source disables scheduling policy by default`() {
    val props = Binder(MapConfigurationPropertySource(emptyMap<String, String>()))
        .bindOrCreate(LeaderScheduledPolicyProperties.PREFIX, LeaderScheduledPolicyProperties::class.java)

    props.enabled.shouldBeFalse()
    props.policies.shouldBeEmpty()
}
```

- [x] **Step 2: invalid semantic values의 실패 기대를 고정한다.**

`enabled=true`와 empty policy, blank selector, selector without exactly one `#`, blank name, `waitTime < 0`, `leaseTime <= 0`, `minLeaseTime > leaseTime`, overloaded selector, invalid SpEL, unresolved backend bean은 property binder가 아니라 registry/BPP startup validation에서 `IllegalStateException` 또는 기존 bean-selection 예외로 실패해야 한다. 이 테스트 단계에서는 binding 자체가 값을 보존하는지와 invalid duration 문자열이 binder에서 거부되는지만 확인한다.

```kotlin
@Test
fun `invalid duration text is rejected by Spring Binder`() {
    val source = MapConfigurationPropertySource(
        mapOf("bluetape4k.leader.scheduling.policies[0].lease-time" to "not-a-duration"),
    )

    assertFailsWith<Exception> {
        Binder(source).bindOrCreate(
            LeaderScheduledPolicyProperties.PREFIX,
            LeaderScheduledPolicyProperties::class.java,
        )
    }
}
```

- [x] **Step 3: 현재 구현으로 실패를 확인한다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyPropertiesTest'
```

Expected: `FAIL` because `LeaderScheduledPolicyProperties` does not exist.

## Task 2: Property model 구현과 semantic helper 작성

**Files:**
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyProperties.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderMethodValidationSupport.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyPropertiesTest.kt`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessorTest.kt`

- [x] **Step 1: public additive properties model을 작성한다.**

```kotlin
@ConfigurationProperties(prefix = LeaderScheduledPolicyProperties.PREFIX)
data class LeaderScheduledPolicyProperties(
    val enabled: Boolean = false,
    val policies: List<Policy> = emptyList(),
) : Serializable {
    data class Policy(
        val selector: String = "",
        val name: String = "",
        val waitTime: Duration? = null,
        val leaseTime: Duration? = null,
        val minLeaseTime: Duration = Duration.ZERO,
        val bean: String = "",
        val autoExtend: Boolean = false,
        val streamBounded: Boolean = false,
        val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
    ) : Serializable

    companion object {
        const val PREFIX = "bluetape4k.leader.scheduling"
        private const val serialVersionUID = 1L
    }
}
```

`waitTime`와 `leaseTime`이 `null`이면 `LeaderAopProperties.defaultWaitTime/defaultLeaseTime`을 사용한다. `LeaderProperties`에는 scheduling field를 추가하지 않는다.

- [x] **Step 2: 공통 method validation을 기존 annotation BPP와 property BPP가 공유하도록 분리한다.**

`LeaderMethodValidationSupport`는 다음 입력을 받아 기존 메시지 의미와 strict/warn 정책을 유지한다.

```kotlin
internal fun validateSingle(
    method: Method,
    beanName: String,
    targetClass: Class<*>,
    nameExpression: String,
    leaseTime: Duration,
    minLeaseTime: Duration,
    autoExtend: Boolean,
    streamBounded: Boolean,
)
```

공유 support는 final/private, `Future`/`CompletableFuture`/`Deferred`, Flux/Flow의 `autoExtend || streamBounded`, min-lease 관계, `SpelExpressionEvaluator.preParse`를 담당한다. `LeaderGroupElection`의 `maxLeaders` 검증은 기존 validator에 남긴다. 기존 annotation 테스트의 strict failure와 warning 기대가 그대로 통과해야 한다.

- [x] **Step 3: property model 테스트를 green으로 만든다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyPropertiesTest' \
  --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest'
```

Expected: targeted tests `BUILD SUCCESSFUL`.

## Task 3: Registry/BPP의 exact selector 실패 테스트 작성

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyRegistryTest.kt`
- Reference: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduled.kt`
- Reference: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderBeanSelector.kt`

- [x] **Step 1: test fixture를 plain `@Scheduled`, explicit annotation, overload로 분리한다.**

```kotlin
private class ScheduledFixture {
    @Scheduled(fixedDelay = Long.MAX_VALUE)
    fun reconcile() = Unit

    @LeaderScheduled(name = "explicit-reconcile", fixedDelay = Long.MAX_VALUE)
    fun explicit() = Unit

    @Scheduled(fixedDelay = Long.MAX_VALUE)
    fun overloaded(value: String) = value

    @Scheduled(fixedDelay = Long.MAX_VALUE)
    fun overloaded(value: Int) = value
}
```

- [x] **Step 2: registry success, explicit precedence, and target identity lookup를 테스트한다.**

Assert that `orderJob#reconcile` returns one policy binding, `explicit` is marked as observed but not registered as a property policy, and the same method signature on a second target instance does not reuse a first target's binding. The lookup must be O(1) map access after `freeze()` and must not scan annotations on each call.

- [x] **Step 3: duplicate, missing, non-scheduled, overload, and invalid policy tests를 추가한다.**

Each case must fail during `afterSingletonsInstantiated()` with an error containing the selector and property field. `enabled=true` with no policy must fail before the context is considered usable. A selector targeting an explicit leader annotation counts as an observed valid method and uses annotation precedence.

- [x] **Step 4: 현재 구현으로 실패를 확인한다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistryTest'
```

Expected: `FAIL` because registry and BPP do not exist.

## Task 4: Immutable registry와 policy BPP 구현

**Files:**
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyRegistry.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyBeanPostProcessor.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyRegistryTest.kt`

- [x] **Step 1: registry의 mutable-build/immutable-read 경계를 구현한다.**

```kotlin
class LeaderScheduledPolicyRegistry(
    configured: List<LeaderScheduledPolicyProperties.Policy>,
) {
    fun register(beanName: String, target: Any, method: Method, policy: LeaderScheduledPolicyProperties.Policy)
    fun markObserved(selector: String)
    fun freeze()
    fun lookup(method: Method, target: Any): LeaderScheduledPolicyProperties.Policy?
}
```

The registry parses `beanName#methodName` exactly once, rejects blank/extra separators, duplicate selectors, and ambiguous overloads, then freezes a target-identity plus method-signature map. `lookup` accepts the woven method and target instance so two bean instances of the same class cannot share a wrong policy. After `freeze`, registration mutates nothing.

- [x] **Step 2: BPP가 user bean의 merged scheduled method만 수집하도록 구현한다.**

Use `AopUtils.getTargetClass(bean)` and `MethodIntrospector.selectMethods`/merged annotation lookup. Skip `BeanPostProcessor`, `MethodInterceptor`, `@Aspect`, and `org.springframework.*` infrastructure. For each `@Scheduled` method:

1. derive canonical `beanName#methodName`;
2. mark an exact configured selector as observed;
3. skip property registration when merged `@LeaderElection` or `@LeaderScheduled` exists;
4. reject a second matching overload;
5. validate name/duration/backend/stream/method shape;
6. register the property binding.

The BPP implements `SmartInitializingSingleton` and performs the configured-versus-observed selector comparison in `afterSingletonsInstantiated()`. It is `PriorityOrdered` so scanning precedes Spring's scheduled task finalization; failed context shutdown cancels any framework-owned registrations. It never creates a `ScheduledTask`, `TaskScheduler`, executor, trigger, subscription, or Observation callback.

- [x] **Step 3: backend and SpEL validation을 기존 component로 연결한다.**

Use `LeaderBeanSelector.selectElectionFactory(policy.bean, method)` and the suspend selector for suspend/Mono/Flux/Flow methods. Resolve blank `waitTime`/`leaseTime` from `LeaderAopProperties`; require wait `>= 0`, lease `> 0`, and min-lease `<= lease`. Call `LeaderMethodValidationSupport` with the policy name expression. Error messages include `beanName#methodName` and the failing property name, never resolved lock names, backend addresses, or credential-like values.

- [x] **Step 4: registry tests를 green으로 만든다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistryTest' \
  --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest'
```

Expected: exact selector success and every invalid selector/policy failure test passes.

## Task 5: Auto-configuration과 metadata 실패 테스트 작성

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyAutoConfigurationTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`
- Reference: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [x] **Step 1: ApplicationContextRunner 조건을 고정한다.**

Use `AutoConfigurations.of(LeaderAopFactoryAutoConfiguration::class.java, LeaderScheduledPolicyAutoConfiguration::class.java, LeaderAopAutoConfiguration::class.java)` and a user configuration with `@EnableScheduling`, one explicit `LeaderElectorFactory`, and one plain `@Scheduled` bean. Test:

```kotlin
runner.run { ctx ->
    ctx.containsBean("leaderScheduledPolicyRegistry").shouldBeFalse()
    ctx.containsBean("leaderScheduledPolicyBeanPostProcessor").shouldBeFalse()
}

runner.withPropertyValues(
    "bluetape4k.leader.scheduling.enabled=true",
    "bluetape4k.leader.scheduling.policies[0].selector=scheduledFixture#reconcile",
    "bluetape4k.leader.scheduling.policies[0].name=orders:reconcile",
).run { ctx ->
    ctx.getBean(LeaderScheduledPolicyRegistry::class.java).shouldNotBeNull()
}
```

- [x] **Step 2: empty policy, missing selector, duplicate selector, non-scheduled selector, and backend errors의 context failure를 고정한다.**

Use `ctx.startupFailure.shouldNotBeNull()` and assert the failure message contains the exact selector/property. The disabled path must keep the normal `@Scheduled` bean and must not create the registry/BPP.

- [x] **Step 3: imports order와 metadata assertions를 먼저 추가한다.**

Assert `LeaderAopFactoryAutoConfiguration` index `<` `LeaderScheduledPolicyAutoConfiguration` index `<` `LeaderAopAutoConfiguration` index. Assert metadata contains:

```text
bluetape4k.leader.scheduling.enabled = false
bluetape4k.leader.scheduling.policies
```

- [x] **Step 4: 현재 구현으로 실패를 확인한다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyAutoConfigurationTest' \
  --tests 'io.bluetape4k.leader.spring.metadata.LeaderConfigurationMetadataTest'
```

Expected: `FAIL` because the auto-configuration, import entry, and metadata do not exist.

## Task 6: Auto-configuration, imports, and configuration metadata 구현

**Files:**
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledPolicyAutoConfigurationTest.kt`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`

- [x] **Step 1: conditional auto-configuration을 등록한다.**

```kotlin
@AutoConfiguration(
    after = [LeaderAopFactoryAutoConfiguration::class],
    before = [LeaderAopAutoConfiguration::class],
)
@ConditionalOnClass(name = [
    "org.aspectj.lang.annotation.Aspect",
    "org.springframework.scheduling.annotation.Scheduled",
])
@ConditionalOnBean(LeaderElectorFactory::class)
@ConditionalOnProperty(
    prefix = LeaderScheduledPolicyProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@EnableConfigurationProperties(LeaderScheduledPolicyProperties::class)
class LeaderScheduledPolicyAutoConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderScheduledPolicyRegistry(props: LeaderScheduledPolicyProperties) =
        LeaderScheduledPolicyRegistry(props.policies)

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun leaderScheduledPolicyBeanPostProcessor(
        registry: LeaderScheduledPolicyRegistry,
        props: LeaderScheduledPolicyProperties,
        aopProps: LeaderAopProperties,
        beanSelector: LeaderBeanSelector,
        spel: SpelExpressionEvaluator,
    ) = LeaderScheduledPolicyBeanPostProcessor(registry, props, aopProps, beanSelector, spel)
}
```

The registry/BPP beans exist only when scheduling is explicitly enabled. Existing `LeaderAopAutoConfiguration` receives `ObjectProvider<LeaderScheduledPolicyRegistry>` and passes `getIfAvailable()` to the aspect. Add a secondary five-argument `LeaderElectionAspect` constructor delegating to a sixth optional registry slot so existing direct construction remains source/JVM-compatible.

- [x] **Step 2: imports와 metadata를 함께 변경한다.**

Insert the new import after `LeaderAopFactoryAutoConfiguration` and before `LeaderAopAutoConfiguration`. Add the group and property entries for `enabled`, `policies`, and each nested policy field with default `false`/empty values or descriptions matching the spec. Do not edit the pinned release manual.

- [x] **Step 3: auto-configuration tests를 green으로 만든다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyAutoConfigurationTest' \
  --tests 'io.bluetape4k.leader.spring.metadata.LeaderConfigurationMetadataTest'
```

Expected: disabled/enabled conditions, startup failures, import order, and metadata all pass.

## Task 7: Aspect fallback과 precedence 실패 테스트 작성

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectScheduledPolicyTest.kt`
- Reference: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectTest.kt`
- Reference: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/internal/AdviceMetadata.kt`

- [x] **Step 1: fake registry와 plain scheduled fixture를 만든다.**

```kotlin
private class ScheduledTarget {
    @Scheduled(fixedDelay = Long.MAX_VALUE)
    fun propertyJob(): String = "property"

    @LeaderElection(name = "annotation-job", bean = "annotationFactory")
    @Scheduled(fixedDelay = Long.MAX_VALUE)
    fun explicitJob(): String = "annotation"
}
```

Configure the fake registry to return a policy only for `propertyJob`, and configure `LeaderBeanSelector` mocks for sync and suspend factory selection.

- [x] **Step 2: bypass and explicit precedence를 검증한다.**

For a plain `@Scheduled` method with no policy, `aspect.aroundLeader(pjp)` must return `pjp.proceed()` and verify zero `LeaderBeanSelector`, factory, backend, and recorder calls. For `explicitJob`, the annotation name/bean/options must win even when the registry returns a conflicting policy.

- [x] **Step 3: policy metadata와 failure semantics를 검증한다.**

Verify `name`, defaulted wait/lease, min-lease, `autoExtend`, `streamBounded`, selected bean, and `failureMode` reach the existing sync path. A mocked `LeaderRunResult.Skipped` returns `null` without invoking the body; backend errors follow `SKIP`, `RETHROW`, and `FAIL_OPEN_RUN` exactly as annotation tests do.

- [x] **Step 4: all return branches and cancellation를 검증한다.**

Add direct join-point cases for suspend, `Mono`, `Flux`, and `Flow`. Assert stream policies with neither `autoExtend` nor `streamBounded` fail before body execution, while allowed streams preserve existing release/cancellation behavior. Cancelled suspend/reactive calls rethrow `CancellationException` and do not convert it to backend failure.

- [x] **Step 5: 현재 구현으로 실패를 확인한다.**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspectScheduledPolicyTest'
```

Expected: `FAIL` because the pointcut and registry fallback do not exist.

## Task 8: Aspect pointcut, metadata resolution, and target-aware cache 구현

**Files:**
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectScheduledPolicyTest.kt`
- Regression tests: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectTest.kt`, `LeaderElectionAspectStreamTest.kt`, `LeaderElectionAspectSuspendMonoTest.kt`

- [x] **Step 1: pointcut과 optional registry를 연결한다.**

Extend the existing execution pointcut with `@annotation(org.springframework.scheduling.annotation.Scheduled)`. Keep the existing explicit leader annotation expressions. Add `scheduledPolicyRegistry: LeaderScheduledPolicyRegistry?` to the primary constructor and retain the existing five-argument secondary constructor.

- [x] **Step 2: absent/present metadata sentinel을 도입한다.**

Use a non-null cache value so `ConcurrentHashMap` can represent bypass without storing `null`:

```kotlin
private sealed interface MetadataResolution {
    data class Present(val metadata: AdviceMetadata) : MetadataResolution
    data object Bypass : MetadataResolution
}

private class TargetMethodCacheKey(val target: Any, val method: Method) {
    override fun equals(other: Any?): Boolean =
        other is TargetMethodCacheKey && target === other.target && method == other.method

    override fun hashCode(): Int = 31 * System.identityHashCode(target) + method.hashCode()
}
```

Resolve explicit `@LeaderElection` first; if absent, call `scheduledPolicyRegistry?.lookup(method, target)`; if both are absent return `Bypass`. Cache by target identity and method so two bean instances with distinct bean-name policies cannot share metadata. For bypass in sync, suspend, `Mono`, `Flux`, and `Flow`, call `pjp.proceed()` immediately and do not allocate a leader factory, backend elector, recorder event, or stream wrapper.

The cache is an instance-owned, non-static field whose lifetime is bounded by the managed aspect bean. Clear it from the aspect bean's destruction callback so target references cannot outlive the application context; do not introduce a global cache or cross-context registry.

- [x] **Step 3: share metadata construction for annotation and property sources.**

Refactor the current `resolveMetadata` body into a private builder accepting name, wait/lease/min-lease, auto-extension, stream-bounded, bean, and failure-mode. The annotation adapter supplies annotation values; the property adapter supplies policy values and AOP defaults. Keep `AdviceMetadata` and all existing execution branches unchanged after construction.

- [x] **Step 4: run all aspect regressions.**

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspect*' \
  --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspectScheduledPolicyTest'
```

Expected: existing annotation sync/reactive/coroutine/failure-mode tests and new property tests pass with no duplicate advice behavior.

## Task 9: Spring scheduler lifecycle, cardinality, and Observation tests

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledTaskLifecycleTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt`
- Reference: Spring 7.0.8 `ScheduledAnnotationBeanPostProcessor` and `ScheduledTaskRegistrar` source jar.

- [x] **Step 1: task cardinality fixture를 만든다.**

Use `@EnableScheduling` and a `@Scheduled(fixedDelay = 50, initialDelay = 60_000)` method so registration occurs without a long-running test trigger. Use a second short-delay fixture with `@Scheduled(fixedDelay = 25, initialDelay = 0)` and a `CountDownLatch` only for the bounded Observation assertion. Collect all `ScheduledTaskHolder.getScheduledTasks()` values before and after enabling one matching policy.

- [x] **Step 2: duplicate registration과 context close를 검증한다.**

Assert the enabled-policy task count equals the disabled-policy task count, the scheduled method has exactly one `ScheduledTask`, and closing the context cancels the framework-owned task. Assert no policy registry method creates a `ScheduledTaskRegistrar`, scheduler, executor, reactive subscription, or background thread.

- [x] **Step 3: Observation path의 단일 등록을 검증한다.**

Use a non-noop `ObservationRegistry` and a recording `ObservationHandler`. Let one short fixed-delay invocation complete under a bounded timeout, then assert one scheduler observation per invocation and no duplicate observation caused by the leader policy. Close the context in `finally` and remove the handler before the next test.

- [x] **Step 4: stability evidence를 실행한다.**

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledTaskLifecycleTest' \
  --tests 'io.bluetape4k.leader.spring.metrics.LeaderObservationAutoConfigurationTest'
```

Expected: task cardinality is unchanged, Observation is single-registered, and context close leaves no scheduled task running.

## Task 10: README locale pair와 configuration metadata 문서화

**Files:**
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`

- [x] **Step 1: English README에 YAML-only policy section을 추가한다.**

Place it next to the existing `@LeaderScheduled` example. Include the exact example:

```yaml
bluetape4k:
  leader:
    scheduling:
      enabled: true
      policies:
        - selector: "orderJob#reconcile"
          name: "orders:reconcile"
          wait-time: 0s
          lease-time: 30s
          min-lease-time: 5s
          bean: "redisLeaderElectionFactory"
          auto-extend: false
          stream-bounded: false
          failure-mode: SKIP
```

Explain explicit annotation > property policy > no metadata, exact selector only, explicit bean-name recommendation, default disabled, fail-fast startup errors, `SKIP` contention semantics, stream constraints, and `enabled=false` rollback. State that Spring still owns scheduling/task Observation and dynamic reload/wildcards are unsupported.

- [x] **Step 2: Korean README를 같은 정보 구조로 자연스럽게 현지화한다.**

Keep all configuration keys, class names, commands, URLs, and enum tokens unchanged. Use Korean technical register for the explanation and retain the locale pair's existing section rhythm.

- [x] **Step 3: metadata and docs validation을 실행한다.**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  leader-spring-boot/README.ko.md
git diff --check
```

Expected: terminology audit reports `findings=0`; no whitespace errors; metadata test passes.

## Task 11: Performance/stability risk review and cleanup

**Files:**
- Review all changed Kotlin files and tests.
- Add regression assertions only to the targeted test files above.

- [x] **Step 1: hot-path invariants를 확인한다.**

Verify with MockK counters that disabled/mismatched scheduled methods do not call `LeaderBeanSelector`, factory creation, backend acquisition, SpEL parsing, or recorder callbacks. Verify matching methods perform registry lookup and metadata construction once per target/method cache key, while each tick performs only the existing leader path.

- [x] **Step 2: lifecycle and concurrency invariants를 확인한다.**

Run repeated context open/close tests to detect mutable registry reuse, double `freeze`, task leaks, or duplicate Observation handlers. Exercise two target instances with different selectors and concurrent first invocations; both must resolve their own immutable policy and no policy map may mutate after startup.

- [x] **Step 3: code simplification pass를 수행한다.**

Delete duplicate validation code, reuse `DurationParser`, `LeaderBeanSelector`, `SpelExpressionEvaluator`, and existing aspect branches, and avoid new dependencies or a second scheduler abstraction. Do not alter behavior outside Issue #603.

## Task 12: Full verification, diff review, and implementation handoff

**Files:**
- All changed files in the feature worktree.
- Optional review artifact: `docs/review/2026-08-23-issue-603-plan-review.md` only if the integrated plan review needs durable PR evidence.

- [x] **Step 1: run targeted tests and module checks.**

```bash
./gradlew :bluetape4k-leader-spring-boot:test \
  --tests 'io.bluetape4k.leader.spring.scheduling.*' \
  --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspect*' \
  --tests 'io.bluetape4k.leader.spring.metadata.LeaderConfigurationMetadataTest'
./gradlew :bluetape4k-leader-spring-boot:test
./gradlew :bluetape4k-leader-spring-boot:aotTest
./gradlew detekt
```

Expected: all commands exit 0; no skipped required policy test is treated as coverage.

- [x] **Step 2: run source and documentation diagnostics.**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  leader-spring-boot/README.ko.md
git status --short
```

Expected: no diff errors, terminology findings, or unrelated tracked changes. Runtime `.bluetape` state remains ignored.

- [x] **Step 3: run final six-lens implementation review before PR.**

Review performance hot path, startup lifecycle, selector/config trust boundary, operator rollback and Observation evidence, API/ABI compatibility, and caller documentation. The integrated review must report `P0=0` and `P1=0`; any blocker returns to the affected TDD task and reruns its proof.

- [x] **Step 4: stop at merge-ready boundary.**

Before PR creation, record changed files, commit SHA, test output, AOT/Detekt status, docs audit, and known gaps. PR creation and merge remain separate gates; after CI and exact-head verification, request fresh merge approval instead of enabling auto-merge.

## Spec-to-plan traceability

| Spec item | Plan task | Evidence |
| --- | --- | --- |
| AC-01 disabled default and unchanged `@Scheduled` | Tasks 1, 5, 8, 9 | binder default, no registry/BPP, bypass test, task cardinality |
| AC-02 exact selector and overload rejection | Tasks 3, 4 | registry exact-match and ambiguity tests |
| AC-03 duplicate/missing/non-scheduled startup failure | Tasks 3, 4, 5, 6 | BPP validation and `ApplicationContextRunner.startupFailure` |
| AC-04 explicit annotation precedence | Tasks 3, 7, 8 | explicit observed-but-unregistered and aspect precedence tests |
| AC-05 policy-to-AOP metadata conversion | Tasks 1, 2, 7, 8 | binding, options/factory/failure-mode assertions |
| AC-06 invalid SpEL/duration/min-lease/strict footgun | Tasks 1, 2, 4, 5 | binder and shared validation tests |
| AC-07 contention skip | Task 7 | `LeaderRunResult.Skipped` returns null and body is not called |
| AC-08 sync/suspend/Mono/Flux/Flow release/cancellation | Tasks 7, 8 | branch and cancellation tests |
| AC-09 task cardinality and no duplicate registration | Task 9 | `ScheduledTaskHolder` count before/after policy |
| AC-10 single Observation compatibility | Task 9 | recording handler and context-close assertions |
| AC-10a mismatch fast path | Tasks 7, 8, 11 | zero factory/backend/recorder counters |
| AC-11 README EN/KO and metadata | Task 10 | locale pair, audit, metadata test |
| AC-12 tests, Detekt, diff, final review, exact-head PR | Task 12 | module test, AOT, Detekt, diff, review and PR evidence |
| Spec DoD: ABI/rollback/manual boundary | Tasks 2, 6, 10, 12 | no `LeaderProperties` change, disabled rollback docs, manual untouched, final diff |

## Step 3-R 계획 검토

Six independent lenses plus an integration/maintainability leader pass were applied to the exact plan after the spec approval. Findings are recorded here so implementation starts only from a reviewed, bounded plan.

| Lens | Review result | Disposition |
| --- | --- | --- |
| Performance | PASS; the mismatch path is a direct proceed, and matching metadata is cached once per target/method | Task 8 requires an instance-only cache; Task 11 verifies zero factory/backend/SpEL/recorder calls for bypass and bounded cache lifetime |
| Stability | PASS; BPP validation precedes scheduled-task finalization and failed contexts rely on Spring-owned cancellation | Tasks 4 and 9 verify ordering, one task per method, close behavior, and no registry-owned scheduler resources |
| Security | PASS; selectors are exact and validation reuses the existing SpEL/backend components | Task 4 constrains error messages to selector/property context and excludes addresses, credentials, resolved lock names, and dynamic reload |
| Operations | PASS; the feature is opt-in and rollback is a single property change | Task 10 documents `enabled=false`, failure modes, stream limits, and unsupported wildcards/reload; Task 12 records CI/verification evidence |
| API/ABI | PASS; `LeaderProperties` and `@LeaderScheduled` remain unchanged | Task 2 uses an additive public properties model; Task 6 keeps a five-argument aspect constructor for existing direct callers and introduces no dependency/module |
| User/documentation | PASS; EN/KO locale pair explains the exact selector and precedence with a complete YAML example | Task 10 updates both READMEs and metadata, then runs the Korean terminology audit |
| Integration/maintainability | PASS; the change stays inside `leader-spring-boot` and reuses existing scheduler/AOP/Observation paths | No `settings.gradle.kts`, BOM, CI, nightly, or versioned-manual change is required because no module or publishable surface is added; Task 11 removes duplicate validation and new abstractions |

Integrated result: `P0=0`, `P1=0`, `P2=0`, `P3=0`. No unresolved design or implementation-planning finding remains. The only intentional stop is the user approval gate above; source implementation has not started.

## Rollback and rerun points

- Property-only rollback: set `bluetape4k.leader.scheduling.enabled=false`; existing `@Scheduled` and explicit annotation paths remain active.
- Code rollback before PR: revert only the feature commit; no scheduler task database, backend schema, release tag, or external service is modified.
- Failed targeted test: rerun the owning task's exact `--tests` command after the smallest repair, then rerun the module test.
- Failed AOT or Detekt: keep the branch unmerged, repair auto-configuration/diagnostics, and repeat Tasks 6 and 12.
- Failed CI after PR: inspect the live check and exact head, apply a bounded fix, rerun affected tests/review, and do not merge an earlier SHA.
