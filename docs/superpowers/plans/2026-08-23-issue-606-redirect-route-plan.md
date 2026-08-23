# Issue #606 redirect route metadata 구현 계획

## 결과 목표와 종료 조건

Issue #606의 opt-in redirect-to-leader 정책을 `leader-spring-boot`에 추가한다.
기존 #537 passive route guard와 `LeaderRouteAuthority` 계약은 유지하고, resolver가
제공한 공개 URI만 공통 정책으로 검증한 뒤 MVC/WebFlux에 `307 Temporary Redirect`와
`Location`을 기록한다. resolver가 없거나, 상태·TTL·proxy 근거·URI가 안전하지 않으면
기존 rejection status와 빈 body로 fail-closed 한다.

구현 종료 조건은 다음을 모두 만족하는 경우다.

1. 승인된 spec의 acceptance/traceability 항목이 코드·테스트·문서에 연결된다.
2. `leader-spring-boot` targeted test, module build/AOT, detekt와 manual validation이
   fresh evidence로 통과한다.
3. public compatibility constructor/copy/serialization surface와 기존 #537 회귀가
   검증된다.
4. MVC/WebFlux parity, raw metadata trust, freshness, cancellation/interruption/Error,
   scheduler ownership, no-election-mutation이 검증된다.
5. EN/KO README·metadata·KDoc가 동기화되고, 현재 release pin의 manual은 새 API를
   혼입하지 않은 비변경 상태로 검증되며 writer audit/diff check가 통과한다.

구현하지 않는 항목은 #607 request-scoped lease lifecycle, built-in backend-to-URL
mapping, global route auto-registration, forwarded header 해석/DNS/network validation,
새 dependency/module/CI workflow/Nightly 범위다.

## 구현 전 불변 계약

- branch/worktree: `feat/epic-spring-r-01-redirect` / 현재 feature worktree
- base: `origin/develop` `4b7974e5f79a948e98c19f317eadfb452616c83f`
- public response: valid target에만 `307`, 검증된 URI 한 번만 `Location`, identity·backend
  오류·raw target 미노출
- default: `redirect.enabled=false`, 빈 host/proxy 목록, `lease-safety-window=PT0S`
- outer gate: `route-guard.enabled=false`면 redirect semantic validation/bean 생성 없음
- state: STATE는 `state(slot.lockName)` 정확히 1회, 같은 평가의 `LeaderState?`를 resolver에
  최대 1회 전달, Allowed/Unavailable에서는 resolver 미호출
- freshness: redirect eligibility에만 `leaseUntil > clock.now + safetyWindow`를 엄격 적용;
  기존 Allowed 판정은 변경하지 않음; CUSTOM은 `validUntil` 계약을 resolver가 증명
- trust: raw metadata는 application boundary가 filter/transformer 이전에 캡처; unknown은
  absolute 거부; forwarded=true는 canonical numeric transport peer allowlist exact match;
  library는 raw forwarded header나 transformed remote address를 해석하지 않음
- URI: relative는 `/` 시작·`//`/fragment 금지; absolute는 HTTPS·ASCII exact host·implicit
  default 443·userinfo/fragment/control/invalid port 거부; wildcard/suffix/DNS/zone 금지
- execution: callback은 immutable/thread-safe/in-memory/bounded non-blocking; ordinary
  `Exception`은 fail-closed, `CancellationException` 전달, `InterruptedException` flag 복구 후
  전달, fatal `Error` 전파; WebFlux evaluation scheduler는 caller-owned이며 dispose/timeout
  executor/cache를 만들지 않음
- observability: optional hook은 고정 reason enum과 `framework` tag만 허용하고 host/path/
  leader ID는 기록하지 않음

## 작업 순서

### 1. 설정 모델과 compatibility bridge

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderRouteGuardProperties.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderRouteRedirectProperties.kt` (new)
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderProperties.kt`
- `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`

작업:

1. `LeaderRouteRedirectProperties : Serializable`를 추가하고 `enabled`,
   `allowedHosts`, `trustedProxyAddresses`, `leaseSafetyWindow` 기본값을 정의한다.
