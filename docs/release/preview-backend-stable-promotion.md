# 미리보기 백엔드 안정 승격 체크리스트

이 체크리스트는 `README.md` / `README.ko.md`의 미리보기 백엔드 행을 `Stable`로 변경하기 전에 필요한 증거를 정의합니다.

`docs/release/preview-backend-nightly-gate.md`를 보완합니다. 녹색 전체 야간 실행은 릴리스 증거가 필요하지만 백엔드를
안정 버전으로 승격시키는 것만으로는 충분하지 않습니다.

## 범위

현재 미리보기 백엔드:

| Backend | Module | Storage model |
|---|---|---|
| Consul | `leader-consul` | Consul Session + KV acquire/release |
| DynamoDB | `leader-dynamodb` | Conditional writes + logical TTL |
| etcd | `leader-etcd` | etcd v3 Lock service + leases |
| Kubernetes Lease | `leader-k8s` | `coordination.k8s.io/v1` Lease |

승격은 백엔드별로 이루어집니다. 아래의 각 행이 독립적으로 충족되지 않는 한 모든 미리보기 백엔드를 함께 승격하지 마세요.

## 공유 승격 기준

| Area | Required evidence | Blocks promotion when |
|---|---|---|
| Runtime contracts | Single-leader and group-leader behavior is covered by focused tests, including contention, reacquire, release, timeout, and cleanup paths. | Any split-brain, stale-owner, lost-release, or cleanup timeout remains unresolved. |
| API stability | Public options, endpoint/configuration types, auto-configuration properties, and KDoc have no planned breaking rename for the next minor line. | A backend still exposes third-party implementation details that should be hidden behind bluetape4k-owned DTOs or properties. |
| Cancellation and lifecycle | Blocking, async, suspend, and virtual-thread paths supported by the backend have equivalent timeout/cancellation semantics. | A supported execution model leaks resources, hides cancellation, or depends on caller-side sleep/reaper behavior. |
| CI and Nightly | PR CI module test passes, and the latest full `Nightly` preview backend release summary reports `success` for the backend. | The backend is skipped in full Nightly, has only a fast smoke result, or has a recent unresolved flake/failure. |
| Documentation | README locale set, KDoc, configuration docs, release notes, and known limitations all match the implemented contract. | README says Stable while docs still mention preview-only caveats or missing setup requirements. |
| Examples | At least one runnable adoption example exists when the backend has non-trivial setup or operational semantics. | Users must infer required infrastructure, credentials, TTL, or lease behavior only from tests. |
| Benchmark or operational evidence | Existing benchmark rows or operational notes explain relative cost, noise, and unsupported comparisons. | Performance docs invite unsupported comparisons or omit known noisy rows. |
| Release governance | The promotion PR links all evidence and closes or creates follow-up issues for remaining non-blocking work. | Promotion depends on chat-only evidence, stale local runs, or hidden follow-up work. |

## 백엔드 체크리스트

### Consul

필수 증거:

- Consul 경로가 변경되면 PR CI에서 `Test / leader-consul`이 성공합니다.
- 전체 `Nightly` `Preview Backend Release Summary`는 `Test / leader-consul`에 대해 `성공`을 보고합니다.
- 세션/KV 획득, 해제, 소유권 읽기, 시간 초과 및 정리 테스트에는 블로킹, 비동기 및 suspend 경로가 포함됩니다.
- Spring Boot 자동 구성 문서 및 속성은 불안정한 타사 구현 세부 정보를 공개 계약으로 노출하지 않습니다.
- `examples/consul-maintenance`는 실행 가능한 상태로 유지되며 두 로케일 README 파일에 문서화되어 있습니다.

현재 0.4.0 차단기 검사:

- 별도의 Consul 예제 간격이 열려 있지 않습니다.
- 승격하기 전에 `leader-consul` 공개 엔드포인트/옵션/구성 유형에 대해 API 안정성 검토를 실행하고 검토 결과를 PR에 연결하세요.

### DynamoDB

필수 증거:

