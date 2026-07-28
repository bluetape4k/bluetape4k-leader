# 237호 StateFlow 스타트업 레이스

## 맥락

`leaderStateFlow()`는 수집기가 비동기적으로 시작되는 `stateIn(SharingStarted.Eagerly)`를 사용했습니다. `replay = 0`를 사용하는 핫 이벤트 게시자는 `leaderStateFlow()`가 반환된 직후에 발생하고 수집기가 구독하기 전에 이벤트를 잃을 수 있습니다.

## 결정

기본 Eager 경로의 경우 `MutableStateFlow`를 생성하고 `CoroutineStart.UNDISPATCHED`를 사용하여 업스트림 컬렉션을 시작합니다. 기존 `stateIn()` 경로에 열의가 없는 `SharingStarted` 전략을 유지하세요.

## 결과

열정적 수집기는 `leaderStateFlow()`가 반환되기 전에 구독하는 반면 공개 API 형태와 취소 동작은 변경되지 않습니다. 이는 첫 번째 일시 중단 지점 전에 동기적으로 구독을 등록하는 `MutableSharedFlow.collect()`에 의존합니다. 구독 등록에 동일한 속성이 없는 한 사용자 정의 `Flow` 구현에 동일한 미디스패치 시작 패턴을 적용하지 마세요.

## 검증

- 이제 테스트에서는 `MutableSharedFlow(replay = 0)`를 사용하며 사전 구독 지연이 없습니다.
- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest' --no-configuration-cache --console=plain`
- 결과: 10개의 테스트를 통과하고 빌드에 success했습니다.
- PR 이후 Claude 피드백은 열성적이지 않은 `SharingStarted` 적용 범위를 추가하고 `SharingStarted.Eagerly` 싱글톤 검사를 문서화했으며 `MutableSharedFlow` 구독 전제 조건을 포착했습니다.

## 미래 노트

핫 플로우 스타트업 레이스를 테스트할 때 재생 버퍼와 인위적인 구독 지연을 제거하세요. 방출 후 즉시 `StateFlow.value` 업데이트를 가정하는 대신 `first { ... }`를 사용하여 반응적으로 상태 변경을 기다립니다.
