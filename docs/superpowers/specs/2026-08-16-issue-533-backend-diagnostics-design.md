# Issue #533 backend diagnostics 설계

## 목적

leader backend의 기능 지원 범위와 연결 상태를 Spring 또는 Ktor에 종속되지 않은 공통 계약으로 제공한다. 운영 표면은 backend 구현 세부사항을 해석하지 않고 동일한 응답 모델을 사용한다.

## 결정

정적 capability와 connectivity probe를 분리한다.

- `LeaderBackendDiagnosticsProvider`는 backend descriptor를 항상 제공한다.
- 정적 descriptor 조회는 외부 I/O를 수행하지 않는다.
- connectivity probe는 명시적으로 요청했을 때만 실행한다.
- probe는 양수·유한 timeout을 요구하며 lock 획득, lease 변경, key/table/namespace scan을 금지한다.
- 기존 client의 수명주기를 재사용한다. probe를 위해 client, thread pool, scheduler를 새로 만들지 않는다.
- 안전한 수동 상태 확인이 없는 backend는 `UNKNOWN`을 반환한다. 성공을 추측하거나 무제한 blocking I/O를 실행하지 않는다.
- exception message, endpoint, credential, lock name 같은 backend 원문은 응답에 포함하지 않는다.

## Core 모델

`leader-core`에 다음 공개 모델을 추가한다.

```kotlin
enum class LeaderBackendSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class LeaderBackendConnectivityStatus { UP, DOWN, UNKNOWN, NOT_CHECKED }

enum class LeaderBackendClockSource { PROCESS, BACKEND, CONFIGURABLE, NOT_APPLICABLE, UNKNOWN }

enum class LeaderBackendTtlMode {
    CLIENT_LEASE,
    SERVER_TTL,
    DATABASE_TIMESTAMP,
    SESSION,
    NONE,
    UNKNOWN,
}

enum class LeaderExecutionModel { BLOCKING, ASYNC, SUSPEND, VIRTUAL_THREAD }

data class LeaderBackendModeSupport(
    val single: LeaderBackendSupport,
    val group: LeaderBackendSupport,
)

data class LeaderBackendCapabilities(
    val singleExecutionModels: Set<LeaderExecutionModel>,
    val groupExecutionModels: Set<LeaderExecutionModel>,
    val leaseExtension: LeaderBackendModeSupport,
    val auditState: LeaderBackendModeSupport,
    val clockSource: LeaderBackendClockSource,
    val ttlMode: LeaderBackendTtlMode,
    val limitations: List<String> = emptyList(),
)

data class LeaderBackendDescriptor(
    val backendId: String,
    val displayName: String,
    val capabilities: LeaderBackendCapabilities,
)

data class LeaderBackendConnectivity(
    val status: LeaderBackendConnectivityStatus,
    val checkedAt: Instant? = null,
    val latencyMillis: Long? = null,
)

data class LeaderBackendDiagnostics(
    val descriptor: LeaderBackendDescriptor,
    val connectivity: LeaderBackendConnectivity,
)

interface LeaderBackendDiagnosticsProvider {
    val backendDescriptor: LeaderBackendDescriptor

    fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity =
        LeaderBackendConnectivity.unknown(Clock.systemUTC().instant())

    fun diagnostics(
        probe: Boolean = false,
        timeout: Duration = 500.milliseconds,
    ): LeaderBackendDiagnostics
}

interface LeaderBackendDiagnosticsAware {
    val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
}
```

공개 factory는 `NOT_CHECKED`, `UP`, `DOWN`, `UNKNOWN` 결과를 일관되게 생성하고 timeout과 latency의 음수 값을 거부한다. `diagnostics(probe=false)`는 `checkConnectivity`를 호출하지 않는다.

## Backend provider 규칙

각 backend의 single/group, blocking/suspend 구현은 동일한 immutable descriptor를 공유한다. elector가 `LeaderBackendDiagnosticsProvider`를 구현하므로 Spring과 Ktor가 별도 client wiring 없이 선택된 elector에서 provider를 찾을 수 있다.

수동 상태 확인만 사용한다.

