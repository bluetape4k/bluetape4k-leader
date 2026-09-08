# #917 설계·계획 검토

기준 `1c26c291`, 이 branch의 spec/plan을 대상으로 작성자 검토했다. native 검토가 앞선 세 작업에서
thread limit/응답 기한 초과로 반복 실패하여 현재 여섯 관점도 직접 검토한다. 독립 lane PASS로 기록하지 않는다.
이 문서는 사용자 승인된 직접 설계 검토와 workflow inline fallback의 실제 수행 근거다.

| 관점 | 설계 및 계획 판정 |
|---|---|
| 성능 | production 변경 없음. 테스트의 latch 대기는 상한을 두고 네 backend 검증은 순차 실행한다. |
| 안정성 | 같은 테스트를 네 dispatcher에 적용하며 원래 오류/이중 실패/취소/중복 실행을 확인한다. backend barrier는 유지한다. |
| 보안 | 운영 설정·인증·네트워크 노출 변경 없음. 테스트에 secret을 넣지 않는다. |
| Ops | thread 이름/소유권 불변. stacked base CI 부재를 PENDING으로 공개하고 retarget/dispatch는 수행하지 않는다. |
| API | core testFixtures만 추가. public production API/의존성 확장 방지. adapter가 실제 internal dispatcher를 호출하는지 확인한다. |
| caller | 정상/취소/제출 실패의 backend별 기존 통합 테스트 유지. source cancellation과 result cancellation을 구분한다. |
| 통합 | 이슈가 허용한 공유 contract test 수렴을 선택했다. production clone을 없앴다고 주장하지 않는다. 중복 scheduler 테스트 삭제는 공통 suite GREEN 뒤 수행한다. |

설계·계획 단계 P0=0/P1=0, WATCH. 공개 API 도입과 source generation은 재사용 목적보다 큰 비용이므로 제외한다.
spec 수용 조건은 계획의 suite/adapter, 기존 lifecycle 유지, 순차 검증, ABI/PR 단계에 대응한다.
fixture 클래스가 테스트 상속으로 실제 실행되는지는 구현 후 XML에서 확인한다.

SPW-01~05/KO-01~07: 유지보수자 대상 한국어, source/issue와 비교, 승인 범위·독립성·CI 한계를 명시,
spec/plan/review Markdown read-back 및 용어 검사를 수행한다. 구현 후 최종 결과는 별도로 추가한다.
