# Issue #606 리더 리디렉션 route metadata 설계

## 문제와 결과

Issue #537의 passive route guard는 현재 프로세스가 리더가 아니면 빈 응답과
안전한 상태 코드만 반환한다. Issue #606은 이 fail-closed 동작을 기본값으로
유지하면서, 애플리케이션이 공개 리더 위치를 알고 있을 때만 선택적으로
`Location`을 반환하는 redirect-to-leader 정책을 추가한다.

최종 결과는 다음과 같다.

- route guard는 계속 요청 경로에서 lease를 획득·연장·해제하지 않는다.
- 리디렉션 대상은 애플리케이션이 제공하는 resolver가 결정한다.
- 라이브러리는 resolver 결과를 공통 검증한 뒤에만 MVC와 WebFlux에 같은
  `307 Temporary Redirect`와 `Location`을 기록한다.
- resolver가 없거나, 상태 기준 데이터가 stale/unavailable이거나, URI·proxy 경계가
  안전하지 않으면 기존 rejection status와 빈 body로 돌아간다.
- `Forwarded`와 `X-Forwarded-*` 헤더는 라이브러리가 origin을 계산하는 입력으로
  사용하지 않는다. raw header 존재 여부와 transport peer가 애플리케이션 경계에서
  캡처되어 trusted-proxy allowlist를 통과한 경우에만 absolute redirect 후보를
  허용하며, raw 근거가 없으면 상대 경로만 허용한다. 실제 forwarded-header
  정규화는 애플리케이션과 경계 프록시의 책임이다.

## 범위와 제외

### 목표

- `LeaderState` 기준 데이터와 애플리케이션 소유 공개 노드 매핑 사이의 명시적인
  resolver 계약을 제공한다.
- MVC `HandlerInterceptor`와 WebFlux `WebFilter`가 같은 redirect 검증기를
  사용하게 한다.
- open redirect, header injection, userinfo, fragment, malformed host와
  untrusted forwarded-header 입력을 차단한다.
- raw backend `leaderId`, `nodeId`, internal address, backend 예외를 기본
  응답이나 로그에 넣지 않는다.
- stale 또는 unavailable 위치를 선거 상태 변경 없이 거부한다.

### 제외

- Issue #607의 요청별 lease 획득·연장·해제와 request lifecycle ownership.
- backend의 `nodeId` 또는 `auditLeaderId`를 URL로 직접 변환하는 built-in
  resolver.
- 모든 controller/route를 전역으로 감싸는 자동 등록.
- `Host`, `Forwarded`, `X-Forwarded-*` 값을 신뢰해서 public origin을 추론하는
  기능. 이 값이 필요하면 애플리케이션이 trusted proxy 계층에서 먼저 정규화해야
  한다.
- DNS 조회, private-network 탐지, 네트워크 연결을 통한 URL 검증.

## 현재 근거

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/route/`
  에 이미 `LeaderRouteAuthority`, `LeaderRouteDecision`, state/custom
  authority 선택, MVC/WebFlux factory가 있다.
- #537의 현재 계약은 `Allowed`가 아니면 handler를 실행하지 않고, 기본 응답에
  `Location`과 leader identity를 넣지 않는 것이다.
- `LeaderState.leader`는 `auditLeaderId`, `nodeId`, `leaseUntil`을 담지만
  backend별 public endpoint를 알지 못한다. 따라서 library가 이 필드를 URL로
  해석하면 내부 topology를 노출하게 된다.
- `LeaderSlot`은 election과 route guard가 공유해야 하는 `(lockName, leaderId)`
  값이다.
- Spring 문서는 forwarded header를 신뢰 프록시가 외부 입력에서 제거해야 하며,
  `ForwardedHeaderFilter`/`ForwardedHeaderTransformer` 사용 여부를 애플리케이션이
  결정해야 한다고 설명한다.
  - https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html
  - https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html
- 현재 기준 branch는 `origin/develop`의
  `4b7974e5f79a948e98c19f317eadfb452616c83f`이며, 격리 worktree에서
  `:bluetape4k-leader-spring-boot:test` 기준 실행은 `BUILD SUCCESSFUL`이었다.

## 선택한 설계

### Application-owned resolver

새 public API는 framework type을 노출하지 않는 작은 계약으로 둔다.

```kotlin
fun interface LeaderRouteRedirectResolver {
    fun resolve(context: LeaderRouteRedirectContext): URI?
}

data class LeaderRouteRedirectContext(
    val slot: LeaderSlot,
    val leaderState: LeaderState?,
    val evaluatedAt: Instant,
    val leaseSafetyWindow: Duration,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}

fun interface LeaderRouteRedirectRequestMetadataProvider<T> {
    fun capture(request: T): LeaderRouteRedirectRequestMetadata
}

data class LeaderRouteRedirectRequestMetadata(
    val forwardedHeadersPresent: Boolean?,
    val transportPeerAddress: String?,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}
