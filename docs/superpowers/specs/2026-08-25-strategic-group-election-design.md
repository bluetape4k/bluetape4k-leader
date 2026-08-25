# Strategic Group Election API 설계 사양

상태: 승인된 설계

작성일: 2026-08-25

이슈: [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463)

트레인: `STRATEGIC-01`

## 1. 목적과 현재 계약

현재 `LeaderGroupElector`/`SuspendLeaderGroupElector`는 분산 세마포어의
slot을 획득한 노드를 최대 `maxLeaders`개까지 동시에 통과시킨다. 반면
`StrategicLeaderElector`/`StrategicSuspendLeaderElector`는 후보 레지스트리의
`CandidateInfo`를 `ElectionStrategy`로 평가해 단일 후보만 선택한다.

`#463`은 두 모델을 후보 집합 기준으로 결합한다. 호출자는 후보를 등록하고,
매 선출 라운드마다 같은 후보 목록과 `maxLeaders`에서 최대 N개의 후보를
결정론적으로 선택한다. 선택된 노드만 작업을 실행하고, 나머지는 즉시
`null`을 반환한다.

이 계약은 **관찰한 후보 snapshot에 대한 advisory top-N 선출**이다. 여러 backend
호출이 서로 다른 시점의 snapshot을 읽을 수 있으므로 `maxLeaders`는 각 라운드의
선택 수이지 모든 분산 노드의 전역 동시 실행 상한이 아니다. 전역 상한과 fencing이
필요한 작업은 기존 `LeaderGroupElector`를 사용해야 한다.

범위는 다음 구현체로 한정한다.

| 실행 모델 | 지원 백엔드 |
| --- | --- |
| blocking | `LocalStrategicLeaderGroupElector`, `LettuceStrategicLeaderGroupElector`, `RedissonStrategicLeaderGroupElector` |
| coroutine | `LocalStrategicSuspendLeaderGroupElector`, `LettuceStrategicSuspendLeaderGroupElector`, `RedissonStrategicSuspendLeaderGroupElector` |

Exposed, MongoDB, DynamoDB, etcd, Consul, Kubernetes, Hazelcast, ZooKeeper와
기존 `ElectionStrategy` ABI 변경은 이 설계의 범위가 아니다.

## 2. 확정 결정

### 2.1 별도 strategy 계약

기존 `ElectionStrategy.elect(List<CandidateInfo>)`는 단일 승자 계약이므로
그 시그니처를 변경하지 않는다. 새 `GroupElectionStrategy`는
`maxLeaders`를 명시적으로 받아 복수 승자 결과를 반환한다.

```kotlin
fun interface GroupElectionStrategy {
    fun elect(
        candidates: List<CandidateInfo>,
        maxLeaders: Int,
    ): StrategicGroupElectionResult
}
```

`maxLeaders`는 `1` 이상이어야 하며, 구현체는 후보 수보다 큰 값도 허용한다.
후보가 부족하면 존재하는 후보를 모두 선택한다.

전략이 반환한 결과는 elector가 후보 snapshot과 대조한다. winner와 elimination의
후보 ID는 입력 snapshot에 있어야 하고 중복·교집합·누락·`maxLeaders` 초과가
있으면 `IllegalArgumentException`으로 즉시 거부한다. 후보 ID의 동일성 기준은
`nodeId`다. 입력 후보 자체에 중복 ID가 있으면 backend registry 계약 위반으로
간주하고 같은 예외를 반환한다.

### 2.2 결과 모델

```kotlin
data class StrategicGroupElectionResult(
    val winners: List<CandidateInfo>,
    val eliminations: List<Elimination>,
    val scores: Map<String, Double> = emptyMap(),
) : Serializable
```

결과 불변식은 다음과 같다.

