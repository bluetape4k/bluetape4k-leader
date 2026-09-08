# #922 획득 오류 직접 검토

범위: `fix/issue-922-r2dbc-errors`, 기준 `ded0504b`, #922 구현 diff.
독립 reviewer timeout/thread 한계로 주 세션에서 검토했다. 설계 검토 대체는
사용자가 승인했다. 독립 검토 완료나 주 세션의 확인되지 않은 모델을 주장하지 않는다.

| 관점 | 증거와 판단 |
|---|---|
| 소스 | 두 tryLock의 catch만 좁히고 cause 보호를 공유한다. SQL과 해제 코드는 동일하다. |
| 호출자 | single tryLock과 group 슬롯 순회는 action/history 처리 이전이며 새 Error를 다시 삼키지 않는다. |
| 테스트 | 의도한 RED 5건 → 회귀 12건 PASS. DB별 전체 189건씩 PASS, callback 보상 정리 테스트 포함. |
| API/ABI | public 시그니처 변경 없음. ABI 16 artifact, 기존 synthetic ignore 1, unknown 0. |
| 문서 | README 두 언어의 오류 정책 일치. 계획·lesson에 그룹 unavailable 유지와 fixture 원인을 명시했다. |
| CI | 로컬 detekt/compile/test/ABI PASS. hosted exact-head CI는 PR 생성 후 확인해야 한다. |
| 설계 위험 | 중간 Error·취소와 cause 순환을 처리한다. backend 분류를 재사용한다. DB 자체 timeout은 드라이버 설정 영역이다. |

판정: 직접 검토 P0/P1=0, WATCH. 동기·async·virtual-thread API는 이 모듈이
제공하지 않으며 해당 구현을 변경하지 않는다. suspend 획득과 기존 watchdog·해제
회귀는 DB별 모듈 테스트로 확인했다. 머지 승인과 hosted CI는 이 검토로 대체하지 않는다.

SPW-01~05: 한국어, source/caller/test/ABI 직접 근거, 미실행 CI와 독립성 한계 구분,
계획·lesson·README와 대조 확인.
