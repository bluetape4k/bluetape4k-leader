## 맥락

Issue #175에서는 `CaptureScope.runWithCaptureSuspend`가 일시 중지 작업 주변에서 `ThreadLocal` 캡처를 사용했다고 보고했습니다. 디스패처 홉은 다른 캐리어 스레드에서 정리를 재개하여 원래 스레드에 오래된 핸들을 남길 수 있습니다.

## 결정

suspend 그룹 선출은 더 이상 `CaptureScope`, `AopScopeAccess.setCapture` 또는 `LeaderLockHandleCapture`를 사용하지 않습니다. 코루틴 컨텍스트에서는 `LockHandleElement`에만 의존합니다. `CaptureScope`는 이제 동기화 전용이며 테스트에서는 동기 ThreadLocal 캡처만 다룹니다.

## 결과

`LocalSuspendLeaderGroupElector`는 `withContext(LockHandleElement(handle))` 내에서 직접 일시 중지 작업을 호출합니다. 백엔드 정지 그룹 선출기는 이제 `withContext(AopScopeAccess.createLockHandleElement(handle))`만 사용합니다. 새로운 스트레스 테스트는 IO 및 기본 디스패처 홉을 검증하고 `LeaderLockHandleCapture.poll()`가 null로 유지되는지 검증합니다.

## 검증

- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.internal.CaptureScopeTest' --tests 'io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElectorCaptureTest' --console=plain`
  - 5개의 테스트를 통과했습니다.
- `./gradlew :leader-core:test --console=plain`
  - 605개의 테스트를 통과했습니다.
- Claude Tier 4 Advisor 검토에서는 백엔드 suspend 그룹 선출기에서 동일한 ThreadLocal 주변 일시 중지 패턴을 발견했습니다. 결과가 승인되고 수정되었습니다.
- `./gradlew :leader-core:test :leader-spring-boot:compileKotlin :leader-redis-lettuce:compileTestKotlin :leader-redis-redisson:compileTestKotlin :leader-mongodb:compileTestKotlin :leader-hazelcast:compileTestKotlin :leader-zookeeper:compileTestKotlin :leader-exposed-r2dbc:compileTestKotlin --console=plain`
  - 빌드 성공 `leader-core` 605 테스트를 통과했습니다.
- PR 생성 후 7-R단계 이중 PR 검토가 실행되었습니다.
  - Codex PR 검토: 승인, P0/P1/P2/P3 결과 없음.
  - Claude PR 검토: Spring AOP 검증 누락에 대한 초기 의견, 그 다음
`./gradlew :leader-spring-boot:test --console=plain`가 280개의 테스트를 통과하고 `pollCapture`가 suspend 그룹 측면 런타임 경로에 없는 것으로 검증된 후 승인됩니다.
  - GitHub CI는 녹색이었고 병합 상태는 깨끗했습니다. PR은 초안으로 남아 있습니다.

## 향후 지침

선출기를 일시 중단하기 위해 ThreadLocal 캡처 도우미를 추가하지 마세요. 일시중단 잠금 핸들 전파에는 `LockHandleElement`를 사용하고 동기 선출기용으로만 `CaptureScope.runWithCapture`를 예약하세요.

7-R단계가 필요한 경우 간결한 PR 댓글과 공식 GitHub 리뷰 항목을 모두 남겨주세요. 단순한 문제 의견은 유용한 증거이지만 PR 검토 일정을 채우지는 않습니다.
