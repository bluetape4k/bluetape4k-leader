# 리더 선출 시각적 동반 문서 설계

**날짜:** 2026-07-30

**저장소:** `bluetape4k/bluetape4k-leader`

**이슈:** `#633`, `#634`, `#635`; 게시 후속 작업 `bluetape4k/bluetape4k.github.io#305`

**릴리스 기준선:** `17ab7f872c1f96318c73d3580729cac20a67e017`의 `0.4.0`

## 배경

Leader manual은 단일 리더 및 그룹 리더 계약, Redis Lettuce 동작, Spring Boot
애너테이션을 별도 페이지로 설명한다. 따라서 독자는 락 획득, 토큰 소유권,
lease TTL, 작업 시간, 경합, 만료, 연장, 해제 사이의 시간 의존적인 관계를
여전히 직접 재구성해야 한다.

이 변경은 소스 저장소가 소유하는 독립형 시각 자료 두 개를 영어·한국어로
추가한다.

1. 락과 lease 모델을 정립하는 상세한 `LeaderElector` 동반 문서
2. 첫 번째 모델을 재사용하고 `1 -> N` 슬롯 차이만 설명하는 간결한
   `LeaderGroupElector` 동반 문서

이 동반 문서는 교육용 시뮬레이션이다. Redis에 연결하거나 라이브러리 코드를
실행하지 않으며 백엔드 스케줄링 정밀도를 재현한다고 주장하지 않는다.

## 목표

- 안내형 대화형 Redis Lettuce 타임라인을 통해 blocking `LeaderElector` 계약을
  설명한다.
- 정상적인 경합이 예외가 아니라 skip(`null` 또는 `LeaderRunResult.Skipped`)으로
  끝나는 과정을 눈에 보이게 한다.
- `waitTime`, `leaseTime`, `minLeaseTime`, 작업 시간, 만료, 해제, 인수,
  `autoExtend`를 구분한다.
- 동일한 실행 경계를 Spring Boot `@LeaderElection`에 매핑한다.
- `LeaderGroupElector`를 최대 `maxLeaders`개의 독립 소유 슬롯을 사용하는
  동일한 lease 모델로 설명한다.
- `activeCount`, `availableSlots`, `isFull`, 포화 상태, 슬롯 가용화 후의
  진입을 보여 준다.
- 그룹 선출은 동시성을 제한하지만 작업을 분할하거나 할당하지 않는다는 점을
  명시한다.
- 그룹 동작을 `@LeaderGroupElection`에 매핑하고 stream 제약을 명시한다.
- 자동·라이트·다크 테마를 지원하는 영어·한국어 원문 동등 페이지를 제공한다.
- 기존 source-manifest 및 GitHub Pages snapshot 흐름을 통해 게시한다.

## 범위 제외

- async, coroutine, virtual-thread, Reactor, Kotlin Flow 스케줄링 시뮬레이션
- Redis Lettuce와 Redisson 또는 다른 백엔드 비교
- fencing token을 시연하거나 exactly-once 비즈니스 효과를 약속하는 일
- Redis 배포, connection pooling, 장애 감지, cluster topology 교육
- wall-clock 정밀도, Redis 명령 지연 시간, watchdog jitter 재현
- `LeaderGroupElector`를 work queue, partition allocator, shard coordinator로
  바꾸는 일
- runtime dependency, network request, analytics script, build framework
  포함

## 소스 소유권과 게시

소스 저장소가 다음 정식 아티팩트를 소유한다.

```text
docs/visual-companions/manifest.json
docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.html
docs/superpowers/specs/2026-07-30-leader-elector-visual-companion.ko.html
docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.html
docs/superpowers/specs/2026-07-30-leader-group-elector-visual-companion.ko.html
docs/manual/assets/visual-companions/
scripts/validate-visual-companions.mjs
tests/visual-companions/validator.test.mjs
```

