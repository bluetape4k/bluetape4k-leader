# Issue #856 custom backend conformance 설계

## 문제

`StrategicLeaderElector`, `StrategicLeaderGroupElector`,
`StrategicSuspendLeaderElector`, `StrategicSuspendLeaderGroupElector`는 외부 backend가
후보 레지스트리를 구현할 수 있는 public interface다. 그러나 현재 `leader-core`
testFixtures에는 lock·lease 계약만 있고, strategic 후보의 등록·갱신·만료·결과 기록과
winner 계산을 같은 assertion으로 검증할 재사용 가능한 fixture가 없다.

그 결과 custom backend가 다음 오류를 포함해도 내장 Local/Lettuce/Redisson 테스트만으로는
발견할 수 없다.

- missing 또는 expired 후보를 `refreshCandidate`가 다시 등록한다.
- `refreshCandidate`와 `updateResult`가 동시에 실행될 때 결과 카운터를 잃는다.
- `unregisterCandidate`와 heartbeat가 경합할 때 후보가 다시 나타난다.
- 후보 기준 목록이 같아도 single/group winner가 달라진다.

## 목표와 성공 조건

외부 backend 작성자가 자신의 provider만 주입해서 네 strategic API 변형의 공통 계약을
검증할 수 있는 `leader-core` test fixture를 제공한다. fixture는 다음을 증명해야 한다.

1. winner action은 결과를 한 번 반환하고 loser action은 실행하지 않은 채 `null`을 반환한다.
2. missing/expired refresh는 no-op이며, 기존 후보 refresh는 통계와 등록 시각을 보존한다.
3. TTL 만료 뒤 후보가 목록과 winner 계산에서 제외된다.
4. concurrent `refreshCandidate`/`updateResult`는 성공 카운터를 잃지 않는다.
5. concurrent `refreshCandidate`/`unregisterCandidate`는 후보를 부활시키지 않는다.
6. cleanup 역할의 `unregisterCandidate`는 반복 호출해도 최종 상태가 비어 있다.
7. blocking single/group과 suspend single/group에 같은 의미를 적용한다.

## 현재 근거

- 네 public strategic interface는 후보 저장 연산과 `runIfLeader`를 직접 정의한다.
- `leader-core/src/testFixtures/.../contract`는 abstract JUnit 5 contract test를 backend
  모듈이 상속하는 방식을 사용한다.
- Issue #847은 네 interface의 default refresh를 missing/expired no-op으로 고정했다.
- `RedissonStrategicHeartbeatExpirationRaceTest`는 TTL이 `0ms`가 된 순간과 실제 key 제거
  사이의 경계를 기다리지 않아 전체 build에서 실패할 수 있다. key 부재를 직접 확인해야
  expiry assertion의 전제가 성립한다.
- strategic public API에는 async 또는 virtual-thread 전용 interface가 없다. async와
  virtual-thread는 각각 `AsyncLeaderElector`, `VirtualThreadLeaderElector`의 lock 기반
  실행 모델이다.

## 선택한 설계

### Provider-injectable abstract fixture

`StrategicBackendConformanceProvider`는 `StrategicBackendKind`로 single/group을 구분하고,
각 kind에 다음 두 execution adapter를 만든다.

- blocking single
- blocking group
- suspend single
- suspend group

각 adapter는 public strategic interface를 얇게 감싸고, fixture가 필요한 backend test
control인 `awaitCandidateExpiration`을 provider가 담당한다. 실제 backend는 bounded
polling을, fake backend는 test clock 진행을 사용하되 관리 API 삭제로 TTL 계약을 우회하지
않는다.

`AbstractStrategicBackendConformanceTest`는 JUnit 5 testFixtures API다. 하위 테스트는
`createProvider()`만 구현한다. 각 test는 새 provider를 만들고 `close()`를 정확히 한 번
호출한다. 후보 cleanup은 `unregisterCandidate`의 idempotent final-state 계약으로 검증한다.

### 실행 모델 구분

strategic API가 제공하는 실행 모델은 blocking과 suspend이며 single/group 변형이 있다.
async와 virtual-thread custom lock backend는 기존 `AsyncLeaderElector` 및
`VirtualThreadLeaderElector` 계약 fixture를 사용해야 한다. README에는 이 capability
matrix를 명시해 존재하지 않는 strategic async/virtual-thread API를 암시하지 않는다.

### Custom adapter 증명

`leader-core` 테스트에 built-in Local 구현을 재사용하지 않는 test-only custom provider를
작성한다. 이 provider는 shared concurrent store와 실제 후보 부재를 확인하는 bounded
expiry 대기를 구현하고 새
fixture를 상속한다. 이 테스트는 외부 vendor를 도입하지 않으면서 provider 적용 방법과
fixture 자체의 실행 가능성을 증명한다.

## 대안

### 대안 1: 각 Redis 모듈에 assertion을 복사

기존 backend 테스트와 비슷하지만 외부 backend가 재사용할 수 없고 네 API 변형 사이의
계약이 다시 분산된다. 중복이 커져 채택하지 않는다.

### 대안 2: `@TestTemplate`와 custom extension 제공

mode discovery를 자동화할 수 있지만 JUnit extension lifecycle과 suspend bridge가 public
test API에 추가된다. 현재 abstract fixture 관례보다 사용법과 실패 진단이 복잡하므로
채택하지 않는다.

### 대안 3: production source에 conformance runner 제공

런타임 진단에는 쓸 수 있지만 JUnit/assertion 의존성과 backend admin control이 production
API로 새어 나온다. testFixtures 전용 범위를 지키기 위해 채택하지 않는다.

