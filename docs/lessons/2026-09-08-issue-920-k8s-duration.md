# #920: Duration 정책을 재사용하되 정밀도를 잘라내지 않는다

양수 검증만으로 요청의 양수 초를 보장할 수 없다. seconds.toInt는 500ms를 0초로 만들고
Int 상한 초과도 잘라낸다. mock create/update 및 기간 누락 fallback에서 총 8건 RED를 확인했다.
초기 mock 테스트의 Called import 누락은 컴파일 오류로 구분하고 수정 후 행동 RED를 확인했다.

기존 leader-k8s의 양수·올림·Int 상한 정책을 따르되, 이 예제의 Java Duration은
seconds/nano로 정확히 올림한다. milliseconds로 내렸다 올리면 1s+1ns를 잃을 수 있다.
상한 검증을 더하기/Int 변환 전에 하여 Long.MAX_VALUE초 같은 값도 안전하게 거부한다.
하나의 private 변환값을 create/update/만료 fallback에서 재사용한다.

## 검증 및 직접 검토

- 일반 test 13건, detekt PASS. 경계 입력 12건은 mock 요청 및 client 호출 전 검증을 포함한다.
- 별도 k8sTest 2건 PASS: K3s 실제 500ms create/update가 1초로 저장되며 기존 획득·경합·해제도 통과한다.
- API 시그니처·의존성·배포 library ABI 변경 없음. 저수준 Fabric8 학습 예제를 전체 elector로 바꾸지 않는다.
- 직접 코드·설계 검토 P0/P1=0 WATCH. 승인된 inline fallback이며 독립 native lane PASS는 아니다.
- 기존 backend의 sub-millisecond 구현 자체는 이번 예제 범위에서 수정하지 않는다.
- GNO update와 worktree lesson 검색 노출은 별도다. 일반 CI의 test 성공을 K3s 실행 증거로 바꾸지 않는다.

SPW-01~05/KO-01~07: #920·backend·실행 근거, 한국어 본문, README 영/한 및 KDoc 대조, read-back 완료.