소스 PR은 `docs/leader-election-visual-companions`에서 `develop`을 대상으로
한다. 이 PR이 병합되면 `bluetape4k.github.io`는 별도의 PR에서
`docs/publish-leader-visual-companions`를 출발점으로 정확한 병합 커밋을
snapshot한다. 별도 PR의 대상도 `develop`이다.

manual은 게시된 동반 문서 route를 기본 대화형 경험으로 연결하고, 대화형
기능을 사용할 수 없는 탐색과 시각 검토를 위해 저장소가 소유한 2x PNG
fallback을 포함한다. 독립형 HTML이 계속 정식 시각 자료다.

## 공통 경험 모델

두 동반 문서는 다음 5단계 안내 흐름을 공유한다.

1. **모델** — 후보, Redis 소유권 데이터, 보호할 작업을 식별한다.
2. **설정** — 시나리오를 바꾸는 소수의 매개변수를 확인한다.
3. **Direct API** — 타임라인을 `runIfLeader` 및 `runIfLeaderResult`에 연결한다.
4. **Spring 매핑** — 동일한 애너테이션 경계와 구성을 보여 준다.
5. **실패 및 복구** — 경합, 만료, 단일 리더 연장, 이후 진입을 관찰한다.

학습자가 단계 사이를 이동하는 동안 타임라인과 현재 상태가 계속 보이도록
레이아웃을 구성한다. 의미를 색상에만 의존하지 않도록 시맨틱 버튼, 레이블,
상태 텍스트, live region을 사용한다.

### 공통 컨트롤

컨트롤의 범위는 의도적으로 제한한다.

- 작업 시간
- `leaseTime`
- reset 및 play/pause
- 테마: auto, light, dark

`LeaderElector` 시나리오 preset은 경합, 만료/인수, 연장이다.
`LeaderGroupElector` preset은 가용 용량, 포화/skip, 만료/이후 진입이다.
그룹 옵션은 `autoExtend`를 제공하지 않고 활성 락의 명시적 연장은 시뮬레이션
범위 밖이므로 그룹 페이지에는 연장 preset을 제공하지 않는다.

컨트롤은 제약 없는 입력 대신 이산 값을 사용한다. 값을 변경할 때마다
시뮬레이션을 결정적인 초기 상태로 재설정한다.

### 공통 타임라인 의미

타임라인은 고정된 논리 tick 단위로 진행한다. 다음과 같이 운영 환경의
타이밍이 아니라 교육을 위한 순서를 표시한다.

```text
request -> wait/acquire -> action -> release
                         -> lease expiry -> later acquire
single only              -> periodic extension -> release
```

모든 획득에는 불투명 토큰이 부여된다. 제시된 토큰이 여전히 락 또는 슬롯을
소유한 경우에만 해제나 연장이 성공한다. lease를 초과해 실행 중인 프로세스가
후속 소유자의 소유권을 안전하게 해제하거나 연장할 수 있다고 시각화해서는
안 된다.

## 동반 문서 1: LeaderElector

### 교육 계약

첫 번째 동반 문서는 상세한 기반 설명이다. 하나의 논리적 `lockName`에는 한 번에
최대 하나의 규약 준수 소유자만 진입할 수 있다.

기본 장면에는 세 후보(`node-a`, `node-b`, `node-c`), 하나의 Redis 락 레코드,
하나의 보호된 작업 레인이 있다. 선택한 preset이 후보들의 시도가 어떻게
겹치는지 제어한다.

### Redis Lettuce 표현

시각 자료는 백엔드를 다음 개념적 레코드로 표현한다.

```text
lockName -> { token, owner, ttl }
```

이는 교육용 표현이며 Redis key serialization을 약속하는 것이 아니다. 다음
항목을 보여 줘야 한다.

- 락이 없을 때의 atomic acquisition
- 불투명 소유권 토큰
- `leaseTime`부터 시작하는 TTL 카운트다운
- 토큰 검증을 거치는 연장
- 토큰 검증을 거치는 해제
- 유효한 해제 또는 lease 만료 후 락이 사라지는 과정

### 구성 의미