| Backend | Connectivity 근거 |
|---|---|
| Local | process-local 구현이므로 `UP` |
| Lettuce | 기존 connection의 open 상태 |
| Redisson | 기존 client의 shutdown/shutting-down 상태 |
| Exposed JDBC/R2DBC | 안전한 bounded 수동 상태가 없어 `UNKNOWN` |
| MongoDB | lock collection만으로 bounded ping을 보장할 수 없어 `UNKNOWN` |
| DynamoDB | client 구성만으로 연결 성공을 증명할 수 없어 `UNKNOWN` |
| etcd/Consul | lock-client 계약에 무해한 health method가 없어 `UNKNOWN` |
| Kubernetes | 기존 client의 종료/적응 상태만 안전하게 확인하고 불명확하면 `UNKNOWN` |
| Hazelcast | lifecycle service running 상태 |
| ZooKeeper | Curator connection state |

`UNKNOWN`은 provider 누락이 아니라 “안전한 probe로 연결 성공을 증명하지 않음”을 뜻한다.

## Decorator 계약

listening, tenant-scoped, Micrometer decorator는 `LeaderBackendDiagnosticsAware`를 구현하고 delegate가 제공하는 provider를 nullable 값으로 전달한다. wrapper가 항상 provider를 직접 구현하면 provider가 없는 사용자 구현도 지원하는 것처럼 보일 수 있으므로, canonical elector만 `LeaderBackendDiagnosticsProvider`를 구현한다. selector는 provider 자체 또는 carrier가 전달한 provider를 같은 규칙으로 해석한다.

## Spring Boot 표면

- 기존 `leaderElection` endpoint와 응답 data class는 변경하지 않는다.
- 새 `leaderBackendDiagnostics` endpoint는 정적 descriptor와 `NOT_CHECKED` connectivity를 반환한다.
- 새 health indicator는 `bluetape4k.leader.observability.backend-health.enabled=true`일 때만 probe를 실행한다.
- timeout 기본값은 `500ms`이고 양수·유한 값만 허용한다.
- provider가 없으면 endpoint/health를 만들지 않는다.
- health mapping은 `UP -> UP`, `DOWN -> DOWN`, `UNKNOWN/NOT_CHECKED -> UNKNOWN`이다.
- 기존 `state-provider-bean` 선택 결과와 동일한 elector를 사용해 다중 backend 선택 의미를 바꾸지 않는다.

## Ktor 표면

- 기존 `/management/leaderElection` 응답을 변경하지 않는다.
- `backendDiagnosticsRouteEnabled=false`가 기본값이다.
- 기본 경로는 `/management/leaderElection/diagnostics`이다.
- `backendConnectivityCheckEnabled=false`이면 정적 descriptor와 `NOT_CHECKED`를 반환한다.
- connectivity check가 켜진 경우에만 설정한 timeout으로 provider를 호출한다.
- plugin에 설정한 elector가 provider를 구현하지 않으면 diagnostics route 설치 시 명확한 설정 오류를 낸다.

## 문서 및 manifest

README의 기존 capability matrix는 기준 데이터 원본인 `scripts/ci/leader-contract-capabilities.json`에서 계속 생성한다. 각 row에 runtime descriptor source anchor를 추가하고 validator가 source 존재와 backend ID를 확인해 문서와 런타임 descriptor의 drift를 차단한다.

## 범위 제외

- lock을 실제로 획득하는 synthetic probe
- backend key/table/lease 목록 scan
- credential, endpoint, raw exception 노출
- 지속 polling, cache, scheduler, background refresh
- 기존 `leaderElection` endpoint ABI 변경
- management write action과 audit export. 각각 #532, #535에서 처리한다.

## 승인 조건

- 모든 backend module이 provider를 opt-in한다.
- 정적 조회는 외부 I/O를 실행하지 않는다.
- probe timeout과 실패가 caller 작업 또는 leader lease를 변경하지 않는다.
- Spring/Ktor 표면은 기본 비활성 또는 `NOT_CHECKED` 상태를 유지한다.
- core, backend fake/provider, Spring/Ktor response 테스트가 통과한다.
- README EN/KO와 source-backed manifest가 일치한다.