2. `LeaderRouteGuardProperties.redirect`를 최신 생성자 기본값으로 연결한다.
3. 기존 공개 4-argument constructor, 4-argument synthetic default constructor
   `(4 args, mask, DefaultConstructorMarker)`, `copy`, `copy$default` JVM/Kotlin
   descriptor와 `Serializable` 동작을 별도 bridge로 유지한다. 기존 0.5.0 serialized fixture를
   새 클래스에서 읽을 때 누락된 `redirect` 필드는 기본 정책으로 복원하는
   `readObject`/serialization-proxy 전략을 명시하고 round-trip을 검증한다.
   `LeaderProperties` 10-argument compatibility constructor/copy surface도 유지한다.
4. `route-guard.enabled` outer gate와 `redirect.enabled` inner gate를 auto-configuration
   조건으로 분리한다. redirect disabled에서는 semantic host/IP/duration validation과
   redirect policy bean 생성을 하지 않는다. 단, `LeaderProperties` 전역 바인딩이 수행하는
   Spring Binder의 기본 타입 변환(예: malformed `Duration`)은 outer condition보다 먼저
   실패할 수 있으므로, 이를 semantic validation skip과 혼동하지 않도록 명시하고 테스트한다.
5. enabled startup validation을 구현한다.
   - host: exact ASCII, wildcard/suffix/CIDR/URI scheme/port/zone 금지, canonical lower-case
   - trusted peer: numeric IPv4/IPv6 only, DNS/CIDR/zone/scheme/port 금지, canonical form
   - duration: finite non-negative only
   - invalid value는 configuration exception으로 fail-fast
6. 설정 metadata에 nested property type/default/description을 추가한다.

TDD 증거:

- RED: 기존 binding/compatibility test에 redirect default 및 4-argument descriptor assertion을
  먼저 추가하고 실패를 확인한다.
- GREEN: 모델/validator/metadata를 구현한다.
- 대상 테스트: `LeaderPropertiesBindingTest`, `LeaderConfigurationMetadataTest`, 신규
  `LeaderRouteRedirectPropertiesTest`, `LeaderRouteGuardAutoConfigurationTest`.

### 2. framework-neutral public API와 공통 policy

대상 파일(구현 시 실제 package layout을 유지):

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteRedirectResolver.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteRedirectContext.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteRedirectRequestMetadata.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteRedirectPolicy.kt`
  (internal policy/validator/evaluation)
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteRedirectFailureReason.kt`
  (고정 reason enum 또는 동등한 internal contract)

작업:

1. `LeaderRouteRedirectResolver`, `LeaderRouteRedirectContext(slot, leaderState,
   evaluatedAt, leaseSafetyWindow)`, `LeaderRouteRedirectRequestMetadataProvider<T>`,
   `LeaderRouteRedirectRequestMetadata`를 한국어 public KDoc과 함께 추가한다. public
   data class인 context/metadata는 기존 route 기준 데이터 모델과 같은 `Serializable`,
   `serialVersionUID` 계약을 갖고 round-trip을 테스트한다.
2. resolver 결과는 `URI?`만 받는다. resolver가 public mapping registry에서 반환한 URI를
   common policy가 검증하며 library는 backend identity로 URI를 만들지 않는다.
3. policy는 설정을 startup에서 immutable normalized Set으로 받아 요청마다 재복사하지 않는다.
4. URI validator를 구현한다.
   - relative path/fragment/network-path reference
   - HTTPS exact host/default 443/ASCII/userinfo/fragment/raw authority/empty port
   - control character와 percent-encoded C0 control 방어
   - malformed/opaque/Unicode/invalid host/port/zone/backslash/allowlist mismatch
5. request metadata policy를 구현한다.
   - `forwardedHeadersPresent == null` 또는 필요한 peer null: absolute 거부
   - `false`: forwarded trust check 없이 relative 및 allowlisted HTTPS absolute 허용
   - `true`: canonical peer exact trusted allowlist match만 허용
   - raw header parsing/DNS/현재 request remote address 추론 금지