| 설정 | 시각적 의미 |
| --- | --- |
| `waitTime` | 경합자가 skip하기 전에 재시도할 수 있는 최대 논리 시간 창 |
| `leaseTime` | 획득 시 부여되고 성공한 각 연장으로 복원되는 TTL |
| `minLeaseTime` | 일찍 끝난 작업이 해제하기 전에 유지해야 하는 최소 성공 보유 시간. `leaseTime`을 초과할 수 없다. |
| 작업 시간 | 시뮬레이션에서 보호된 작업이 활성 상태로 유지되는 시간 |
| `autoExtend` | 단일 리더 경로에서 작업이 활성 상태인 동안 주기적인 lease 연장을 시작함 |

`waitTime`은 안내 문구에서 설명하지만 자유 슬라이더로 노출하지 않고 preset으로
제어한다. 이렇게 하면 컨트롤 표면을 작게 유지하면서 경합 모델을 보존할 수
있다.

### 시나리오 preset

#### 경합

- `node-a`가 락을 획득하고 작업을 실행한다.
- `node-b`가 소유권이 유지되는 동안 시도한다.
- 소유권이 `node-b`의 대기 시간 창을 넘어 계속 유지되면 `node-b`는 skip한다.
- 결과 패널은 skip을 `null` 및 `LeaderRunResult.Skipped`에 매핑한다.
- 정상적인 경합 예외는 표시하지 않는다.

#### 만료와 인수

- `node-a`가 작업 시간보다 짧은 lease를 획득한다.
- `autoExtend`는 비활성화된다.
- `node-a`가 아직 실행 중인 동안 lease가 만료된다.
- 이후 `node-b`가 시도하여 새 토큰을 획득하고 규약 준수 소유자가 된다.
- `node-a`는 stale worker로 표시되며 `node-b`의 토큰을 해제할 수 없다.
- 안내 문구는 리더 선출만으로 외부 부작용을 fencing할 수 없으며 작업은
  멱등적이어야 한다고 경고한다.

#### 연장

- `node-a`가 작업 시간보다 짧은 lease를 획득한다.
- `autoExtend`가 활성화된다.
- 주기적인 성공 연장이 만료 전에 TTL을 복원한다.
- 연장된 소유권이 유효한 동안 경합자들은 skip한다.
- 작업이 완료되고 토큰 검증을 거친 해제로 락이 제거된다.

### Direct API 매핑

동반 문서는 다음 소스 동등 Kotlin snippet을 보여 준다.

```kotlin
val value = elector.runIfLeader("invoice-close") {
    closeInvoices()
}
```

그리고:

```kotlin
when (val result = elector.runIfLeaderResult("invoice-close") { closeInvoices() }) {
    is LeaderRunResult.Elected -> showCompleted(result.value)
    LeaderRunResult.Skipped -> showSkipped()
    is LeaderRunResult.ActionFailed -> showFailure(result.cause)
}
```

페이지는 작업이 반환하는 `null`과 `LeaderRunResult.Skipped`를 명시적으로
구분한다. `runIfLeaderResult`는 이 모호성을 제거한다.

### Spring Boot 매핑

Spring 패널은 동일한 락 식별자와 타이밍을 사용한다.

```kotlin
@LeaderElection(
    name = "invoice-close",
    waitTime = "PT0.5S",
    leaseTime = "PT10S",
    minLeaseTime = "PT1S",
    autoExtend = true,
)
fun closeInvoices(): CloseSummary? = service.closeInvoices()
```

다음 내용을 설명한다.

- AspectJ compile-time weaving이 호출을 보호한다.
- `@EnableAspectJAutoProxy`는 필요하지 않다.
- Kotlin 메서드는 `open`일 필요가 없다.
- private 메서드는 가로채지 않는다.
- `name`에는 유효한 SpEL 표현식을 사용할 수 있다.
- synchronous 및 suspend 값과 `Mono`, `Flux`, `Flow`를 지원한다.
- 긴 stream에는 `autoExtend = true`가 필요하다. 단, lease 안에서 완료된다는
  보장이 있을 때만 `streamBounded = true`를 사용할 수 있다.