## 책임 경계

- fixture는 backend의 저장 연산과 결과 상태를 검증하지만 성능이나 장애 복구를 자동
  보증하지 않는다.
- provider는 test namespace, client lifecycle, 실제 expiry 확인 수단, test 종료 cleanup을
  소유한다.
- application은 custom backend의 credential, retry, timeout, provisioning을 계속 소유한다.
- production strategic interface와 직렬화 형식은 변경하지 않는다.
- 새 dependency, module, workflow, BOM, catalog 변경은 없다.

## 실패 모드와 방어

| 실패 모드 | 관찰 신호 | 방어 및 검증 |
|---|---|---|
| refresh/updateResult lost update | 최종 `successCount`가 실행 횟수보다 작음 | 같은 후보에 두 연산을 동시에 반복하고 정확한 카운터 확인 |
| unregister 뒤 zombie candidate | 모든 작업 종료 뒤 목록에 node가 남음 | refresh/unregister barrier 경합 후 빈 목록 확인 |
| TTL 판정 조기 완료 | TTL은 0이지만 key가 남아 refresh가 성공함 | provider가 실제 부재를 확인한 뒤 expiry 완료를 보고하도록 계약 |
| provider resource leak | 다음 테스트에 후보가 남거나 client가 닫히지 않음 | test별 provider 생성, `finally`에서 단 한 번 `close()` |
| 잘못된 execution-model 주장 | 사용자가 없는 strategic async API를 찾음 | README capability matrix로 strategic와 lock 기반 fixture 분리 |

## 호환성과 공개 변경

production ABI/API 변경은 없다. `leader-core`의 published testFixtures variant에 additive
interface와 abstract test class가 추가된다. 기존 consumer는 영향을 받지 않는다. fixture
이름이나 adapter method를 바꾸면 testFixtures consumer source compatibility가 깨질 수
있으므로 1.1.0 공개 이후에는 production API와 같은 수준으로 변경을 검토한다.

## 수용 기준 추적

| Issue 수용 기준 | 설계 대응 |
|---|---|
| 실행 모델별 최소 계약 | strategic blocking/suspend와 lock async/virtual-thread capability matrix |
| skip/null/result, refresh, expiry, cleanup | abstract fixture의 winner/loser, lifecycle, expiry, idempotent unregister 테스트 |
| concurrent atomicity/linearizability | register/refresh/update/unregister 경합의 허용 최종 상태 명시 |
| provider-injectable fixture | `StrategicBackendConformanceProvider`와 kind별 blocking/suspend factory |
| custom backend 적용 | test-only shared concurrent store provider |
| core 문서/README 책임 구분 | 영문·한글 `leader-core` README 동시 갱신 |
| test/detekt/ABI | targeted core/Redisson, detekt, binary compatibility 검증 |

## 인라인 6관점 설계 리뷰

독립 lane을 사용하지 않는 현재 실행 조건과 사용자의 inline fallback 지시에 따라 같은
exact spec을 여섯 관점으로 각각 검토했다. 독립 review provenance는 주장하지 않는다.

| 관점 | 판정 | 근거와 처분 |
|---|---|---|
| 성능 | P0=0, P1=0 | bounded 반복만 사용하고 actual-absence expiry 대기를 provider control로 분리 |
| 안정성 | P0=0, P1=0 | 경합 최종 상태와 provider lifecycle을 명시; Redisson actual absence 대기 포함 |
| 보안 | P0=0, P1=0 | credential/provisioning은 provider 책임이며 fixture에 secret surface 없음 |
| 운영 | P0=0, P1=0 | capability와 미보증 범위를 README에 명시; production 관측 경로 변경 없음 |
| 개발자/API | P0=0, P1=0 | 기존 abstract contract 관례를 재사용하고 production API 변경 없음 |
| 사용자/caller | P0=0, P1=0 | 네 strategic mode와 async/virtual-thread 경계를 명시해 오용 방지 |

초기 설계의 강제 삭제 방식은 TTL을 구현하지 않은 backend도 통과할 수 있는 안정성 P1로
판정해 actual-absence 대기 계약으로 교체했다. 구현 중 `updateResult` 뒤 completion time까지
보존한다고 가정한 assertion과 과도한 public cleanup hook은 각각 P2로 발견해 수정·삭제했다.
수정 후 통합 판정은 `P0=0`, `P1=0`이며 미처리 finding은 없다.

## DoD

- provider-injectable fixture와 custom provider 적용 테스트가 통과한다.
- 네 strategic mode가 같은 lifecycle/concurrency/winner 계약을 검증한다.
- Redisson expiry race가 실제 key 부재를 전제로 검증된다.
- 영문·한글 README와 Korean KDoc이 실제 API 이름과 일치한다.
- 관련 module test, `detekt`, `checkBinaryCompatibility`, `git diff --check`가 통과한다.
- exact-head 인라인 리뷰에서 P0/P1이 0이고 PR CI가 성공한다.

## Writer DoD

- SPW-01 PASS: 독자는 custom backend 작성자이며 source와 Issue #856/#847을 근거로 했다.
- SPW-02 PASS: 문제, 대안, 경계, 실패 모드, 호환성, 수용 기준, DoD를 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인했고 식별자를 보존했다.
- SPW-04 PASS: public interface와 기존 fixture 경로를 source에 대조했다.
- SPW-05 PASS: 표, 코드 토큰, heading, 수용 기준 추적을 최종 read-back했다.
