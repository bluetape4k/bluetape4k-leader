# #924 Redisson 비동기 정리 계획

승인 범위: develop → refactor/issue-924-redisson-async, PR 생성까지. Type B.
Redisson 4.7.0 dependencyInsight와 source JAR에서 native async API를 확인했다.

1. [완료] fixture 격리 후 원본 구현에서 barrier RED 3건을 재확인했다.
2. [완료] isHeldByThreadAsync와 expireAsync를 조합하고 거부 후 정리도 같은 경로로 바꿨다.
3. [완료] 회귀 10건, 전체 모듈 326건, detekt 및 ABI 16 artifact unknown 0 통과.
4. [진행 중] README 두 언어·교훈·직접 검토 완료. PR 생성 및 exact-head CI 확인이 남았다. 머지는 하지 않는다.

남은 최소 lease는 ownership 응답 뒤 기존 monotonic 기준으로 계산한다.
group permit 갱신과 single key expiry는 의미가 다르므로 공통 helper로 합치지 않는다.
새 executor·의존성·public API 변경은 없다. 동기 runIfLeader 정리는 변경하지 않는다.
정리 실패는 기존 로그 관측과 action 결과 우선 정책을 보존한다.

직접 설계 검토: 소스·callers·barrier 테스트·API·문서·CI·설계 위험 확인,
P0/P1=0 WATCH. 독립 reviewer 불가에 따른 승인된 대체이며 독립 PASS가 아니다.
SPW-01~05: 한국어, issue/source/version 근거, 계약·범위·실행 전 검증 순서 대조.