```

`leaderState`는 built-in `STATE` authority가 이미 읽은 상태 기준 데이터를 전달할 때만
채워진다. `CUSTOM` authority는 `null`일 수 있으며, 애플리케이션 resolver가
자체 ownership registry를 사용하는 경우를 허용한다. resolver는 다음 책임을
갖는다.

- 상태 기준 데이터의 `leaseUntil`이 지났거나 공개 endpoint 매핑이 없으면 `null`을
  반환한다.
- `nodeId`, `auditLeaderId`, backend URI를 그대로 반환하지 않고, 애플리케이션이
  관리하는 공개 host/path allowlist에서 URI를 만든다.
- 네트워크 호출, lease mutation, 무제한 재시도, 요청 handler 실행을 하지 않는다.
- 공유 route에 등록할 수 있으므로 상태를 내부에서 변경하지 않는 immutable/thread-safe
  구현이어야 하며, in-memory·non-blocking·bounded CPU/메모리 작업만 수행한다.
- 한 요청의 `NotLeader` 평가에서 최대 한 번만 호출되며, 느린 resolver가 필요하면
  애플리케이션이 별도의 bounded registry/update 경로를 소유한다.
- `CUSTOM` authority에서 `leaderState == null`이면 resolver는 자체 mapping 기준
  데이터의 `validUntil`이 `context.evaluatedAt + context.leaseSafetyWindow`보다
  엄격히 이후인지 확인한 경우에만 URI를 반환한다. 기준 데이터가 없거나 경계
  시각 이하이면
  반드시 `null`을 반환하며, 이 freshness 증명은 resolver와 애플리케이션 registry의
  호출자 계약이다.

`LeaderRouteRedirectRequestMetadata`는 forwarded-header 변환 이전의 transport
peer와 raw header 존재 여부를 애플리케이션 경계가 보존한 값이다. `null`인
`forwardedHeadersPresent` 또는 `transportPeerAddress`는 원시 근거를 확인할 수
없다는 뜻이다. 기본 factory overload는 이 값을 `null`로 전달하며, 따라서
absolute redirect를 거부하고 상대 경로만 허용한다. absolute redirect가 필요하면
애플리케이션은 `ForwardedHeaderFilter`/`ForwardedHeaderTransformer`보다 앞선
경계에서 값을 캡처한 provider overload를 route에 명시적으로 전달해야 한다.
Spring filter/transformer가 이미 요청을 변환하거나 header를 제거한 뒤에는
adapter가 현재 request에서 raw 근거를 복원하려고 시도하지 않는다.

factory는 기존 `interceptor(slot)`와 `filter(slot)` API를 유지한다. redirect를
사용하는 route만 다음 overload에 resolver를 전달한다.

```kotlin
fun interceptor(
    slot: LeaderSlot,
    resolver: LeaderRouteRedirectResolver,
): HandlerInterceptor

fun interceptor(
    slot: LeaderSlot,
    resolver: LeaderRouteRedirectResolver,
    requestMetadataProvider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>,
): HandlerInterceptor

fun filter(
    slot: LeaderSlot,
    resolver: LeaderRouteRedirectResolver,
): WebFilter

fun filter(
    slot: LeaderSlot,
    resolver: LeaderRouteRedirectResolver,
    requestMetadataProvider: LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange>,
): WebFilter
```

기존 overload는 resolver가 없는 것과 같으며, 기존 rejection 동작을 바꾸지
않는다. 자동 설정은 resolver를 전역 bean으로 등록하거나 모든 route에 적용하지
않는다.

### 호출자 migration 예시

redirect는 route별로 명시적으로 연결한다. 다음 예시는 public mapping registry가
`validUntil > context.evaluatedAt + context.leaseSafetyWindow`를 확인하고, 그
조건을 만족하지 못하면 `null`을 반환하는 호출자 계약을 보여 준다.

```kotlin
val resolver = LeaderRouteRedirectResolver { context ->
    val leaderId = context.leaderState?.leader?.auditLeaderId
    val mapping = leaderId?.let(publicLeaderRegistry::find)
    if (mapping == null || !mapping.validUntil.isAfter(
            context.evaluatedAt.plus(context.leaseSafetyWindow),
        )) {
        null
    } else {
        mapping.publicUri
    }
}

val metadataProvider = LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest> { request ->
    LeaderRouteRedirectRequestMetadata(
        forwardedHeadersPresent = request.getAttribute("raw.forwarded.present") as Boolean?,
        transportPeerAddress = request.getAttribute("raw.transport.peer") as String?,
    )
}

registry
    .addInterceptor(factory.interceptor(slot, resolver, metadataProvider))
    .addPathPatterns("/internal/orders/**")
```

WebFlux는 같은 resolver와 provider 계약을 사용해 route filter를 등록한다.

```kotlin
val webExchangeMetadataProvider = LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange> { exchange ->
    LeaderRouteRedirectRequestMetadata(
        forwardedHeadersPresent = exchange.attributes["raw.forwarded.present"] as Boolean?,
        transportPeerAddress = exchange.attributes["raw.transport.peer"] as String?,
    )
}

