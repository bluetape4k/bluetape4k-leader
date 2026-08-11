# Issue #671: 공통 leader contract fixture 적용 설계

## 결정 상태

- 이슈: [#671](https://github.com/bluetape4k/bluetape4k-leader/issues/671)
- 상태: 구현 완료, 7-tier inline review 및 delivery gate 대기
- 작업 브랜치: `chore/issue-671-contract-fixtures`
- 기준 커밋: `9799ea37789c40b843e9412fc5758bbba13a120d`
- 범위: etcd, Consul, DynamoDB, Kubernetes Lease의 테스트 계약 채택

## 문제와 목표

네 backend 모듈은 `leader-core`의 `testFixtures`를 의존하지만, 공통
leader-id와 LockAssert/LockExtender 계약을 구체적인 backend 테스트로
채택하지 않는다. 이 상태에서는 backend가 실제 slot/lease overload를
구현했는지와 core 계약을 보존하는지를 모듈별 회귀 테스트로 보장할 수 없다.

이번 변경은 다음을 보장한다.

1. 지원하는 blocking, async, suspend, group 조합마다 기존 abstract
   contract fixture를 상속하는 concrete test가 존재한다.
2. 공통 fixture로 표현할 수 없는 virtual-thread wrapper/overload와 실제
   slot/lease 경로는 backend-local 직접 테스트로 검증한다.
3. 지원하지 않는 조합은 기계 판독 가능한 test-only capability matrix에서
   `N/A`와 이유를 함께 기록한다.
4. 네 module의 일반 test 및 Kubernetes `k8sTest` fan-out에서 새 계약이
   실제 실행된다.

## 범위 밖

- production API, lock 알고리즘, dependency, publication 버전 변경
- `README.md`, `README.ko.md`, manual의 실행 모델/autoExtend 문서 변경
  (별도 이슈 #672)
- async/virtual-thread 전용 LockExtender abstract fixture 신설
- backend protocol 단위 테스트를 공통 계약으로 치환

## 현행 근거와 불변 조건

- `leader-core`에는 leader-id용 blocking/group/suspend/async abstract
  fixture와 sync/group/suspend LockExtender fixture가 이미 있다.
- `LeaderElector`와 `LeaderGroupElector`는 async API를 상속하지만, backend가
  slot overload를 실제로 override하는 경우에만 async leader-id fixture를
  적용한다. 현재 etcd는 lockName async overload만 구현하고 blocking/suspend
  single elector도 slot overload를 override하지 않으므로 해당 leader-id
  조합은 `N/A`로 기록한다.
- virtual-thread API는 `VirtualFuture`를 사용하고 공통 abstract fixture가
  없으므로 각 backend의 실제 wrapper 또는 executor overload를 직접
  호출해야 한다.
- 정상 contention은 예외가 아니라 skip/null/result여야 한다. 계약 테스트는
  이 core 규칙을 바꾸지 않는다.
- Testcontainers/K3s 수명주기는 backend 기존 base test와 동일하게
  유지한다. DynamoDB 계약은 기존 DynamoDB Local launcher 위에 계약 전용
  table/client를 만들고, K8s 계약은 K3s singleton 위에 class별 client를
  만들고 `@AfterAll`에서 닫는다. 계약 test는 production client lifecycle을
  변경하지 않는다.
- Kubernetes Lease가 요구하는 DNS-1123 lock 이름과 모든 backend의
  랜덤 lock 이름을 함께 만족하도록 `leader-core` test fixture의 Base58
  lock suffix를 소문자로 정규화한다. 이는 test-only 입력 경계이며
  production lock 이름 정책은 변경하지 않는다.

## 선택한 구조

### 1. Concrete subclass 채택

각 backend의 `src/test/kotlin/.../contract/`에 다음 종류의 클래스를
추가한다.

- `AbstractLeaderElectorLeaderIdContractTest`
- `AbstractLeaderGroupElectorLeaderIdContractTest`
- `AbstractAsyncLeaderElectorLeaderIdContractTest`
- `AbstractAsyncLeaderGroupElectorLeaderIdContractTest`
- `AbstractSuspendLeaderElectorLeaderIdContractTest`
- `AbstractSuspendLeaderGroupElectorLeaderIdContractTest`
- backend가 실제 LockExtender를 지원하는 경우의
  `AbstractSyncLockExtenderContractTest`,
  `AbstractGroupLockExtenderContractTest`,
  `AbstractSuspendLockExtenderContractTest`,
  `AbstractSuspendGroupLockExtenderContractTest`

각 subclass는 기존 backend test base에서 client/container를 얻고, 해당
backend의 public constructor/factory를 사용한다. protocol mock이나
내부 lock client를 fixture에 주입하지 않는다.

### 2. Virtual-thread와 overload 직접 계약

`leader-core` fixture를 억지로 확장하지 않고 backend-local 테스트에서
다음을 직접 검증한다.

- etcd: `EtcdVirtualThreadLeaderElector` wrapper의 lockName 단일 leader
  경로와 group executor overload. wrapper에는 slot overload가 없으므로
  virtual slot leader-id는 `N/A`로 기록한다.
- DynamoDB: `DynamoDbVirtualThreadLeaderElector`와
  `DynamoDbVirtualThreadLeaderGroupElector`의 slot 경로
- Consul/Kubernetes: blocking elector가 제공하는
  `runAsyncIfLeader(..., executor)`/slot overload의 실제 executor 경로

직접 테스트는 결과가 `Elected`이고 `leaderId`가 slot 값과 같으며, lease가
완료 후 재획득 가능함을 확인한다. LockExtender의 ThreadLocal/코루틴
컨텍스트 계약은 기존 sync/suspend fixture에만 맡긴다.

### 3. Test-only capability matrix와 정적 검증

`scripts/ci/leader-contract-capabilities.json`을 machine-readable matrix로
추가한다. 이 파일은 독자 문서가 아니라 CI/test 계약의 source of truth다.
각 행은 다음 필드를 가진다.

```json
{
  "backend": "etcd",
  "module": "bluetape4k-leader-etcd",
  "contract": "leader-id-sync",
  "status": "supported",
  "test": "leader-etcd/src/test/.../EtcdLeaderGroupElectorLeaderIdContractTest.kt",
  "base": "AbstractLeaderGroupElectorLeaderIdContractTest"
}
```

`status`가 `na`이면 `reason`이 필수다. 예를 들어 async/virtual
LockExtender 조합은 해당 core fixture가 없고 lexical lock context를
정의하지 않으므로 `N/A`로 기록한다. 전용 virtual wrapper가 없는 backend
조합은 public blocking elector의 executor overload가 지원되는지에 따라
`supported` 직접 테스트 또는 구체적인 `N/A`로 구분한다.

`scripts/ci/validate_leader_contract_matrix.py`는 다음을 정적으로
검증한다.

- `supported` 행의 test 파일과 abstract base 토큰이 실제로 존재한다.
- `na` 행은 비어 있지 않은 `reason`을 가진다.
- 네 backend 모두 single/group 및 sync/suspend leader-id 기본 조합을
  누락하지 않는다.
- virtual/slot/lease direct test 행은 해당 test 파일과 backend 모듈을
  가리킨다.
- matrix의 `module` 값은 `settings.gradle.kts`에 실제 등록되어야 한다.
- `ci.yml`의 backend job이 해당 module `test`를 실행하며, K8s 행은
  `k8sTest`까지 포함한다.

기존 `ci-contract` job에서 `--static`과 `--self-test`를 실행한다. 신규
backend job이나 README matrix는 만들지 않는다.

## Capability matrix 초안

| backend | leader-id sync single | leader-id async single/group | leader-id sync group | leader-id suspend single/group | LockExtender sync/suspend | virtual/direct overload |
|---|---|---|---|---|---|---|
| etcd | N/A (blocking slot override 없음) | N/A (slot async override 없음) | supported | suspend single N/A, group supported | supported | lockName wrapper + group executor direct |
| Consul | supported | supported | supported | supported | supported | executor overload direct |
| DynamoDB | supported | supported | supported | supported | supported | single/group wrapper direct |
| Kubernetes Lease | supported | supported | supported | supported | supported | executor overload direct (`k8sTest`) |

공통 LockExtender matrix의 async/virtual 열은 네 backend 모두 `N/A`로
기록한다. leader-id async는 Consul/DynamoDB/Kubernetes가 실제 slot
overload를 제공할 때만 supported로 기록하고, etcd의 async single/group과
blocking/suspend single leader-id는 실제 slot overload가 없어 `N/A`로
남긴다. etcd virtual slot도 wrapper에 실제 overload가 없어 `N/A`다. 이는
backend 결함을 숨기는 것이 아니라 source에 없는 조합을 계약으로 선언하지
않는 경계이며, 별도 API 변경은 이번 이슈의 범위를 넘는다.

## Test/CI 실행 순서

의존성이 적은 backend부터 다음 순서로 한 slice씩 완료한다.

1. etcd: 지원되는 group leader-id 두 계열, 네 LockExtender 계열, virtual
   wrapper/group executor direct 경로
2. Consul: 동일 계열과 executor overload
3. DynamoDB: 동일 계열과 두 virtual wrapper
4. Kubernetes: 동일 계열과 K3s direct overload
5. matrix validator와 `ci-contract` fan-out 검증

각 slice마다 먼저 해당 module compile/test를 실행하고, integration
컨테이너가 필요한 테스트를 module 전체 테스트로 재실행한다. K8s는
`test`와 `k8sTest`를 모두 실행한다. 공통 계약과 protocol 테스트 결과를
서로 다른 증거로 기록한다.

## 실패·롤백 전략

- fixture 상속 단계에서 compile failure가 나면 production 수정 대신
  constructor/option 차이를 backend-local adapter로 해결한다.
- 실제 backend가 slot/lease overload를 제공하지 않으면 해당 조합을
  `N/A`로 낮추고 public API 근거를 기록한다. 테스트를 `@Disabled`로
  숨기지 않는다.
- Testcontainers/K3s가 환경에서 unavailable하면 code review와 compile
  증거를 유지하되 integration 결과를 `PENDING`으로 보고한다.
- 각 slice는 독립 커밋으로 되돌릴 수 있게 유지한다.

## 설계 자체 검토

- 요구사항 매핑: 네 backend concrete fixture, explicit N/A, virtual/slot/
  lease direct test, CI fan-out을 모두 포함한다.
- Kotlin 지침: 기존 Kluent/`io.github.bluetape4k.assertions`와
  `@TestInstance(PER_CLASS)` 패턴을 재사용하며 새 assertion API나
  dependency를 추가하지 않는다.
- 경계 검토: README #672와 production 구현을 변경하지 않는다.
- 회귀 위험: async fixture는 blocking elector의 상속 API만 사용하고,
  virtual direct test는 실제 backend wrapper/overload만 호출한다.
- 운영 위험: K8s 테스트는 기존 `@Tag("k8s")`와 `k8sTest` task를 유지한다.
- 검증 누락 방지: matrix validator가 파일/abstract-base/CI task 연결을
  정적으로 확인한다.

## 승인 후 구현 산출물

- 네 backend의 `contract/` concrete Kotlin tests
- `scripts/ci/leader-contract-capabilities.json`
- `scripts/ci/validate_leader_contract_matrix.py`와 self-test
- `.github/workflows/ci.yml`의 `ci-contract` 검증 호출
- 이 spec에 연결된 한국어 PR/issue evidence
