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

## 구현 검증 및 최종 직접 검토

core fixture → Consul → etcd → Kubernetes → MongoDB 순서로 diff와 실제 adapter 호출을 검토했다.
각 adapter는 backend internal completeAfter만 호출하며 production 알고리즘을 복제하지 않는다.
기존 backend barrier·single/group 획득/취소/제출 실패 회귀는 보존했다.

| 관점 | 구현 근거와 판정 |
|---|---|
| 성능 | production 변경 0, dependency 변경 0. 테스트만 공유하므로 운영 성능 영향 없음. |
| 안정성 | 공유 11×4건 및 backend 전체 540건 PASS. core 1045건 PASS. 오류·skip 0. |
| 보안 | 테스트의 고정 오류 문자열만 사용, 인증·secret·권한 변경 없음. |
| Ops | 무승인 dispatch/merge/cleanup 없음. stacked CI 한계는 PR에 공개한다. |
| API | checkBinaryCompatibility PASS. production 공개 선언·배포 의존성 불변. testFixtures 지원 경계만 확장. |
| caller | source 취소와 result 취소를 별도 검사. 원래 오류 우선, self-suppression, 중복 실행을 검사. |
| 통합 | spec 조건은 공유 계약과 보존된 lifecycle 검증에 매핑된다. production clone 잔존은 의도된 한계. |

A-VER-01~07: 승인된 spec/plan 존재, 수용 조건별 파일·테스트 대응, 범위 일치,
회귀·정적 검사 및 ABI 성공, 운영 문서 계약 불변, 순차 검증과 되돌리기 경계 확인.
최종 P0=0/P1=0, WATCH — 독립 검토는 수행 불가, 직접 검토 결과이며 CI는 별도 확인한다.
SPW-01~05/KO-01~07: 한국어 검토 결과, 실행 숫자·source 대응, 한계 명시 및 read-back 완료.

SPW-01~05/KO-01~07: 유지보수자 대상 한국어, source/issue와 비교, 승인 범위·독립성·CI 한계를 명시,
spec/plan/review Markdown read-back 및 용어 검사를 수행한다. 구현 후 최종 결과는 별도로 추가한다.