6. freshness와 fixed `Clock` 주입을 구현한다. STATE의 null/expired/equal lease는 redirect만
   거부하고 Allowed evaluation은 보존한다. CUSTOM은 resolver context의 `evaluatedAt`과
   `leaseSafetyWindow`를 사용해 `validUntil`을 증명하며 위반 시 null을 반환한다.
7. 고정 failure reason enum(`STALE_LEASE`, `UNAVAILABLE`, `NULL_TARGET`, `URI_REJECTED`,
   `UNTRUSTED_PROXY`, `METADATA_UNKNOWN`, `CALLBACK_FAILURE`)과 optional hook을 만든다.
   hook tag는 `reason`, `framework`만 허용하고 `framework` 값은 MVC/WebFlux 고정 enum으로
   제한한다. host/path/leader ID가 hook·로그 입력에 도달하지 않는 테스트를 둔다.
8. common policy의 callback 경계를 정의한다. ordinary Exception만 rejection으로 정규화하고
   cancellation/interruption/fatal Error는 각각 contract대로 처리한다.

TDD 증거:

- 신규 `LeaderRouteRedirectUriValidatorTest`: URI positive/negative matrix와 raw component,
  percent-encoded control, relative fragment, IPv4 leading-zero/IPv4-mapped IPv6,
  whitespace/trailing-dot host와 percent-encoded `@`/backslash.
- 신규 `LeaderRouteRedirectPolicyTest`: enabled/disabled, metadata, host/proxy, reason code,
  no-location, no-mutation, resolver call count, single captured clock/CUSTOM validUntil,
  concurrent reentrant resolver, fixed framework tag와 민감값 비전달.
- 신규 `LeaderRouteRedirectPropertiesTest`: strict parse, canonicalization, disabled semantic
  skip와 malformed typed-binding 경계.
- `LeaderRouteGuardPropertiesSerializationTest`: 기존 0.5.0 serialized fixture의
  `redirect` 기본 복원, 새 버전 round-trip, 4-argument constructor/synthetic default
  constructor/copy/copy$default descriptor.
- `LeaderRouteRedirectContextSerializationTest`: context/metadata의 serialVersionUID와
  Java serialization round-trip.

### 3. 단일 authority evaluation과 기존 runtime 호환

대상 파일:

- `LeaderRouteAuthorityRuntime.kt`
- `StateLeaderRouteAuthority.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/LeaderRouteEvaluation.kt`
  (new internal evaluation value)

작업:

1. `LeaderRouteEvaluation.kt`에 내부 `LeaderRouteEvaluation(decision, leaderState,
   evaluatedAt)`를 명시적으로 두고, policy/adapter가 이 단일 값만 소비하게 한다.
2. `StateLeaderRouteAuthority`가 동일 state 조회 결과로 decision과 state를 함께 반환하는
   internal evaluation 경로를 추가한다. public `evaluate(slot): LeaderRouteDecision`는 기존
   결과를 그대로 반환한다.
3. `LeaderRouteAuthorityRuntime`는 기존 `evaluate`를 유지하고 route adapter가 사용할
   evaluation API를 제공한다. CUSTOM의 leaderState는 null이다. 내부 evaluation 경로는
   factory가 주입한 `Clock`을 한 요청에서 한 번 캡처해 `evaluatedAt`, STATE freshness와
   resolver context가 같은 시각을 사용하도록 하며, 기존 2-argument factory constructor는
   `Clock.systemUTC()` 기본값으로 보존한다.
4. `CancellationException`, `InterruptedException`, ordinary Exception, fatal Error의 기존
   behavior를 regression test로 고정한다.

TDD 증거:

- `StateLeaderRouteAuthorityTest`: state 호출 1회, Allowed 호환성, NotLeader state 전달,
  null/expired redirect-only freshness.
- 신규/업데이트 `LeaderRouteAuthorityRuntimeTest`: custom state null, decision/evaluation parity.

