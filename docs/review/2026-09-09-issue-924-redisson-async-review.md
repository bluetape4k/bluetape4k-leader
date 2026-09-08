# #924 native async 정리 직접 검토

기준 ded0504b, refactor/issue-924-redisson-async의 diff를 직접 검토했다.
독립 reviewer 실행 불가와 승인된 직접 설계 검토 대체를 적용했다.
주 세션의 확인되지 않은 모델이나 독립 검토 PASS를 주장하지 않는다.

| 관점 | 근거와 판단 |
|---|---|
| 소스 | ownership 응답 뒤 remainingMinLeaseTime 계산, native expiry/unlock future 연결. 동기 wrapper 2곳 제거. |
| 호출자 | action 완료 정리와 획득 후 executor 거부 정리가 같은 private helper를 사용한다. 획득 threadId를 그대로 전달한다. |
| 테스트 | 원본 RED 3건, 회귀 10건 및 실제 Redis 전체 326건 PASS. 취소·거부·minLease·watchdog 기존 회귀 포함. |
| API/ABI | private 구현만 수정. ABI 16 artifact, 기존 synthetic ignore 1, unknown 0. |
| 문서 | single/group best-effort와 로그 관측, 최소 lease TTL 경합 가능성 및 비변경 범위를 영/한 README에 명시했다. |
| CI | compile/test/detekt/ABI 로컬 PASS. hosted exact-head CI는 PR 후 확인한다. |
| 설계 위험 | ownership 확인과 expiry의 원자성은 기존과 같다. 새 executor·dependency 없음. group 초기화/audit 및 sync/suspend API는 범위 밖이다. |

판정: P0/P1=0 WATCH. cleanup future 실패가 action 결과를 덮지 않는 기존
정책을 유지한다. 독립성 한계와 hosted CI/머지 승인은 별도 항목이다.
SPW-01~05: 한국어, 코드·호출자·검증 근거, source JAR 버전 확인,
문서·계획·lesson 일치 및 미실행 검증 구분.