- DynamoDB 경로가 변경되면 PR CI에서 `Test / leader-dynamodb`가 성공합니다.
- 전체 `Nightly` `Preview Backend Release Summary`는 `Test / leader-dynamodb (DynamoDB Local)`에 대해 `성공`을 보고합니다.
- 지원되는 실행 모델에는 조건부 쓰기 소유권, 논리적 TTL, 오래된 항목 교체, 시간 초과 및 정리 의미 체계가 포함됩니다.
- DynamoDB 로컬 설정, AWS 지역/자격 증명 요구 사항, 테이블/TTL 동작 및 Spring Boot 속성이 문서화되어 있습니다.
- 실행 가능한 DynamoDB 채택 사례가 있습니다.

현재 0.4.0 차단기 검사:

- DynamoDB 예약 내보내기 리더 예에서는 `#423`이 아직 열려 있습니다. 해당 예제 또는 명시적으로 동등한 채택 경로가 병합될 때까지
- DynamoDB를 미리 보기로 유지합니다.

### etcd

필수 증거:

- etcd 경로가 변경되면 PR CI에서 `Test / leader-etcd`가 성공합니다.
- 전체 `Nightly` `Preview Backend Release Summary`는 `Test / leader-etcd (Testcontainers)`에 대해 `성공`을 보고합니다.
- 리스 승인/연결 유지/해지, 서비스 소유권 잠금, 정리 시간 초과 및 더 풍부한 소유권 메타데이터는 테스트 및 문서에서 다룹니다.
- `examples/etcd-reconciler`는 실행 가능한 상태로 유지되며 두 로케일 README 파일에 문서화되어 있습니다.
- README 행이 Stable로 변경되기 전에 알려진 etcd 클라이언트/버전 제약 조건이 문서화되어 있습니다.

현재 0.4.0 차단기 검사:

- 별도의 etcd 예제 간격이 열려 있지 않습니다.
- 승격하기 전에 etcd 정리/메타데이터 테스트 슬라이스를 다시 실행하고 결과를 전체 Nightly 요약과 연결하세요.

### Kubernetes Lease

필수 증거:

- `Test / leader-k8s`는 유닛/스모크 슬라이스에 대한 PR CI에서 성공해야 합니다.
- 전체 `Nightly` `Preview Backend Release Summary`는 `Test / leader-k8s (K3s + group slots)`에 대해 `성공`을 보고합니다.
- 리스 획득/업데이트/해제, 그룹 슬롯 동작, 네임스페이스/이름 유효성 검사 및 K3s 런타임 적용 범위는 Fabric8/Vert.x 런타임 종속성이
- 변경된 후 모두 녹색입니다.
- `examples/k8s-lease` 및 `examples/k8s-operator`는 실행 가능한 상태로 유지되며 두 로케일 README 파일에 문서화되어 있습니다.
- Kubernetes RBAC, 네임스페이스 및 K3s/Testcontainers 요구 사항은 사용자 대상 문서에 명시되어 있습니다.

현재 0.4.0 차단기 검사:

- `#480`은 Fabric8 Vert.x 런타임 오류를 해결했으며 해당 수정 후 최신 전체 Nightly는 2026-06-04에 성공했습니다:
- https://github.com/bluetape4k/bluetape4k-leader/actions/runs/26984503181
- 최신 전체 Nightly가 성공할 때까지 최신 K3, Fabric8 또는 Vert.x 런타임 회귀를 승격 차단으로 처리합니다.

## 승격 PR 규칙

사용자가 결합된 릴리스 준비 PR을 명시적으로 요청하지 않는 한 각 백엔드에 전용 프로모션 PR을 사용합니다.

각 프로모션 PR에는 다음이 포함되어야 합니다.

1. `README.md` 및 `README.ko.md` 상태 행이 `Preview`에서 `Stable`로 업데이트됩니다.
2. 최신 전체 Nightly 요약 및 대상 로컬 검증에 대한 링크입니다.
3. P0=0 및 P1=0인 API/KDoc 검토 결과입니다.
4. 누락된 예제가 의도적으로 릴리스를 차단하지 않는 경우 예제/문서 증거 또는 연결된 후속 조치 문제입니다.
5. 프로모션 및 남은 제한 사항을 설명하는 릴리스 노트 또는 변경 로그 항목입니다.

드라이브 바이 문서 PR에서 백엔드 상태 행을 `Stable`로 변경하지 마세요.