- `winners`는 선출 우선순위가 높은 순서로 정렬된 목록이다.
- 한 결과에서 `winners`의 `nodeId`는 중복되지 않는다.
- `winners`와 `eliminations`는 같은 후보를 동시에 포함하지 않는다.
- 모든 입력 후보는 `winners` 또는 `eliminations` 중 정확히 한 곳에 기록된다.
- 빈 후보 목록은 `StrategicGroupElectionResult.EMPTY`를 반환한다.
- `scores`는 점수 전략이 계산한 후보만 `nodeId` 키로 담고, FIFO 전략은 빈 맵을 반환한다.

`Elimination.reason`은 진단용 사람이 읽는 문자열이며 안정적인 파싱 계약이
아니다. 안정적인 분류가 필요하면 후속 이슈에서 사유 코드 필드를 별도로
설계한다.

### 2.3 공통 옵션과 실행 semantics

`runIfLeader`는 기존 strategic API의 후보 등록, TTL, 결과 갱신, cancellation
semantics를 그대로 유지하며 `LeaderGroupElectionOptions`를 재사용한다.
선출에 직접 사용하는 필드는 `maxLeaders`다. `options.nodeId`는 무시하고
elector의 `nodeId`를 현재 후보 ID로 사용한다. `waitTime`, `leaseTime`,
`minLeaseTime`, `useDbTime`은 기존 생성자 검증은 적용하지만 lock/lease 의미는
일반 group elector에만 적용하고 strategic candidate registry에는 새 distributed
claim을 추가하지 않는다.

```kotlin
fun <T> runIfLeader(
    lockName: String,
    strategy: GroupElectionStrategy,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: () -> T,
): T?
```

coroutine variant는 같은 인자와 `suspend () -> T` action을 사용한다.

- `registerCandidate`, `unregisterCandidate`, `listCandidates`, `updateResult`는
  기존 `StrategicLeaderElector`와 같은 시그니처와 TTL 전달 규칙을 따른다.
- 현재 `nodeId`가 관찰된 snapshot의 winner 목록에 없으면 action을 호출하지 않고
  `null`을 반환한다.
- 현재 `nodeId`가 관찰된 snapshot의 winner 목록에 있으면 해당 호출에서 action을
  한 번 호출하고 그 결과를 반환한다. 서로 다른 snapshot에서 여러 node가 선택될
  수 있으며, 이는 이 API의 비범위인 전역 동시 실행 보장과 구분한다.
- action 성공 시 `CandidateResult.SUCCESS`, 일반 예외 시 `FAILURE`를 best-effort로
  기록한다. TTL이 action 전후에 만료되면 결과 갱신은 no-op일 수 있다.
- `CancellationException`은 결과 갱신으로 삼키지 않고 기존 구현과 동일하게
  재전파한다. 결과 갱신 자체의 일반 예외는 경고 로그 후 action 결과를 보존한다.

### 2.4 결정론적 내장 전략

#### `FifoGroupElectionStrategy`

`registeredAt` 오름차순, 동률이면 `nodeId` 사전순으로 전체 후보를 정렬하고
앞에서 `maxLeaders`개를 winner로 선택한다. 나머지는
`registered later` 또는 `nodeId lexicographically after winner` 의미의
진단 사유로 elimination에 기록한다.

#### `ScoredGroupElectionStrategy`

기존 `CandidateScorer`를 재사용한다. 각 후보 점수를 한 번 계산한 뒤 점수
내림차순, 동점이면 `registeredAt` 오름차순, 다시 동률이면 `nodeId` 사전순으로
정렬하고 앞에서 `maxLeaders`개를 선택한다. 결과의 `scores`에는 모든 입력
후보의 점수를 기록한다.

`CandidateScorer`가 `NaN` 또는 무한대를 반환하면 `IllegalArgumentException`으로
실패한다. 유한 점수만 순위와 `scores`에 사용한다.

## 3. 백엔드 경계

- Local은 기존 `ConcurrentHashMap` 후보 레지스트리와 lock/mutex 보호를
  재사용한다.
- Lettuce와 Redisson은 기존 후보 레지스트리의 TTL, 직렬화, 후보 목록 조회,
  결과 갱신 경로를 재사용한다.