### 4. MVC adapter

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/mvc/LeaderMvcRouteGuardFactory.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/mvc/LeaderMvcRouteGuardTest.kt`
- 신규 MVC redirect policy/metadata tests

작업:

1. 기존 `interceptor(slot)`를 보존한다.
2. resolver overload와 `LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>`
   overload를 추가한다.
3. `NotLeader`에서만 evaluation state를 한 번 읽고 resolver/policy를 한 번 적용한다.
4. valid URI일 때만 status 307과 `Location`을 한 번 기록하고 `false`를 반환한다.
5. invalid/null/stale/untrusted/unavailable/ordinary exception은 기존 rejection status와
   빈 body를 유지한다. Allowed는 handler를 한 번 실행한다.
6. provider가 raw boundary attribute를 읽고 transformed request에서 raw evidence를 재구성하지
   않도록 한다.

TDD 증거:

- valid relative/absolute redirect, exact path, no handler, one Location
- all negative URI/metadata/freshness cases, no raw identity/body leakage
- resolver-only overload + relative URI is 307; resolver-only + absolute URI is rejection with
  no `Location`; both metadata fields null is relative-only; forwarded=true with null/untrusted
  peer rejects; redirect disabled never calls resolver/provider
- resolver call count and single state read
- cancellation/interruption/fatal Error and configured rejection parity

### 5. WebFlux adapter와 scheduler/cancellation 경계

대상 파일:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/webflux/LeaderWebFluxRouteGuardFactory.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/route/webflux/LeaderWebFluxRouteGuardTest.kt`
- 신규 WebFlux scheduler/metadata tests

작업:

1. 기존 `filter(slot)`를 보존하고 resolver/provider overload를 추가한다.
2. authority evaluation, metadata capture, resolver, URI policy를 동일 injected evaluation
   scheduler 경계에 둔다. event-loop에서 blocking state lookup을 하지 않는다.
3. queued cancellation은 callback/handler를 시작하지 않게 하고, active synchronous callback은
   강제 interrupt하지 않으며 downstream을 구독하지 않게 한다.
4. Reactor error boundary에서 ordinary Exception은 rejection, CancellationException은 error,
   InterruptedException은 flag 복구 후 error, fatal Error는 재전파한다.
5. caller-owned scheduler를 dispose하지 않고 timeout executor/timer/cache를 만들지 않는다.
6. valid target은 307/Location/chain 미호출, Allowed는 chain 한 번, 나머지는 setComplete와
   configured status를 유지한다.

TDD 증거:

- `LeaderWebFluxRouteGuardTest`에 MVC와 동일한 positive/negative parity matrix 추가
- WebFlux example/test는 `PathPatternParser` 또는 동등한 route-scoped `/internal/orders/**`
  pattern을 사용해 인접·비대상 경로가 chain으로 통과하는지 고정한다.
- `VirtualTimeScheduler` 또는 injected test scheduler로 queued cancellation/ownership 확인
- scheduler thread marker로 event-loop 탈출 확인
- resolver/validator exception, cancellation, interruption, Error 경계 확인
- concurrent reentrant resolver 격리와 한 evaluation의 단일 `Clock.instant()` 캡처 확인
- active synchronous resolver callback을 latch로 완료시킨 뒤 취소해도 downstream chain이
  구독되지 않는지 결정적으로 확인

### 6. auto-configuration과 startup wiring

대상 파일:

- `LeaderRouteGuardAutoConfiguration.kt`
- `LeaderRouteGuardAutoConfigurationTest.kt`
- `LeaderConfigurationMetadataTest.kt`

작업:

1. `route-guard.enabled` outer condition을 유지한다.
2. redirect enabled일 때만 immutable policy/validator를 wiring하고 invalid configuration은
   startup exception으로 표면화한다.
3. resolver는 global bean으로 자동 등록하지 않는다. factory overload에서 route owner가 직접
   전달한다.
4. MVC/WebFlux conditional class guards와 factory registration order를 유지한다.
5. enabled/disabled 세 조합, absent property, malformed property, backward-compatible factory
   construction을 검증한다. redirect-enabled AOT context를 별도 fixture로 실행해 조건부
   policy wiring과 hints를 검증하고, outer-disabled malformed typed-binding 경계도 고정한다.

### 7. 문서·metadata·manual

대상 파일:

- `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`
- `docs/manual/manifest.yaml` 및 현재 pinned manual 파일의 비변경 검증
- KDoc가 있는 신규/수정 Kotlin public API

