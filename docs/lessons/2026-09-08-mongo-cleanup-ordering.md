# MongoDB 비동기 cleanup의 완료 순서

## 맥락과 원인

#916은 `AsyncLeaseCleanupDispatcher`의 scheduler 예외 처리와 single/group action 제출 실패를 다룬다.
`RejectedExecutionException`만 처리하면 다른 scheduler 예외가 callback 밖으로 빠져나가고,
별도로 만든 결과 future는 완료되지 않는다. 두 scheduler가 모두 거부할 때 inline cleanup을 실행하면
호출자의 완료 스레드를 blocking IO로 점유한다.

획득 future에 `whenComplete`와 `thenComposeAsync`를 나란히 등록한 코드에도 순서 문제가 있었다.
등록 순서가 실행 순서라는 가정 때문에 action 제출 거부를 처리할 때 획득 기록이 아직 없을 수 있었다.
이 경우 실패 결과를 먼저 반환하고 늦게 cleanup을 시작했다.

## 결정과 재사용 경계

Consul·etcd·Kubernetes의 기존 scheduler fallback 정책을 MongoDB에도 적용한다.
모든 dispatch 예외에서 fallback을 시도하고, fallback까지 실패하면 inline 실행 없이 결과를 실패로 완료한다.
원래 오류를 우선하며 추가 오류는 suppressed 예외로 보존한다. 이중 dispatch 실패는 실제 unlock의 증거가 아니다.

획득 기록 `whenComplete`가 반환한 future에 action 제출을 연결하여 선후 관계를 명시한다.
취소는 기존 acquisition future에 전파하고, 정상 경합의 `null` 계약과 공개 API를 유지한다.
새 barrier 추상화는 이 문제에 필요하지 않아 추가하지 않았다. 모듈 간 공통화는 #917에서 별도로 검토한다.

## 검증과 발견

- 최초 scheduler 회귀 테스트: `TimeoutException`으로 RED를 확인했다.
- single/group에 획득·cleanup latch를 둔 테스트: cleanup 중 결과가 이미 완료되어 두 경로 모두 RED였다.
- 수정 후 두 테스트 클래스 9개가 통과했다.
- MongoDB 전체 158개 테스트와 모듈 detekt를 포함한 Gradle 실행이 성공했다.
- ABI 검사도 16개 산출물에서 미분류 오류 0개로 통과했다. 실제 PR head CI는 아직 실행하지 않았다.

## 재발 방지

같은 future에 등록한 형제 callback 사이의 실행 순서에 의존하지 않는다. 앞 단계의 완료가 필요하면
그 단계가 반환한 future에 다음 단계를 연결한다. cleanup 시작뿐 아니라 완료 전 결과 상태를 latch로 검증한다.
`cancel()`의 즉시 완료와 실제 자원 정리 완료는 구분한다.

구현 위임에서는 실행 중이라는 상태만으로 진척을 추정하지 않는다. 이번에는 담당 응답이 없어
중단 후 직접 이어받았고, 중단 직후 도착한 dispatcher 변경도 다시 읽고 검증했다.
이후에는 진행 근거와 command deadline을 실제 UTC 시각으로 기록하고, 중단 후 diff를 다시 수집한다.

독립 검토를 실행하지 못하면 검토 성공으로 기록하지 않는다. 이번에는 코드 리뷰가 응답 기한을 넘겼고
architect 실행은 thread limit으로 실패했다. 사용자가 직접 설계 검토 대체를 승인한 뒤 작성자 검토로
진행했다. 다음 작업에서도 실행 실패, 대체 승인, 실제 검토 주체와 남은 CI 검증을 각각 기록한다.