- strategic single과 strategic group은 backend별 별도 key namespace를 사용해
  같은 `lockName`을 우연히 공유해도 후보 집합과 결과 갱신을 섞지 않는다.
- 후보 목록 조회와 선택은 backend별 read consistency 범위 안에서 수행한다.
  이 API는 후보 선택과 action 실행 사이에 새로운 distributed atomic claim을
  제공하지 않으며, `maxLeaders`의 전역 동시 실행 상한을 보장하지 않는다.
- TTL이 만료된 후보는 기존 레지스트리 조회 결과에 포함되지 않으며, 한
  라운드에서 읽은 후보 목록은 해당 라운드의 선택 입력으로 고정한다.
- `Duration.ZERO`는 만료되지 않는 후보 등록이다. 유한 TTL 후보는 호출자가
  재등록/heartbeat해야 하며 권장 cadence는 TTL보다 짧게 잡는다. Local은
  기존 구현과 같이 TTL을 저장하지 않고 프로세스 메모리 수명으로 유지한다.
- 지원하지 않는 backend에 전략적 group adapter를 추가하는 작업은 별도
  issue로 분리한다.

## 4. 호환성과 공개 API 원칙

- 기존 `ElectionStrategy`, `StrategicLeaderElector`,
  `StrategicSuspendLeaderElector`, `LeaderGroupElectionOptions`의 기존
  메서드와 JVM descriptor를 변경하지 않는다.
- 새 public 타입과 KDoc은 기존 bluetape4k Kotlin 패턴, `requireGe` 계열
  검증 helper, `CandidateInfo`/`CandidateScorer` 재사용 규칙을 따른다.
- 별도 의존성이나 새 serialization 포맷을 추가하지 않는다.
- 단일 strategic election과 strategic group election은 key namespace로
  구조적으로 격리되며, 사용 가이드에서도 두 실행 모델의 선택 기준을 구분한다.

## 5. 수용 기준과 테스트 표

| 영역 | 검증 |
| --- | --- |
| 전략 결과 | 빈 후보, 후보 수가 N보다 적은 경우, 정확히 N개, 중복 후보 방지 |
| 결정론 | FIFO의 `registeredAt`/`nodeId` tie-break, scored의 score/`registeredAt`/`nodeId` tie-break |
| 실행 | 선택된 node만 action 실행, 비선택 node는 `null`과 action 미실행 |
| 상태 | 성공·실패 `updateResult`, 예외 전파, coroutine cancellation 재전파 |
| backend | Local unit, Lettuce Redis integration, Redisson Redis integration의 blocking/coroutine 대칭 |
| 문서 | README.md와 README.ko.md의 API 표, 선택 가이드, 지원 backend 범위 일치 |
| 회귀 | 기존 strategic single 및 기존 group 테스트와 ABI/Detekt/build 통과 |

## 6. 비범위와 후속 이슈

- 전역 동시 실행 상한을 위한 atomic claim, 선택된 여러 노드의 순차 실행,
  quorum, weighted capacity는 지원하지 않는다. `winners` 순서는 관찰 가능한
  우선순위이며 실행 순서를 보장하지 않는다.
- action을 시작한 뒤 lease를 자동 연장하는 별도 watchdog은 추가하지 않는다.
- candidate registry 읽기와 action 시작 사이의 fencing token 또는 atomic claim은
  별도 설계가 필요하다.
- Exposed/R2DBC group contract와 추가 strategic backend adapter는 현재
  트레인 이후 issue로 분리한다.

## 7. 설계 근거

- 현재 API와 구현: `StrategicLeaderElector`,
  `StrategicSuspendLeaderElector`, Local/Lettuce/Redisson strategic elector
- 기존 단일 전략: `ElectionStrategy`, `ElectionResult`,
  `FifoElectionStrategy`, `ScoredElectionStrategy`
- 공통 group 옵션: `LeaderGroupElectionOptions`
- 선행 계약 검토: [#681](https://github.com/bluetape4k/bluetape4k-leader/issues/681)
- 요구사항 원문: [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463)