작업:

1. opt-in YAML, exact host/proxy rules, 307, relative-only fallback, raw metadata capture order,
   CUSTOM freshness, route-scoped MVC/WebFlux migration example을 `leader-spring-boot/README.md`
   와 `README.ko.md`에 같은 구조로 반영한다. 현재 `docs/manual/**`는 manifest의
   `releaseRef=0.5.0`, `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`에
   고정된 release 문서이므로 새 redirect API claim을 추가하지 않고 비변경 상태를
   검증한다. 1.0.0 release pin 확보 후 versioned manual 반영을 별도 작업으로 추적한다.
2. `ForwardedHeaderFilter`/`ForwardedHeaderTransformer` 뒤에서 raw evidence를 복원하지 않는
   운영 경계를 문서화한다. MVC는 transformer/filter보다 높은 우선순위의 application
   `Filter`가 raw transport peer와 forwarded-header presence를 먼저 immutable metadata로
   기록하고 interceptor는 producer 경계로 사용하지 않는다. WebFlux는 transformer 적용
   이전의 server/`HttpHandler` decorator 또는 외부 trusted boundary가 metadata를 만들며,
   일반 `WebFilter` ordering만으로 pre-transform 원본을 보장하지 않는다. 해당 경계를
   구성할 수 없으면 resolver-only relative URI만 사용하도록 명시한다.
3. library identity/backend address를 Location/body/log에 넣지 않는 금지와 rollback
   (`redirect.enabled=false` 후 restart/redeploy)를 문서화한다.
4. README는 1.0.0 release manual 반영 전까지 현재 pinned manual의 source-of-truth
   경계를 링크로 설명하고 상세 계약을 중복하지 않는다.
5. `releaseRef=0.5.0`, `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`에 맞춘
   release manual 검증을 실행해 pinned 문서가 변경되지 않았고 새 API claim이 섞이지
   않았음을 확인한다. 새 API manual 반영은 release pin 확보 후의 후속 작업이다.

### 8. 검증·정리·리스크 점검

작업 순서:

1. `git diff --check` 및 untracked artifact check
2. Korean terminology audit
3. targeted tests:
   `./gradlew --no-configuration-cache :bluetape4k-leader-spring-boot:test`
4. module build/AOT:
   `./gradlew --no-configuration-cache :bluetape4k-leader-spring-boot:build` 및
   redirect-enabled fixture를 포함한 `./gradlew --no-configuration-cache
   :bluetape4k-leader-spring-boot:aotTest`. AOT 로그에서 Testcontainers/context
   skip-warning을 허용하지 않고, redirect-enabled fixture의 실제 실행 test count와
   policy bean/context assertion을 evidence에 기록한다.
5. static analysis: `./gradlew --no-configuration-cache detekt`
6. manual inventory/release checks:
   - `./gradlew exportManualModuleInventory`
   - `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml`
   - `ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977`
   - `ruby scripts/manual/export_manifest.rb --check`
   - `ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'`
   - `python3 scripts/ci/validate_manual_contract.py`
7. 필요 시 Testcontainers는 `colima status`, `docker context show`, `docker info`의
   fresh output으로 managed socket을 확인한 뒤 sequential으로 실행하고, 실제 test 결과와
   skip/실패 여부를 evidence에 기록한다. skipped/failed container test를 성공으로 간주하지
   않는다.
8. 변경된 파일, simplification, residual risk를 `verification-before-completion` evidence에
   기록한다.

리스크와 대응:

- URI parser 차이: raw component와 Java `URI`를 함께 검사하고 negative matrix를 유지한다.
- public property ABI: explicit bridge/descriptor/serialization test를 구현 전에 고정한다.
- WebFlux cancellation race: injected scheduler와 deterministic cancellation test를 사용한다.
- proxy metadata 오염: application boundary attribute only, unknown absolute rejection을 유지한다.
- stale leader mapping: fixed clock와 no-cache next-request recovery test를 유지한다.
- 문서 release drift: manifest pin과 exact release validation command를 DoD에 포함한다.

## 파일별 acceptance mapping

