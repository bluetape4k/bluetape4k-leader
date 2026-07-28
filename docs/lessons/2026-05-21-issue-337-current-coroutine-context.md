# 문제 337 현재 코루틴 컨텍스트 활성

## 맥락

`WebhookPoller.runLoop()`는 `kotlin.coroutines.coroutineContext[Job]?.isActive != false`를 통해 루프 활성 상태를 검증했습니다. 해당 속성은 일시 중지 코드에서 유효하지만 가져오기를 놓치기 쉽고 저장소의 설정된 일시 중지 루프 스타일과 다릅니다.

## 결정

일시 중지 루프 활성 검증에는 `currentCoroutineContext()[Job]?.isActive != false`를 사용합니다. 일반 예외 처리기 전에 `CancellationException` 다시 발생을 유지하여 취소 시 폴러가 즉시 종료되도록 합니다.

## 결과

webhook-poller 예제는 이제 `kotlinx.coroutines.currentCoroutineContext`를 가져와 `runLoop()`에서 사용합니다. 일반 catch 블록의 오래된 `null` 표현식도 효과가 없기 때문에 제거되었습니다.

## 검증

웹후크 폴러 대상 테스트를 실행하고 이전 `coroutineContext[Job]?.isActive != false` 활성 패턴이 없음을 증명하는 저장소 검색을 실행합니다.

## 향후 지침

bluetape4k 코드의 일시 중단 루프의 경우 컨텍스트 요소를 읽거나 `ensureActive()`를 호출할 때 `currentCoroutineContext()`를 선호합니다. 소스 모듈에 이미 하위 수준 속성이 필요한 좁은 경우에는 원시 `kotlin.coroutines.coroutineContext` 사용을 유지하세요.

## 후속 조치

PR #338 이후 Claude CLI 검토에서는 루프 상단에서 `currentCoroutineContext().ensureActive()`를 사용하여 취소 검증을 명시적으로 만드는 것이 좋습니다. 폴링 루프의 경우 종료 전에 정리가 필요하지 않은 경우 수동 `Job.isActive` 루프 조건보다 양식을 선호합니다.