시뮬레이션은 synchronous로 유지한다. 다른 실행 모델은 호환성 참고 사항일
뿐이며 별도의 애니메이션 엔진이 아니다.

### 상태 모델

렌더러는 하나의 평범한 데이터 상태에서 모든 표시 결과를 도출한다.

```text
scenario
logicalTick
playing
actionDuration
waitTime
leaseTime
minLeaseTime
autoExtend
candidates[]
lock { token, owner, acquiredAt, expiresAt }?
events[]
resultByCandidate
```

주어진 컨트롤 선택에 대한 상태 전이 함수는 결정적이다.

## 동반 문서 2: LeaderGroupElector

### 교육 계약

두 번째 동반 문서는 다음 문장으로 시작한다.

```text
LeaderElector: 1 lock -> at most 1 leader
LeaderGroupElector: 1 group -> at most N occupied slots
```

상세한 락/lease 설명을 반복하지 않는다. 점유된 각 슬롯에는 여전히 불투명
토큰과 lease가 있으며, 달라지는 부분은 진입 용량이다.

### 추가 컨트롤

- 후보 수
- `maxLeaders`

후보 수는 가용 용량 상태와 포화 상태를 모두 표현할 수 있어야 한다.
`maxLeaders`는 제한된 양수 범위를 사용하며 페이지는 1 미만의 잘못된 값을
막는다.

직접 사용하는 `LeaderGroupElectionOptions` 계약은 `maxLeaders >= 1`을
허용하지만, Spring 시작 검증은 `@LeaderGroupElection.maxLeaders > 1`을
요구하고 단일 리더 사용 사례에는 `@LeaderElection`을 사용하도록 안내한다.
Spring 매핑은 이 더 엄격한 경계를 명시해야 한다.

### 시나리오 preset

- **가용 용량** — `maxLeaders`보다 적은 슬롯이 점유된 동안 후보들이 독립적인
  토큰을 획득한다.
- **포화 및 skip** — 그룹이 가득 차고 대기 마감에 도달한 경합자가 예외 없이
  skip한다.
- **만료 및 이후 진입** — 점유된 슬롯이 만료되고 이후 후보가 새 토큰으로
  새롭게 가용해진 용량을 획득한다.

명시적인 그룹 lease 연장은 호환성 참고 사항으로 남기며 애니메이션으로
표현하지 않는다.

### 그룹 상태

페이지는 다음 값을 계속 계산하여 표시한다.

```text
activeCount
availableSlots = maxLeaders - activeCount
isFull = activeCount >= maxLeaders
```

슬롯이 가용할 때 도착한 후보는 독립적인 슬롯 토큰을 획득하고 동시에
실행한다. 대기 시간 창 안에 획득하지 못한 후보는 skip한다. 토큰이 해제되거나
만료되면 이후 후보가 새로 가용해진 용량을 점유할 수 있다.

### 작업 분배 경계

핵심 경고는 계속 표시한다.

> 슬롯 소유권은 동시성을 제한할 뿐, 고유 작업을 할당하지 않는다.

시각 자료는 슬롯 인덱스를 비즈니스 파티션으로 보여 줘서는 안 된다. 후보가
배타적인 작업 할당을 필요로 한다면 애플리케이션이 queue, partition map,
claim table 또는 다른 작업 분배 메커니즘을 제공해야 한다.

### Direct API 매핑

```kotlin
val group = connection.leaderGroupElection(
    LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = 500.milliseconds,
        leaseTime = 10.seconds,
    )
)

val value = group.runIfLeader("thumbnail-workers") {
    processNextClaimedBatch()
}

val state = group.state("thumbnail-workers")
```

상태 카드는 `LeaderGroupState`와 그 파생 프로퍼티에 직접 매핑된다.

### Spring Boot 매핑

```kotlin
@LeaderGroupElection(
    name = "thumbnail-workers",
    maxLeaders = 3,
    waitTime = "PT0.5S",
    leaseTime = "PT10S",
)
fun processNextClaimedBatch(): BatchSummary? = service.processNextClaimedBatch()
```