| acceptance 영역 | 구현 파일 | 검증 |
|---|---|---|
| opt-in/default/outer gate | properties, auto-configuration | binding + three configuration matrix |
| resolver/context/provider | new route API files | KDoc/API compile + resolver call tests |
| URI/trust policy | common policy/validator | positive/negative URI and metadata matrix |
| single state/freshness | runtime/state authority | one state read, fixed clock, CUSTOM validUntil |
| MVC/WebFlux parity | both factories | status/Location/handler/chain parity |
| errors/cancellation | common policy + WebFlux | Exception/Cancellation/Interrupted/Error tests |
| scheduler/lifecycle | WebFlux factory | injected scheduler non-disposal/no timer/cache |
| compatibility | property/model bridges | constructor/copy/serialization descriptor tests |
| docs/manual/metadata | README/metadata/KDoc, pinned manual guard | audit, diff check, manual release validators, release-pin evidence |

## Commit and delivery boundaries

- 구현 단계는 설정/API·policy·runtime·MVC·WebFlux·docs 순서로 작은 commit으로 나눈다.
- 각 commit은 Lore protocol을 따른다: intent, Constraint, Rejected, Confidence,
  Scope-risk, Directive, Tested, Not-tested.
- PR 생성은 실제 target/base/head 권한과 최종 clean diff/CI 증거를 확인한 뒤 수행한다.
- merge는 별도 fresh explicit approval 없이는 하지 않는다. user가 1인 개발자라고 했더라도
  exact head/CI/metadata/DoD 검증은 생략하지 않는다.
- rollback은 feature branch에서 `redirect.enabled=false` 기본값을 유지하고, release 전에
  문서/metadata와 함께 되돌릴 수 있도록 각 commit을 분리한다.

## Plan Writer DoD (SPW-01..SPW-05)

| gate | status | evidence |
|---|---|---|
| SPW-01 scope, audience, source ledger, identifiers, unknowns | PASS | 승인된 Issue #606와 #537/#607 경계, current source/test 경로, base SHA, branch/worktree, 명시적 제외 범위를 위에 기록했다. |
| SPW-02 executable implementation plan | PASS | Tasks 1-8에 정확한 파일, RED→GREEN 테스트, 의존 순서, 명령, AOT/manual/rollback 증거와 stop condition을 고정했다. |
| SPW-03 Korean reader-facing plan quality | PASS | 한국어 독자용 prose와 보존해야 할 code/API/URL/status token을 구분했고 `audit-korean-terms.mjs`가 `findings=0`을 반환했다. |
| SPW-04 spec-to-plan traceability | PASS | spec acceptance와 DoD를 파일별 acceptance mapping, 작업별 TDD 증거, 호환성/outer-gate/문서·AOT 검증에 매핑했다. |
| SPW-05 final Markdown readback | PASS | 최신 수정 후 전체 plan read-back, placeholder/경로 점검, `git diff --check`와 untracked no-index check를 통과했다. |

## Plan DoD

- [x] 모든 spec acceptance가 위 작업과 파일·테스트에 매핑됨
- [x] 테스트가 RED→GREEN 순서로 작성됨
- [x] public compatibility/serialization 검증이 포함됨
- [x] MVC/WebFlux, state/freshness, proxy/URI, error/cancellation, scheduler 경계가 포함됨
- [x] EN/KO README/KDoc/metadata가 포함되고 pinned release manual은 비변경 검증됨
- [x] exact module/manual/detekt/AOT 명령과 결과 저장 위치가 포함됨
- [x] rollback·No new dependency/module/workflow 범위가 명시됨
- [x] plan review 최종 `P0=0, P1=0`

구현 후 fresh evidence는 대상 테스트 92건, 모듈 build 540건, AOT 7건,
Detekt와 manual contract 통과를 기록한다. `checkBinaryCompatibility`는
`ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0`으로 재실행했으며 Issue #606의
runtime/config descriptor 제거는 사라졌지만, 기존 synthetic accessor와 observability/AOP
drift 5건이 저장소 baseline으로 남아 전체 task는 실패한다. 이 baseline은 delivery
DoD의 잔여 리스크로 PR에 명시한다.