val redirectFilter = factory.filter(slot, resolver, webExchangeMetadataProvider)
val orderPattern = PathPatternParser.defaultInstance.parse("/internal/orders/**")
val routeScopedFilter = WebFilter { exchange, chain ->
    if (orderPattern.matches(exchange.request.path.pathWithinApplication())) {
        redirectFilter.filter(exchange, chain)
    } else {
        chain.filter(exchange)
    }
}
```

두 adapter를 활성화하는 최소 설정은 다음과 같다. `allowed-hosts`에는 scheme이나
port를 넣지 않고 exact public host만 적는다.

```yaml
bluetape4k:
  leader:
    route-guard:
      enabled: true
      redirect:
        enabled: true
        allowed-hosts: [leader.example]
        trusted-proxy-addresses: [10.0.0.10]
        lease-safety-window: 5s
```

`raw.forwarded.present`와 `raw.transport.peer`는 `ForwardedHeaderFilter` 또는
`ForwardedHeaderTransformer`보다 앞선 application/proxy boundary에서 먼저 캡처해
request attribute 또는 별도 immutable wrapper에 저장해야 한다. MVC에서는 filter
chain보다 높은 우선순위의 application `Filter`가 원시 transport peer와 header 존재
여부를 기록해야 하며, MVC interceptor는 producer 경계로 사용하지 않는다. WebFlux에서는
`ForwardedHeaderTransformer` 적용 이전의 server/`HttpHandler` decorator 또는 외부
trusted boundary가 immutable metadata를 만들어야 하며, 일반 `WebFilter` ordering만으로
pre-transform 원본을 보장하지 않는다. provider를 생략하거나
해당 attribute가 `null`이면 absolute target은 거부되고 `/orders` 같은 relative
target만 후보가 된다. 예시는 transformed request에서 header를 다시 읽지 않으며,
resolver는 synchronous callback이지만 immutable·thread-safe·in-memory·bounded
non-blocking 계약을 지킨다. ordinary `Exception`은 fail-closed, cancellation과
interruption은 각각 보존·복구되고, fatal `Error`는 전파된다.

public API의 KDoc와 EN/KO README는 위 호출 조건, metadata 캡처 순서, 307 결과,
URI allowlist/fragment 규칙, 예외·취소 계약을 설명한다. 현재 release pin의
versioned manual은 새 API claim을 추가하지 않고 비변경 상태를 검증하며, 상세
manual 반영은 1.0.0 release pin 확보 후 수행한다.

### 공통 redirect 정책

`LeaderRouteGuardProperties`에 다음 중첩 설정을 추가한다.

| 속성 | 기본값 | 의미 |
|---|---:|---|
| `bluetape4k.leader.route-guard.redirect.enabled` | `false` | 명시적 resolver 결과를 redirect 응답으로 사용할지 결정한다. |
| `bluetape4k.leader.route-guard.redirect.allowed-hosts` | `[]` | `https`와 기본 port `443` 조합의 absolute URI에 허용할 정확한 ASCII host 목록이다. 비어 있으면 absolute URI를 거부한다. 목록 값 자체가 cross-origin 307 redirect 전송에 대한 명시적 opt-in이다. |
| `bluetape4k.leader.route-guard.redirect.trusted-proxy-addresses` | `[]` | raw forwarded header가 있는 요청에서 redirect를 허용할 numeric transport peer IP 목록이다. hostname, CIDR, IPv6 zone ID, scheme/port 포함 값은 허용하지 않는다. |
| `bluetape4k.leader.route-guard.redirect.lease-safety-window` | `PT0S` | `STATE` redirect 대상 lease가 현재 시각보다 더 오래 유효해야 하는 안전 여유 시간이다. 음수·무한 값은 거부한다. |

redirect status는 v1에서 `307 Temporary Redirect`로 고정한다. POST/PUT 같은
method를 다른 method로 바꾸지 않으면서 permanent-cache 부작용을 만들지 않는
정책이다. status 임의 설정은 후속 이슈로 남긴다.

공통 검증기는 다음 규칙을 순서대로 적용한다.

1. route guard가 비활성화되었거나 redirect가 비활성화되었거나 resolver가
   없으면 `null`을 반환한다.
2. `LeaderRouteDecision.NotLeader`에서만 resolver를 호출한다. `Allowed`는
   handler를 실행하고 `Unavailable`은 redirect 없이 fail-closed 한다.
3. `STATE`의 `NotLeader` evaluation에 lease freshness 증명이 없으면 resolver를
   호출하지 않고 `null`을 반환한다. `CUSTOM` resolver는 context의
   `evaluatedAt`과 `leaseSafetyWindow`를 사용해 자체 registry freshness를
   증명하지 못하면 반드시 `null`을 반환한다.
4. resolver, authority, metadata provider, URI validator의 ordinary `Exception`은
   `null`/rejection으로 정규화한다. `CancellationException`은 그대로 전달하고,
   `InterruptedException`은 interrupt flag를 복구한 뒤 그대로 전달한다. fatal
   `Error`는 숨기지 않는다.
5. URI 전체와 raw component에 control character(`CR`, `LF`, `0x00` 등)가
   있으면 거부한다.
6. relative URI는 absolute authority가 없어야 하고, path가 `/`로 시작하되
   `//`로 시작할 수 없다. fragment 구분자도 허용하지 않는다. 따라서
   network-path reference가 외부 host로 해석되지 않고, client-only fragment가
   서버 간 redirect 계약에 섞이지 않는다.