차이 패널에는 다음 내용을 명시한다.

- synchronous, suspend, `Mono` 반환 타입을 지원한다.
- 슬롯별 stream lease 연장이 정의되지 않았으므로 `Flux`와 Kotlin `Flow`는
  거부된다.
- 그룹 옵션은 `autoExtend`를 제공하지 않는다.
- 필요한 경우 활성 락 연장 계약을 통해 명시적인 연장을 수행할 수 있지만,
  여기서는 시뮬레이션하지 않는다.

### 그룹 상태 모델

그룹 렌더러는 공통 상태를 다음 항목으로 확장한다.

```text
candidateCount
maxLeaders
slots[] { token, owner, acquiredAt, expiresAt }
activeCount
availableSlots
isFull
```

어떤 슬롯 인덱스도 안정적인 비즈니스 식별자로 노출하지 않는다.

## 로케일 동등성

영어와 한국어 페이지는 다음 항목을 공유한다.

- DOM 구조
- CSS 및 JavaScript 동작
- 컨트롤 범위와 시나리오 데이터
- API snippet 및 기술 식별자
- 접근 가능한 이름과 상태 의미
- 테마 동작

설명 문장과 UI 레이블만 다르다. 한국어 텍스트는 직역이 아니라 한국어
기술 문체로 작성한다. validator는 소스 동등 구조 마커, 동반 문서 ID,
컨트롤 ID, 시나리오 ID, 릴리스 메타데이터, 필수 기술 anchor를 검사한다.

## 시각 및 상호작용 설계

- inline CSS와 JavaScript만 사용하는 독립형 HTML
- 외부 font, image, script, stylesheet, fetch, module import를 사용하지 않음
- 한 열로 축소되는 반응형 2열 desktop 레이아웃
- blue, violet, cyan, amber, green, red를 절제된 의미 강조색으로 사용하는
  dark diagram 스타일
- `prefers-color-scheme`와 명시적인 auto/light/dark 컨트롤을 통한 테마 지원
- 상호작용 대상의 최소 크기 44px
- 포커스 표시와 키보드로 조작 가능한 컨트롤
- `prefers-reduced-motion`에서 동작을 비활성화하거나 축소
- 모든 색상에 상태, 소유권, 결과 레이블을 함께 표시
- full-size desktop 및 좁은 mobile 캡처에서도 카드와 타임라인 레이블이
  읽기 쉬워야 함

## Manifest 계약

`docs/visual-companions/manifest.json`에는 다음 항목을 포함한 두 개의
entry가 있다.

- 안정적인 companion ID
- 소스 저장소 및 릴리스 메타데이터
- 이슈 참조
- 영어 및 한국어 소스 경로
- 예정된 public route
- 로케일별 제목 및 요약
- manual 진입점
- fallback image 경로
- 필수 검증 anchor

manifest는 결정적이며 생성된 timestamp를 포함하지 않는다.

## 검증

### 구조 검증기

`scripts/validate-visual-companions.mjs`는 다음을 검증한다.

- manifest schema 및 고유 ID
- 릴리스 ref와 커밋 일관성
- 모든 로케일 소스 및 fallback 경로의 존재 여부
- 네트워크 기능이 가능한 markup 또는 외부 asset 참조의 부재
- 필수 로케일, 테마, 안내 단계, 시나리오, 컨트롤, API, Spring,
  접근성 anchor
- 단일 동반 문서의 경합, 만료/인수, 연장, 토큰, TTL, skip,
  `LeaderRunResult` 커버리지
- 그룹 동반 문서의 `maxLeaders`, 상태 필드, 포화, 작업 분배 경고 커버리지
- 소스 동등 영어/한국어 구조

Node 테스트에는 통과하는 저장소 fixture와 함께 로케일 파일 누락, 외부 asset,
anchor 누락, 중복 ID, 동등성 drift를 위한 제한된 negative fixture가 포함된다.

### Manual 검증

