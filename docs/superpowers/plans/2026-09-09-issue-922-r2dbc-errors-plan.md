# #922 R2DBC 획득 오류 정책 계획

승인: develop(ded0504b) → fix/issue-922-r2dbc-errors, PR 생성까지만.

| 입력 | single | group |
|---|---|---|
| CancellationException | 즉시 원본 전파 | 즉시 원본 전파 |
| JVM Error 및 wrapper 내부 Error | 원본 Error 전파, 재시도 없음 | 원본 Error 전파, unavailable 변환 없음 |
| transient DB 오류 | 기존 monotonic wait budget 안에서 재시도, 소진 시 false | 기존 unavailable/null, 슬롯 순회 중단 |
| non-transient 및 미분류 Exception | 재시도 없이 false | 기존 unavailable/null |
| 정상 contention | 기존 budget 재시도 후 false | 기존 budget 재시도 후 false |
| availability callback 오류 | 해당 없음 | 기존 원본/suppressed 및 보상 해제 계약 유지 |

1. [완료] 실제 tryLock의 transaction 실패 주입으로 의도한 RED 5건을 확인했다. fixture 실패는 별도로 수정했다.
2. [완료] 기존 분류기를 재사용하고 fatal/cancel cause를 반환 정책보다 먼저 처리했다.
3. [완료] 회귀 12건, H2/PostgreSQL/MySQL 각각 189건 및 detekt/ABI를 순차 통과했다.
4. [진행 중] README 두 언어·교훈·직접 검토를 마쳤다. commit/PR와 정확한 head CI는 다음 단계다.

getMostSpecificCause는 최심부만 돌려주어 중간 Error나 cancellation을 놓칠 수 있다.
분류 로직을 복제하지 않고 cause 순회의 fatal 보호만 더한다. 기존 public constructor나 반환형을 확장하지 않는다.
새 dependency/DB unavailable 계약 제거/전체 예외 throw 정책은 범위 밖이다.
실패 시 해당 정책 회귀부터 재실행하며 callback 보상 정리 로직은 변경하지 않는다.
SPW-01~05: 한국어, #922/source/classifier 근거, 정책 표·검증 범위·read-back 확인.