7. absolute URI는 `https` scheme, ASCII host, userinfo 구분자 없음, fragment
   구분자 없음, 명시적 port 없음(`https` 기본 port `443`)이어야 한다. raw
   authority에 `@`가 있거나 raw fragment가 `null`이 아니거나 raw authority가
   빈 port를 포함하면 거부한다. host는 startup에서 strict parse한
   `allowed-hosts`와 대소문자를 무시해 정확히 일치해야 한다. wildcard, suffix
   match, DNS resolution, IPv6 zone ID는 사용하지 않는다. `http`, non-default
   port, out-of-range port는 모두 거부한다.
8. `LeaderRouteRedirectRequestMetadata.forwardedHeadersPresent`가 `true`이면
   `transportPeerAddress`가 startup에서 numeric canonical form으로 정규화한
   `trusted-proxy-addresses`와 정확히 일치해야 한다. 값이 `null`이거나
   allowlist 밖이면 redirect 후보를 버리고 기존 rejection 응답을 반환한다.
   `false`이면 forwarded-header 신뢰 검사를 하지 않으며, `null`이면 absolute
   redirect를 거부한다. library는 raw header 값을 해석하거나 현재 request의
   변환된 remote address를 transport peer로 간주하지 않는다.
9. 검증된 URI만 `Location` header에 한 번 기록한다. resolver 결과, leader
   identity, backend exception은 response body/header/log에 복사하지 않는다.

이 정책은 forwarded header의 값을 해석하지 않는다. trusted proxy가 header를
정규화한 뒤에도 resolver는 allowlisted public URI를 직접 반환해야 한다. raw
header/transport peer 캡처가 불가능한 배포에서는 provider를 생략하고 상대
경로만 사용해야 한다. 이 경계를 두면 MVC와 WebFlux가 서로 다른 framework
wrapper 상태에 따라 다른 origin을 계산하지 않으며, 공격자가 직접 보낸
forwarded header가 redirect destination을 바꾸지 못한다.

### 단일 상태 조회와 내부 평가 결과

기존 #537의 `STATE` 비용 계약을 유지하기 위해 route runtime은 public
`LeaderRouteDecision`과 redirect에 재사용할 `LeaderState?`를 함께 담은 내부
`LeaderRouteEvaluation`을 사용한다.

- `StateLeaderRouteAuthority`는 한 번 읽은 `LeaderState`로 decision을 계산하고,
  그 값을 같은 evaluation에 보존한다. `state(slot.lockName)`은 한 요청에서
  정확히 한 번만 호출한다.
- `CUSTOM` authority는 기존 public `LeaderRouteAuthority.evaluate`만 호출하며
  `leaderState`는 `null`이다. custom resolver는 자체 소유 registry를 사용할 수
  있지만 authority나 route guard가 두 번째 backend 조회를 추가하지 않는다.
- `Allowed`와 `Unavailable`에서는 resolver를 호출하지 않는다. `NotLeader`에서만
  동일 evaluation의 `leaderState`와 `slot`을 resolver에 한 번 전달한다.
- MVC와 WebFlux의 공통 redirect policy 계산(헤더 존재 여부, peer allowlist,
  URI 검사 포함)은 WebFlux의 기존 bounded evaluation scheduler 경계 안에서
  실행한다. 따라서 resolver와 validator가 event-loop에서 실행되지 않는다.
- `allowed-hosts`와 `trusted-proxy-addresses`는 설정 수신 시 대소문자 정규화와
  중복 제거를 거친 불변 Set으로 복사한다. 요청 경로에서 설정 컬렉션을 복사하거나
  다시 정규화하지 않는다. 요청당 URI/문자열 처리 복잡도는 URI·header 크기에
  선형이고, 네트워크·DNS·무제한 버퍼를 사용하지 않는다.
- `redirect.enabled=true`일 때 `allowed-hosts`, `trusted-proxy-addresses`,
  `lease-safety-window`는 startup에서 strict validation한다. 잘못된 host/IP,
  wildcard, CIDR, zone ID, scheme/port 포함 값과 음수·무한 duration은 조용히
  제거하지 않고 configuration exception으로 fail-fast 한다. redirect가
  비활성화된 경우에는 기존처럼 해당 redirect 설정을 읽거나 bean을 만들지 않는다.
- `route-guard.enabled=false`는 항상 outer auto-configuration gate다. 이 경우
  `redirect.enabled` 값과 redirect 설정의 semantic validation/bean 생성은 모두
  건너뛴다. `route-guard.enabled=true`인 경우에만 `redirect.enabled=true`가
  redirect 설정 validation과 policy wiring을 활성화하며, `redirect.enabled=false`면
  route guard는 기존 factory만 만들고 redirect policy는 만들지 않는다.
- `StateLeaderRouteAuthority`의 `NotLeader` redirect 후보는
  `leader?.leaseUntil`이 존재하고 `clock.instant() + lease-safety-window`보다
  엄격히 이후일 때만 freshness를 통과한다. `null`, 만료, 경계 시각은 모두
  redirect를 거부한다. 이 검사는 redirect eligibility에만 적용하며, 기존
  #537의 `Allowed` 판정과 compatibility를 바꾸지 않는다. `CUSTOM`에서
  `leaderState == null`이면 resolver가 같은 freshness 증명을 소유하고, 증명할
  수 없으면 `null`을 반환한다.