저장소 manual 시퀀스는 계속 릴리스 문서 게이트로 사용한다.

```bash
./gradlew exportManualModuleInventory
ruby scripts/manual/release_inventory.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017 build/manual/module-inventory.json build/manual/release-module-inventory.json 35
ruby scripts/manual/validate_manuals.rb build/manual/release-module-inventory.json
ruby scripts/manual/validate_release_manuals.rb 0.4.0 17ab7f872c1f96318c73d3580729cac20a67e017
ruby scripts/manual/export_manifest.rb --check
ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
```

### 브라우저 및 시각 검증

각 영어/한국어 페이지를 desktop 및 mobile 크기에서 다음 항목에 대해
테스트한다.

- console 또는 page error 없이 로드되는지
- 모든 안내 단계
- 모든 시나리오 preset
- play, pause, reset 및 컨트롤 변경
- 테마 선택
- 키보드 포커스
- 의미 있는 최종 상태와 결과 레이블

결정적인 시각 증거에는 고정 viewport, 고정 컨트롤 상태, 동작 축소, 로컬 파일
로드를 사용한다. 각 로케일/테마 캡처를 두 번 생성하고 크기와 hash가 일치해야
한다. 검토한 기본 상태의 2x PNG fallback은
`docs/manual/assets/visual-companions/` 아래에 커밋한다.

full-size 검토로 다음을 확인한다.

- 모든 카드 텍스트가 들어가는지
- 잘림이나 겹침이 없는지
- connector와 방향 표시가 계속 보이는지
- dark 및 light 대비를 읽을 수 있는지
- mobile 순서가 교육 흐름을 보존하는지

## Manual 통합

다음 영어·한국어 페이지에는 동반 문서 내용을 중복하지 않고 간결한
“Visual companion” 링크를 추가한다.

```text
docs/manual/en/core/single-group-strategic.md
docs/manual/ko/core/single-group-strategic.md
docs/manual/en/frameworks/spring-boot.md
docs/manual/ko/frameworks/spring-boot.md
```

core 페이지는 두 동반 문서에 연결하고 그룹 페이지를 단일 선출과의 차이로
설명한다. Spring 페이지는 두 동반 문서의 애너테이션 매핑 단계에 연결한다.
링크는 최종 public route를 사용하며, 릴리스 소스 링크는 manual 릴리스
커밋에 계속 고정한다.

## 위험 및 완화

| 위험 | 완화 |
| --- | --- |
| 시뮬레이션을 Redis protocol 문서로 오해함 | Redis 레코드를 개념적 표현으로 표시하고 백엔드 주장을 `0.4.0` Lettuce 구현에 연결한다. |
| Lease 만료를 비즈니스 exactly-once 안전성으로 오해함 | stale 작업 겹침과 지속적인 멱등성/fencing 경고를 보여 준다. |
| 그룹 슬롯을 작업 파티션으로 오해함 | 안정적인 숫자형 비즈니스 식별자를 피하고 작업 분배 경고를 계속 표시한다. |
| 영어와 한국어 동작이 달라짐 | 상태 marker를 공유하고 구조, 컨트롤, 시나리오, anchor를 검증한다. |
| 테마 또는 반응형 레이아웃이 회귀함 | 결정적인 desktop/mobile 매트릭스를 캡처하고 full-size 이미지를 검토한다. |
| 사이트 snapshot이 소스와 달라짐 | 정확한 병합 소스 커밋에서만 게시하고 사이트 snapshot 메타데이터에 기록한다. |

## 전달 경계

소스 PR은 manifest, 두 영어·한국어 동반 문서 쌍, fallback, validator
테스트, manual 링크, manual 검증, 브라우저 검사, 결정적 캡처, 최종 검토가
정확한 head에서 모두 통과하면 완료된다.

소스 PR은 새로운 정확한 head 승인 없이는 병합하지 않는다. 사이트 snapshot
게시 작업은 해당 병합 이후에만 시작하며 자체 PR과 병합 승인 게이트를 통해
전달한다.
