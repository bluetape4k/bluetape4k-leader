# Issue #603 scheduled task property 기반 leader policy 설계

## 문서 상태

- Issue: [#603](https://github.com/bluetape4k/bluetape4k-leader/issues/603)
- Epic: [#700](https://github.com/bluetape4k/bluetape4k-leader/issues/700)
- Train: `SPRING-S-02`
- 저장소: `bluetape4k-leader`
- branch: `feat/epic-spring-s-02-scheduled-policy`
- base: `origin/develop` (`f5e1062c815b2c743ad5ecabd5105467224203cc`)
- 작업 범위 승인: 2026-08-23, 사용자 승인
- 설계 승인: 2026-08-23, 사용자 승인

## 문제와 목표

현재 Spring scheduled task가 leader election을 사용하려면 각 method에
`@LeaderScheduled` 또는 `@LeaderElection`을 직접 붙여야 한다. 기존
`@Scheduled` method를 YAML만으로 선택해 leader policy를 적용할 수 없으므로,
운영 환경에서 관리하는 scheduled task를 소스 annotation 변경 없이 전환하기
어렵다.

이번 변경의 목표는 다음과 같다.

1. 기존 `@Scheduled` method를 stable selector로 선택한다.
2. lock name, wait/lease/min-lease, backend bean, auto-extension,
   stream-bounded, failure-mode를 property로 설정한다.
3. 명시적 leader annotation과 property policy의 precedence를 고정한다.
4. 잘못된 selector나 policy를 startup에서 fail-fast한다.
5. Spring scheduling, task observation, 기존 `LeaderElectionAspect` 경로를
   재사용한다.

## 범위와 제외 범위

### 포함

- `leader-spring-boot`의 property model, policy resolver/registry,
  startup validation, AOP metadata fallback
- `ApplicationContextRunner` binding/validation/precedence 테스트
- woven scheduled method의 contention, lifecycle, task cardinality,
  observation 호환성 테스트
- `leader-spring-boot` README 영어/한국어 문서와 Spring configuration metadata

### 제외

- 별도 scheduling engine, dynamic rescheduling subsystem, 별도 scheduling task registry
- Spring `ScheduledAnnotationBeanPostProcessor` 교체 또는 synthetic
  `@Scheduled` task 등록
- `@LeaderScheduled`의 기존 annotation contract 변경
- Epic #700의 route metadata/요청별 lease인 #606, #607
- 새 module, dependency, backend, release/tag/publication

## 현재 구현과 근거

다음 경로를 현재 `develop`에서 확인했다.

| 근거 | 확인 내용 |
| --- | --- |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduled.kt` | `@Scheduled`와 `@LeaderElection`을 합성하고 기존 alias를 제공한다. |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt` | `@LeaderElection`/`@LeaderScheduled` pointcut, metadata cache, sync/suspend/Mono/Flux/Flow 분기를 이미 제공한다. |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` | leader annotation method의 final/private, SpEL, duration, stream footgun을 startup에서 검증한다. |
| `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderProperties.kt` | 기존 public constructor/copy ABI를 유지해야 하는 설정 모델이다. 이번 변경에서 직접 확장하지 않는다. |
| Spring Framework 7.0.8 `ScheduledAnnotationBeanPostProcessor` source | `@Scheduled`를 탐색하고 `ScheduledTaskRegistrar`에 task와 Observation 경로를 등록한다. `processScheduled*`/`processScheduledTask`는 private이므로 대체·래핑 시 lifecycle 중복 위험이 있다. |
| `./gradlew :bluetape4k-leader-spring-boot:test` | 기준선 `BUILD SUCCESSFUL`, 38 actionable tasks. |

Spring scheduler는 method의 `@Scheduled`를 계속 소유해야 한다. leader policy는
실행 시 기존 aspect metadata에만 추가하고, task 등록/trigger/취소/Observation은
Spring 원래 경로에 맡긴다.

## 선택한 설계

### 1. 별도 property model

기존 `LeaderProperties`의 public constructor/copy descriptor를 변경하지 않기
위해 별도 `@ConfigurationProperties` model을 추가한다.

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

`bluetape4k.leader.scheduling.enabled`의 기본값은 `false`다. policy 항목의
속성은 다음 계약을 따른다.

| 속성 | 기본값/필수 | 계약 |
| --- | --- | --- |
| `selector` | 필수 | `beanName#methodName` exact match. wildcard, 정규식, 공백 selector는 지원하지 않는다. 같은 bean에서 같은 이름의 overloaded scheduled method가 여러 개면 모호한 selector로 startup 실패한다. |
| `name` | 필수 | 기존 SpEL/placeholder lock-name 표현식과 동일하게 해석한다. |
| `wait-time` | 기존 `LeaderAopProperties.defaultWaitTime` | 음수가 아니어야 한다. |
| `lease-time` | 기존 `LeaderAopProperties.defaultLeaseTime` | 양수 duration이며 `min-lease-time`보다 크거나 같아야 한다. |
| `min-lease-time` | `PT0S` | 음수가 아니며 lease보다 클 수 없다. |
| `bean` | 비어 있음 | 기존 `LeaderBeanSelector`의 explicit bean/primary/단일 후보 규칙을 사용한다. |
| `auto-extend` | `false` | 기존 `LeaderElection`의 stream lease extension 규칙을 사용한다. |
| `stream-bounded` | `false` | reactive/Flow task가 lease 안에서 끝난다는 명시적 contract다. |
| `failure-mode` | `INHERIT` | 기존 `LeaderAopProperties.failureMode`를 상속하거나 `SKIP`, `RETHROW`, `FAIL_OPEN_RUN`을 사용한다. |

`enabled=true`인데 policy가 비어 있으면 기능을 켜고 대상도 지정하지 않은
구성으로 간주해 startup에서 실패한다. `enabled=false`이면 policy를 읽거나
scheduled method를 leader path로 보내지 않으며 기존 `@Scheduled` 동작을
그대로 유지한다.

### 2. Stable selector와 registry

`LeaderScheduledPolicyRegistry`와 `LeaderScheduledPolicyBeanPostProcessor`를
추가한다. 이 registry는 policy metadata만 보관하며 scheduling task, trigger,
subscription, executor를 소유하지 않는다.

- BPP는 user bean의 target class에서 `@Scheduled` merged annotation이 있는
  method를 찾는다.
- Spring이 전달하는 canonical `beanName`과 method name으로
  `beanName#methodName` key를 만든다.
- 운영 설정의 selector에는 명시적으로 지정한 Spring bean name을 사용한다.
  컴포넌트 스캔으로 생성된 이름이나 다른 자동 생성 이름은 리팩터링 때 바뀔 수
  있으므로 stable selector 계약으로 간주하지 않는다.
- 동일한 `beanName#methodName`에 여러 `@Scheduled` method가 매칭되면
  signature selector로 임의 선택하지 않고 startup에서 실패한다.
- policy selector와 exact match한 method만 registry에 등록한다.
- 명시적 `@LeaderElection`/`@LeaderScheduled`가 있는 method는 property
  registry에 등록하지 않고 기존 annotation path를 그대로 사용한다.
- 같은 selector를 두 policy가 선언하면 어느 policy를 사용할지 추측하지 않고
  startup에서 실패한다.
- selector가 실제 bean/method에 매칭되지 않거나 `@Scheduled`가 아닌 method를
  가리키면 startup에서 실패한다.
- BPP가 관찰한 selector와 configured selector를 `afterSingletonsInstantiated`
  단계에서 비교해, 어떤 bean도 관찰하지 못한 selector도 startup에서
  식별한다.
- registry는 `Method`와 target fallback을 함께 보관해 Spring proxy와 AspectJ
  woven method lookup 차이를 흡수한다.
- policy가 비활성화되면 BPP는 no-op이며 registry는 빈 상태다.

`LeaderScheduledPolicyAutoConfiguration`은 `LeaderAopFactoryAutoConfiguration`
이후, `LeaderAopAutoConfiguration` 이전에 등록한다. scheduling이 비활성화된
경우 registry/BPP bean을 만들지 않으며, aspect는 optional registry를 받아
기존 annotation-only 생성 경로와 constructor 호환성을 유지한다.

registry는 scheduling task를 만들지 않는다. `ScheduledTaskRegistrar`와
`ScheduledAnnotationBeanPostProcessor`는 기존 auto-configuration 그대로 한
번만 task를 등록한다.

### 3. Annotation/property precedence

`LeaderElectionAspect`의 metadata resolution 순서를 다음처럼 고정한다.

1. method의 명시적 `@LeaderElection` 또는 합성된 `@LeaderScheduled`
2. registry가 제공하는 property policy
3. 두 metadata가 모두 없으면 `pjp.proceed()`만 수행

명시적 annotation이 있는 method에 같은 selector policy가 있어도 annotation이
우선하며 property 값을 병합하지 않는다. 하나의 method에 두 leader advice를
겹쳐 적용하거나 property를 두 번 실행하지 않는다.

pointcut에는 plain `@Scheduled`를 추가하되, `aroundLeader`는 explicit
annotation과 registry policy가 모두 없는 method를 즉시 `proceed`한다. 따라서
feature가 기본 비활성인 애플리케이션의 기존 scheduled method는 lock을
획득하지 않는다.

policy lookup은 startup에서 만든 immutable registry를 method 단위로 조회한다.
scheduled tick마다 property binding, selector parsing, SpEL parsing을 반복하지
않으며, 미매칭 method의 fast path는 leader factory를 만들거나 backend를
호출하지 않는다.

### 4. 기존 실행 경로 재사용

registry policy를 기존 `AdviceMetadata`로 변환한다.

- sync 반환은 기존 `LeaderElector.runIfLeaderResult` 경로를 사용한다.
- Kotlin suspend, `Mono`, `Flux`, `Flow`는 기존 branch와 cancellation/release
  처리를 그대로 사용한다.
- contention은 기존 `SKIP` semantics에 따라 scheduled body를 호출하지 않고
  예외를 던지지 않는다.
- backend 오류는 policy의 `failure-mode`를 거쳐 기존 metrics/observation
  callbacks와 함께 처리한다.
- `Flux`/`Flow`는 `auto-extend=true` 또는 `stream-bounded=true`를 요구하는
  기존 validation을 동일하게 적용한다.

### 5. Startup validation

policy BPP와 기존 validator가 다음을 검증한다.

- `enabled=true`일 때 policy가 하나 이상 존재한다.
- selector가 non-blank이고 중복되지 않는다.
- selector 대상 bean과 method가 존재하고 해당 method가 `@Scheduled`다.
- `name`의 SpEL/placeholder가 pre-parse된다.
- duration이 유효하고 `min-lease-time <= lease-time`이다.
- explicit backend bean이 존재하거나 기존 bean selection 규칙으로 단일
  후보를 결정할 수 있다.
- final/private method 및 stream return의 strict 정책을 기존 validator와
  일치시킨다.
- scheduling trigger의 exactly-one, cron/fixed-rate/fixed-delay, method
  signature 검증은 Spring scheduler가 계속 담당한다.

검증 오류는 대상 `beanName#methodName`과 실패한 property를 포함한 startup
오류로 보고한다. 실행 중 policy 변경이나 dynamic reload는 지원하지 않는다.

SpEL은 기존 `SpelExpressionEvaluator`와 동일한 method-invocation 제한을
사용한다. selector는 exact match만 허용하고 wildcard/정규식이나 외부 입력을
실행하지 않는다. 오류 로그에는 selector와 property 이름만 포함하며, runtime에
해석된 lock name, backend 주소, credential-like 값을 새로 출력하지 않는다.

## 대안과 거부 근거

### `ScheduledAnnotationBeanPostProcessor` 교체/래핑

Spring의 private task parsing과 registration lifecycle을 복제해야 한다.
task 취소, reactive subscription, observation registry, context close 순서를
중복 구현하게 되므로 기존 framework contract를 깨뜨릴 위험이 크다.

### 별도 dynamic scheduler/registry

새 scheduler가 trigger와 task 수명을 소유하면 기존 Spring scheduled-task
observation과 중복 등록된다. 또한 Issue #603의 명시적 제외 범위인 별도
scheduling engine에 해당한다.

### `LeaderProperties`에 scheduling field 직접 추가

기존 public constructor/copy ABI와 configuration model을 불필요하게 변경한다.
별도 `LeaderScheduledProperties`를 사용해 binary/source compatibility 위험을
좁힌다.

## 성능·안정성 불변식

- disabled 또는 미매칭 scheduled method의 advice 경로는 즉시 `proceed`하고
  leader factory/backend/metrics acquisition을 시작하지 않는다.
- policy registry는 context refresh 중 한 번만 구성하며, 실행 tick마다
  reflection scan, property bind, selector parse를 수행하지 않는다.
- scheduler task, reactive subscription, Observation callback은 기존 Spring
  owner가 한 번만 만들고 닫는다.
- registry는 background thread, retry loop, dynamic refresh, mutable task
  collection을 만들지 않는다.
- selector validation을 scheduled trigger 실행보다 앞에서 끝내고, context
  startup이 실패하면 Spring lifecycle의 close 경로가 등록된 scheduler task를
  취소한다.

## 실패 모드와 복구

| 상황 | 예상 동작 | 복구/rollback |
| --- | --- | --- |
| `enabled=false` 또는 policy 미매칭 | 기존 `@Scheduled` body가 leader lock 없이 실행된다. | property를 그대로 두고 점진적으로 selector를 추가한다. |
| selector 중복/미존재/모호성 | context refresh가 fail-fast한다. 실패한 context는 scheduler 등록을 닫고 trigger 실행을 허용하지 않는다. | selector를 정정하거나 `enabled=false`로 되돌린다. |
| 잘못된 SpEL/duration/min-lease | startup validation이 실패한다. | property 값을 수정하고 context를 재시작한다. |
| 명시적 annotation과 policy 충돌 | annotation이 우선하고 property는 적용하지 않는다. | policy를 제거하거나 annotation을 기준으로 문서화한다. |
| lock contention | scheduled body를 skip하며 예외를 던지지 않는다. | 기존 leader metrics/observation으로 skip을 확인한다. |
| backend 오류 | `failure-mode`에 따라 skip/rethrow/fail-open한다. | 기존 backend/`failure-mode` 운영 절차를 사용한다. |
| stream이 lease보다 오래 실행 | `auto-extend`/`stream-bounded`가 없으면 startup validation이 차단한다. | auto-extension을 켜거나 bounded stream contract를 명시한다. |
| 구현 롤백 | scheduler 등록 경로를 바꾸지 않았으므로 기존 annotation path가 남는다. | `enabled=false`를 기본/운영 설정으로 적용하고 property auto-config/BPP와 pointcut 변경을 되돌린다. |

## 호환성과 마이그레이션

- 기본값은 비활성화이므로 기존 `@Scheduled`, `@LeaderScheduled`,
  `@LeaderElection` 애플리케이션의 실행 의미가 바뀌지 않는다.
- `LeaderProperties`의 기존 constructor/copy descriptor는 변경하지 않는다.
- 기존 `LeaderElectionAspect` 직접 생성 경로가 있는 테스트·사용자를 위해
  policy registry 의존성은 마지막 optional parameter 또는 호환 constructor로
  추가한다.
- 새 property model은 additive API이며 새 dependency나 module을 추가하지
  않는다.
- 기존 `@LeaderScheduled` 사용자는 migration 없이 계속 사용할 수 있다.
- YAML-only 전환이 필요한 task만 selector와 leader policy를 추가한다.
- versioned release manual은 현재 `releaseRef`가 0.4.0으로 고정되어 있으므로
  이번 0.5.0 예정 API를 pinned manual에 추가하지 않는다. 모듈 README와
  unreleased 문서만 갱신한다.

## 수용 기준

- **AC-01**: `enabled` 기본값이 `false`이고 disabled context에서 기존
  `@Scheduled` method가 그대로 실행된다.
- **AC-02**: `selector=beanName#methodName` exact match가 대상 method 하나만
  선택하고, overloaded method로 둘 이상 매칭되면 startup에서 실패한다.
- **AC-03**: duplicate, missing, non-`@Scheduled` selector가 startup에서
  식별 가능한 오류로 실패한다.
- **AC-04**: explicit `@LeaderElection`/`@LeaderScheduled`가 property보다
  우선한다.
- **AC-05**: name, duration, bean, auto-extension, stream-bounded,
  failure-mode가 기존 AOP metadata와 동일한 값으로 변환된다.
- **AC-06**: invalid SpEL, negative duration, `min-lease-time > lease-time`,
  strict method footgun이 startup에서 차단된다.
- **AC-07**: leader contention 시 scheduled body가 호출되지 않고 예외가
  발생하지 않는다.
- **AC-08**: sync/suspend/Mono/Flux/Flow의 기존 release/cancellation 경로가
  유지된다.
- **AC-09**: Spring `ScheduledTaskHolder`의 task cardinality가 property
  적용 전후 동일하고 duplicate registration이 없다.
- **AC-10**: scheduled-task Observation이 한 번만 기록되고 기존 registry와
  호환된다.
- **AC-10a**: disabled/mismatched method는 leader factory/backend를 호출하지
  않고 기존 body만 실행한다.
- **AC-11**: `leader-spring-boot` README 영어/한국어와 configuration metadata가
  실제 property contract와 일치한다.
- **AC-12**: targeted tests, module test, Detekt, diff check, final review가
  통과하고 PR은 exact head로 CI에 제출된다.

## 명세 DoD

- property model과 selector contract가 고정되었다.
- annotation/property precedence와 disabled default가 고정되었다.
- Spring scheduler/Observation ownership과 duplicate 방지 불변식이 고정되었다.
- startup validation, runtime failure mode, stream/cancellation semantics가
  기존 AOP contract와 연결되었다.
- compatibility와 rollback 경계가 명시되었다.
- 수용 기준이 구현·테스트·문서 검증 항목으로 추적 가능하다.

## Step 2-R 설계 검토

검토 범위는 이 문서의 현재 내용과 위 표의 근거, 승인된 설계 A, 그리고
저장소의 Spring AOP/scheduling 구현이다. 각 관점은 P0/P1 차단 여부와 문서
경계를 독립적으로 확인한 뒤 main-session integration으로 중복·모순·증거
누락을 다시 대조했다.

| 관점 | 확인 근거 | 결과 | 조치 |
| --- | --- | --- | --- |
| 성능 | immutable registry, disabled/mismatch fast path, tick당 reflection/property/SpEL 금지 | P0/P1 없음 | selector exact match와 no-backend fast path를 AC-02/AC-10a로 유지 |
| 안정성 | Spring task/Observation ownership, startup validation, cancellation/release, context close | P0/P1 없음 | overloaded selector와 failed-context task 취소 계약을 명시 |
| 보안 | exact selector, 기존 SpEL evaluator 제한, secret-like log 금지, 기본 비활성 | P0/P1 없음 | 추가 변경 없음 |
| 운영 | fail-fast, `enabled=false` rollback, 기존 metrics/Observation 재사용, release manual 경계 | P0/P1 없음 | 추가 변경 없음 |
| 개발자/API | 별도 properties model, `LeaderProperties` ABI 보존, optional aspect registry, dependency 증가 없음 | P0/P1 없음 | 추가 변경 없음 |
| 사용자/caller | YAML 예시, annotation 우선순위, 명시 bean-name 권고, wildcard/dynamic reload 제외 | P0/P1 없음 | 추가 변경 없음 |
| 통합 | 범위/대안/실패 모드/AC/DoD가 선택 설계와 일치하고, scheduler task registry와 policy metadata registry를 구분 | P0/P1 없음 | P2/P3 없음; 명세 문구를 최신화 |

통합 결과는 `P0=0`, `P1=0`, `P2=0`, `P3=0`이다. 다음 단계의 구현 계획은
overload rejection, context-close cancellation, task cardinality/Observation
검증을 각각 테스트 항목으로 추적해야 한다.