- 매 evaluation은 cross-request cache를 사용하지 않으며, 다음 요청은 새로운
  state와 `Clock` 관찰값으로 회복 여부를 다시 판단한다. 테스트는 fixed clock으로
  expiry와 safety-window 경계를 결정적으로 검증한다.

v1은 특정 latency/throughput 수치를 보장하지 않는다. 대신 단일 state read,
resolver 1회 호출, bounded scheduler 실행, concurrent slow-resolver 격리와
취소, 설정 컬렉션의 immutable 정규화를 focused test로 고정한다. 이후 수치 SLA가
필요하면 별도 benchmark 이슈로 측정 계약을 추가한다.

resolver와 authority callback은 동기 함수이지만 반드시 bounded, non-blocking,
reentrant/thread-safe여야 한다. library는 timeout executor를 만들거나 blocking
callback을 강제로 interrupt하지 않는다. callback이 이 계약을 위반하면 MVC
요청 스레드 또는 WebFlux의 injected evaluation scheduler를 점유할 수 있으므로
호출자 책임으로 기록하고, library는 ordinary exception만 fail-closed로
정규화한다. WebFlux subscription이 queued 상태에서 취소되면 callback 자체를
시작하지 않으며, 이미 실행 중인 동기 callback은 취소로 강제 중단하지 않고
완료 후 downstream을 구독하지 않는다.

### MVC와 WebFlux 실행 흐름

두 어댑터는 다음 순서를 공유한다.

```text
request
  -> passive authority evaluation
  -> Allowed: handler/chain exactly once
  -> NotLeader: resolver -> common URI policy
       -> valid target: 307 + Location, no handler
       -> invalid/null/stale/untrusted: configured rejection, empty body
  -> Unavailable/ordinary failure: configured rejection, empty body
```

STATE authority는 decision과 함께 한 번 읽은 `LeaderState`를 내부 평가 값으로
전달해 redirect resolver가 같은 상태 기준 데이터를 사용하게 한다. 별도의 두 번째
`state()` 호출이나 cache를 만들지 않는다. WebFlux authority 평가·resolver·URI
검증·request metadata 검사는 모두 동일한 bounded evaluation scheduler 경계 안에서
수행하고 event-loop에서 blocking state lookup을 하지 않는다. cancellation은
queued callback을 시작하지 않게 하고, interruption은 기존 #537 계약대로
보존한다. factory가 받은 scheduler는 caller-owned로 간주해 dispose하지 않으며,
기본 `Schedulers.boundedElastic()`와 별도 timer/executor의 lifecycle을 library가
새로 소유하지 않는다.

## 실패 모드와 대응

| 실패 | 관찰 가능한 대응 | 상태 변경 |
|---|---|---|
| resolver가 `null`을 반환 | 기존 rejection status + 빈 body | 없음 |
| `leaseUntil` 만료 또는 상태 기준 데이터가 비어 있음 | resolver가 `null`을 반환하고 기존 rejection | 없음 |
| absolute URI가 allowlist 밖이거나 scheme/userinfo/fragment가 부적합 | redirect 후보 폐기, 기존 rejection | 없음 |
| CRLF/control character 또는 malformed URI | redirect 후보 폐기, 기존 rejection | 없음 |
| untrusted peer가 forwarded header를 보냄 | redirect 후보 폐기, 기존 rejection | 없음 |
| resolver/backend mapping 예외 | cancellation/interruption 외에는 fail-closed | 없음 |
| authority가 `Unavailable` | resolver를 호출하지 않고 기존 rejection | 없음 |

모든 실패는 raw target, host, leader ID, backend 오류를 외부 응답에 포함하지
않는다. 정상적인 authority contention도 예외가 아니라 기존 상태 응답으로
남는다.

## 호환성과 운영

- 새 설정의 기본값은 `redirect.enabled=false`, 빈 allowlist, 빈 trusted-proxy
  목록, `lease-safety-window=PT0S`이다. 기존 애플리케이션은 현재와 동일하게
  `Location`을 받지 않는다. `redirect.enabled=true`인 애플리케이션은
  absolute target에 exact HTTPS host allowlist를 채워야 하며, 목록을 채우는
  행위가 cross-origin 307 redirect 전송에 대한 명시적 opt-in이다.
- 기존 `LeaderProperties`의 10-argument compatibility constructor/copy
  surface와 기존 공개 `LeaderRouteGuardProperties`의 4-argument constructor,
  `copy`, `copy$default` JVM/Kotlin surface를 유지한다. 새 `redirect` 중첩 설정은
  최신 생성자 기본값으로만 추가하며, `LeaderRouteGuardProperties`와 새 redirect
  설정 타입 모두 기존 `Serializable` 계약을 유지한다. 호환성 테스트는 0.5.0
  공개 surface의 descriptor와 serialization round-trip을 확인한다.
- 새 resolver API는 additive이며 기존 `LeaderRouteAuthority` 계약을 변경하지
  않는다.
- 구현할 중첩 설정 타입은 `LeaderRouteRedirectProperties : Serializable`로
  두고, `LeaderRouteGuardProperties.redirect`에 기본값으로 연결한다. 기존
  4-argument public surface를 별도 compatibility constructor/copy bridge로
  보존해 Kotlin/JVM binary descriptor와 Java serialization 형태가 바뀌지
  않도록 한다.
