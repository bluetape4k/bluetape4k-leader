# 224호 리스닝 이벤트 게시자

## 맥락

`ListeningLeaderElector` 및 `ListeningLeaderGroupElector`를 차단하면 콜백 리스너가 노출되지만 `LeaderElectionEventPublisher`는 노출되지 않으므로 코루틴 소비자는 `Flow`를 통해 차단 선거 수명 주기 이벤트를 관찰할 수 없습니다.

## 결정

차단 리스너 데코레이터 내에서 `MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)`를 사용하세요. `tryEmit`로 내보내므로 차단 및 가상 스레드 호출자는 `runBlocking`를 일시 중지하거나 필요로 하지 않습니다.

## 결과

이제 두 차단 수신기 데코레이터 모두 `LeaderElectionEventPublisher`를 구현합니다. 동기화 및 비동기 경로는 동기화 및 비동기 작업 failure 취소 이벤트를 포함하여 `Elected`, `Revoked` 및 `Skipped` 이벤트를 내보냅니다.

## 검증

- IDE 진단: 접촉된 Kotlin 파일에 오류가 없습니다.
- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-configuration-cache --console=plain`
- 결과: 24개 테스트를 통과하고 빌드에 success했습니다.
- PR Claude 이후 피드백에는 동기화 작업 failure 범위, 공유 버퍼 용량 상수 및 KDoc 주문 콜백 대 Flow가 추가되었습니다.

## 미래 노트

차단 API에 이벤트 게시자를 추가할 때 버퍼링/삭제 의미 체계를 문서화하고 비동기 success, 건너뛰기 및 failure 경로를 다룹니다. 호출 경로를 차단하는 일시 중단 전용 이벤트 도우미를 사용하지 마세요.
