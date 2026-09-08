# #919 nullable 결과 관측 수정 계획

승인: develop(ded0504b) → fix/issue-919-batch-result, PR 생성까지만.

1. [완료] 실제 Redis와 logger로 elected-null 로그 RED 및 interrupt flag 누락 RED를 확인했다.
2. [완료] 기존 runIfLeaderResult/LeaderRunResult 재사용과 ActionFailed.cause 재throw를 적용했다.
3. [완료] 실제 경합·일반 오류·취소·interrupt 및 오류 후 재획득을 검증했다.
4. [진행 중] 전체 14건 및 detekt, README 영/한·KDoc·교훈과 직접 검토 완료. PR·CI는 다음 단계다.

반환형 T?는 유지하므로 반환값만으로 elected-null/skip을 구분할 수 없다는 한계는 문서화한다.
새 결과 wrapper·의존성·production logger API를 추가하지 않는다. 로그 캡처는 기존 Logback만 사용한다.
테스트 실패 시 해당 계약부터 재검증하고, 되돌리기는 examples/batch-scheduler 및 task 문서로 제한한다.
SPW-01~05: 한국어 유지보수자 문서, #919 및 실제 core API 대조, 검증·한계·read-back 확인.