- `leader-spring-boot`의 configuration metadata와 EN/KO README를 같은 구조로
  갱신한다. 예제는 route-scoped resolver와 public mapping registry를 보이고,
  raw backend address를 사용하지 않는다. 사용자 문서의 source of truth인
  `docs/manual/en/frameworks/spring-boot.md`,
  `docs/manual/ko/frameworks/spring-boot.md`,
  `docs/manual/en/modules/bluetape4k-leader-spring-boot.md`,
  `docs/manual/ko/modules/bluetape4k-leader-spring-boot.md`는 현재 manifest의
  `releaseRef=0.5.0`, `releaseCommit=721a9a3808f67489d2bdb8177734325981c24977`에
  고정된 release 문서이므로 이번 develop 변경의 새 redirect API 주장을 추가하지
  않는다. 새 API의 상세 release manual 반영은 1.0.0 release pin 확보 후 별도
  문서 작업으로 수행하고, 이번 이슈에서는 pinned manual 비변경과 release 규칙을
  검증한다.
- 모듈, BOM, dependency, publishing, Nightly workflow 변경은 없다.
- rollback은 `redirect.enabled=false`로 설정한 뒤 애플리케이션을 재기동 또는
  redeploy하는 순서로 수행한다. dynamic refresh는 v1 범위가 아니며, passive guard와
  election state에는 영향을 주지 않는다.

## 검증 설계

### Resolver/policy

- relative `/leader/orders`는 허용하고 `//evil.example/orders`는 거부한다.
- relative `/leader/orders#section`도 fragment가 포함되므로 거부한다.
- `https://leader.example/orders`는 `allowed-hosts`가 정확히 일치할 때만
  허용한다.
- `http://leader.example/orders`, `https://leader.example:8443/orders`,
  `https://leader.example:/orders`, `https://@leader.example/orders`,
  `https://leader.example/orders#`, opaque URI, userinfo, invalid port,
  Unicode/invalid host, CRLF를 거부한다.
- resolver가 raw `nodeId` 또는 backend address를 반환해도 allowlist 밖이면
  응답에 넣지 않는다.
- stale `leaseUntil`, empty state, `Unavailable`, `null`, ordinary exception은
  모두 빈 rejection 응답이 된다.
- `CUSTOM` resolver의 mapping `validUntil`이 `evaluatedAt + safetyWindow`와
  같거나 이전이면 resolver가 `null`을 반환하고, 다음 요청에서 fresh mapping이
  관찰될 때만 redirect가 회복된다.
- raw metadata가 `forwardedHeadersPresent=true`이면 canonical numeric peer가
  trusted allowlist에 있을 때만 allowlisted resolver 결과를 사용할 수 있다.
  `false`는 relative와 allowlisted HTTPS absolute target을 허용하고, `null`은
  absolute target을 거부한다. framework transformer/removeOnly 뒤에서 raw
  근거가 사라진 경우도 같은 fail-closed 결과다.
- allowed host에 wildcard/suffix/CIDR/hostname/IP zone ID가 들어오면 startup
  configuration error가 발생하고, redirect disabled 상태에서는 해당 검증을
  수행하지 않는다.

### MVC/WebFlux parity

- `NotLeader` + 유효 URI는 양쪽 모두 status 307, 동일 `Location`, handler
  미호출이다.
- invalid/null/stale/unavailable는 양쪽 모두 configured rejection status,
  빈 body, `Location` 없음이다.
- allowed request는 handler/chain을 한 번만 실행한다.
- authority/resolver cancellation 및 interruption semantics가 양쪽에서
  유지된다.
- WebFlux 평가가 event-loop에서 실행되지 않고, 취소 시 handler subscription이
  시작되지 않는다.
- MVC/WebFlux adapter가 동일한 raw metadata contract를 common policy에 전달하고,
  trusted/untrusted/unknown peer와 IPv4/IPv6 canonicalization 결과가 parity를
  이룬다.
- resolver ordinary exception은 빈 rejection으로 정규화되고 cancellation,
  interruption, fatal `Error`는 각각 보존·복구·전파된다. WebFlux에서 resolver
  예외가 authority `onErrorResume` 바깥으로 새지 않는다.
- `leaseUntil == null`, 만료, 정확히 safety-window 경계는 redirect를 거부하며,
  fixed clock에서 이후 lease가 다시 유효해지면 다음 요청이 cache 없이 회복한다.
- injected scheduler는 factory가 dispose하지 않고, 별도 timeout executor/timer가
  생성되지 않는다. queued cancellation은 resolver와 handler를 호출하지 않는다.
- STATE authority의 `state()`가 정확히 한 번만 호출되고, resolver가 같은
  `LeaderState` 참조를 한 번 받는다. `Allowed`/`Unavailable`에서는 resolver가
  호출되지 않는다.
- MVC와 WebFlux에서 resolver와 URI validator가 evaluation scheduler 경계를
  벗어나지 않으며, 호출자가 bounded non-blocking 계약을 지키는 resolver의
  동시 평가가 다른 요청의 handler를 무기한 점유하지 않는다. library는 timeout이나
  강제 interrupt를 제공하지 않으므로 계약을 위반한 blocking/slow callback의
  격리는 호출자 registry/update 경로의 책임이다. 취소된 평가와 queued 평가에는
  resolver/validator 호출이 남지 않는다.
- redirect 설정 컬렉션은 startup 시 immutable normalized Set으로 복사되고,
  요청마다 재복사되지 않는다.

### 설정/문서

- redirect default binding, allowlist binding, trusted-proxy binding과 기존
  route-guard metadata parity를 검증한다.
- `route-guard.enabled=false`/`redirect.enabled=true`,
  `route-guard.enabled=true`/`redirect.enabled=false`, 두 값을 모두 true인
  조합을 각각 검증하고, outer disabled 상태에서는 invalid redirect 설정을
  semantic validation하거나 bean을 만들지 않는지 확인한다.
- redirect enabled 상태에서는 malformed allowed host/trusted peer, forwarded
  metadata unknown, HTTP/non-default port target, null/expired lease를 모두
  startup 또는 요청 시 fail-closed로 관찰한다.
- stale/untrusted/malformed/null/exception 결과는 URI·leader identity를 노출하지
  않는 고정 reason code(`STALE_LEASE`, `UNAVAILABLE`, `NULL_TARGET`,
  `URI_REJECTED`, `UNTRUSTED_PROXY`, `METADATA_UNKNOWN`, `CALLBACK_FAILURE`)로만
  관찰할 수 있도록 운영 hook/metric의 확장 지점을 남긴다. hook의 허용 tag는
  `reason`과 `framework`로 제한하고 host/path/leader ID를 넣지 않으며, 기본
  로그에는 민감한 target을 기록하지 않는다. cancellation/interruption/fatal
  `Error`는 응답 reason code로 바꾸지 않고 기존 전파 계약을 유지한다.
- EN/KO README의 property, resolver, security boundary, fallback 설명을 동기화하고,
  현재 release pin의 manual에는 새 API claim이 없는 비변경 상태를 확인한 뒤
  `git diff --check`와 Korean terminology audit를
  실행한다. `./gradlew :bluetape4k-leader-spring-boot:test`,
  `./gradlew :bluetape4k-leader-spring-boot:build`, `./gradlew detekt`,
  `./gradlew exportManualModuleInventory`,
  `ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml`,
  `ruby scripts/manual/validate_release_manuals.rb 0.5.0 721a9a3808f67489d2bdb8177734325981c24977`,
  `ruby scripts/manual/export_manifest.rb --check`,
  `ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'`
  의 결과와 설정 조합 artifact를 plan/DoD에 기록한다.
- AOT, module test/build, detekt, six-perspective security review를 완료한다.

## 대안과 거부 이유

| 대안 | 결정 | 이유 |
|---|---|---|
| resolver URI를 어댑터가 그대로 `Location`에 기록 | 거부 | open redirect와 header injection 검증이 MVC/WebFlux에서 분기된다. |
| library가 `LeaderState.nodeId`/backend address로 URL을 생성 | 거부 | internal topology와 raw identity disclosure를 기본 경로로 만들고 public mapping 책임을 침범한다. |
| library가 raw forwarded header로 origin을 직접 계산 | 거부 | Spring 문서가 지적한 trusted-proxy 경계를 library가 확인할 수 없고, 두 web stack의 wrapper 차이가 생긴다. |
| transformed framework request에서 header/peer를 다시 추론 | 거부 | filter/transformer가 header를 제거하거나 remote address를 바꾼 뒤에는 raw trust evidence가 없으므로 우회 가능성이 생긴다. |
| resolver + raw metadata provider + 공통 HTTPS URI/trusted-peer policy | 채택 | application-owned mapping과 pre-transform trust evidence, library-owned response safety를 분리하고 두 adapter가 같은 결과를 사용한다. |

## Acceptance criteria

- [ ] redirect policy는 opt-in이고 기본 상태는 기존 empty rejection과 같다.
- [ ] application-owned resolver가 `LeaderState?`와 `LeaderSlot`을 받아 공개
      URI 또는 `null`을 반환하고, context의 평가 시각·safety window를 기준으로
      CUSTOM freshness를 증명한다.
- [ ] MVC/WebFlux가 공통 validator로 307/`Location` parity를 제공한다.
- [ ] relative URI, exact host allowlist, scheme/userinfo/fragment/control
      character 규칙이 테스트된다.
- [ ] absolute URI는 exact HTTPS host와 default port `443`만 사용하며 HTTP,
      non-default/empty/out-of-range port, raw userinfo/fragment를 거부한다.
- [ ] raw forwarded metadata가 확인되지 않으면 absolute redirect를 만들지
      않고, trusted canonical transport peer allowlist가 명시된 경우에만
      forwarded-header 후보가 통과한다. MVC/WebFlux 결과가 같다.
- [ ] stale/unavailable/null/exception 위치는 election state를 바꾸지 않고
      fail-closed 한다.
- [ ] STATE는 요청당 `state()`를 정확히 한 번 호출하고, `NotLeader` resolver는
      같은 `LeaderState?`를 최대 한 번 받으며 `Allowed`/`Unavailable`에서는
      호출되지 않는다.
- [ ] resolver는 thread-safe, immutable, in-memory, non-blocking, bounded
      실행 계약을 따르며 WebFlux resolver/validator는 bounded scheduler에서
      실행된다. v1은 정량 latency SLA를 주장하지 않는다.
- [ ] redirect allowlist와 trusted-proxy 설정은 startup 시 immutable normalized
      Set이 되고, 요청 경로에서 재정규화·네트워크 조회·무제한 버퍼를 사용하지
      않는다.
- [ ] `LeaderRouteGuardProperties`의 기존 4-argument constructor, `copy`,
      `copy$default`, `Serializable` surface와 `LeaderProperties`의 10-argument
      compatibility surface가 유지된다.
- [ ] `route-guard.enabled` outer gate와 `redirect.enabled` inner gate의 세
      설정 조합, restart-required rollback, low-cardinality reason-code 관찰
      경계가 테스트/운영 문서에 고정된다.
- [ ] `leaseUntil`이 null·만료·safety-window 경계이면 redirect를 거부하고,
      `Allowed` 호환성은 유지한다. 다음 요청은 cache 없이 fixed-clock 기준으로
      재평가한다.
- [ ] `CUSTOM` mapping의 `validUntil`이 `evaluatedAt + safetyWindow`와 같거나
      이전이면 `null`을 반환하고, 다음 요청에서 fresh mapping이 관찰될 때만
      redirect가 회복된다.
- [ ] resolver/authority/metadata/validator ordinary exception은 fail-closed,
      cancellation/interruption/fatal Error는 각각 보존·interrupt 복구·전파한다.
- [ ] resolver는 bounded non-blocking reentrant/thread-safe 계약을 따르고,
      WebFlux의 queued cancellation과 scheduler ownership 규칙이 테스트된다.
- [ ] raw leader/backend metadata가 기본 response에 노출되지 않는다.
- [ ] #607의 request-to-lease lifecycle은 변경하지 않는다.
- [ ] EN/KO README, pinned release manual의 비변경 검증, metadata, public KDoc,
      AOT 및 module validation이 통과하고 manifest의 `releaseRef`/`releaseCommit`
      규칙을 지킨다. 새 redirect API의 release manual 반영은 1.0.0 release pin
      확보 후 별도 작업으로 추적한다.
- [ ] MVC/WebFlux route-scoped resolver와 raw metadata provider의 최소 migration
      예제가 EN/KO README에 있고, pinned manual은 새 API claim 없이 검증되며,
      provider 생략·null metadata의 relative-only 결과가 문서와 테스트에서 일치한다.

## 요구사항 추적성

| 요구사항 | 설계 근거 | 구현·검증 증거 |
|---|---|---|
| opt-in과 기존 rejection 호환 | 공통 정책 1, 호환성과 운영 | redirect default binding, MVC/WebFlux 기존 rejection 회귀 |
| application-owned resolver와 단일 state read | API 계약, 단일 상태 조회 | `LeaderRouteEvaluation`, authority interaction, resolver context 테스트 |
| HTTPS exact-origin과 URI 안전성 | 공통 정책 5-7, Resolver/policy | URI validator negative matrix, allowed-host startup validation |
| raw forwarded trust boundary | request metadata provider, 공통 정책 8 | MVC/WebFlux raw metadata parity와 unknown/untrusted fail-closed 테스트 |
| stale/unavailable no mutation | freshness 규칙, 실패 모드 | fixed-clock expiry/recovery, no `acquire/extend/release` interaction |
| cancellation/scheduler ownership | callback 실행 계약, 실행 흐름 | resolver/authority cancellation, injected scheduler lifecycle 테스트 |
| 문서·metadata·AOT parity | 설정/문서 검증 | EN/KO README, pinned manual 비변경 검증, configuration metadata, AOT/module/detekt 및 release-manual 명령 |
| 공개 호환성 및 outer gate | 호환성과 운영, 단일 상태 조회 | 4-argument property surface, serialization, disabled/enabled configuration matrix |

## 설계 DoD

- 승인된 범위와 제외 범위가 #606/#607/#537 관계와 일치한다.
- public API, property defaults, response status, trust boundary, failure
  matrix가 구현 계획과 테스트 목록으로 추적된다.
- P0/P1 검토 finding이 0이고, 구현 전 설계 review artifact를 남긴다.

## Writer gate

- `SPW-01`: PASS — #606 live issue, #537 source, current route factories,
  Spring forwarded-header references, exact API/property identifiers를 확인했다.
- `SPW-02`: PASS — 문제, 범위, 계약, 보안 규칙, 실패 모드, 호환성, 대안,
  acceptance와 DoD를 포함했다.
- `SPW-03`: PASS — 한국어 technical register를 사용하고 code/API/URL/status
  token을 보존했다.
- `SPW-04`: PASS — 현재 `leader-spring-boot` source/test, #537 artifact,
  #606/#607 live issue와 설계 주장을 대조했다.
- `SPW-05`: PASS — 파일을 read-back하여 표, code fence, 링크, traceability를
  확인했다.

설계 검토 결과: `P0=0, P1=0`을 유지해야 다음 plan 단계로 진행한다.
