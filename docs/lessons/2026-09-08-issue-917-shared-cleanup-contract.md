# #917: 운영 API를 늘리지 않고 cleanup 회귀 계약을 공유한다

## 배경과 결정

#914의 scheduler 수정이 MongoDB에 빠진 문제는 #916에서 먼저 수정했다.
네 backend는 Kotlin internal dispatcher와 backend별 callback·lease 소유권을 유지하되,
기존 leader-core testFixtures에 공통 계약 11개를 두고 실제 dispatcher adapter로 실행한다.
지원 API 추가나 source generation은 이번 계약 중복 제거보다 큰 배포·빌드 비용이어서 제외했다.
production clone은 남는다. 공통 테스트가 서로 다른 구현의 행동 차이를 발견하는 방어선이다.

## 검증과 놓치기 쉬운 점

- 공통 suite를 먼저 네 backend에서 11건씩 통과시킨 뒤 중복 테스트 13개를 제거했다.
- 전체 테스트: core 1045, Consul 171, etcd 173, Kubernetes 34, MongoDB 162.
  XML의 실패·오류·skip은 모두 0이며 각 adapter의 상속 테스트가 11건 실행됐다.
- 네 backend detekt와 checkBinaryCompatibility가 성공했다. production 코드·의존성·workflow 변경은 없다.
- prototype의 String 전용 경계 대신 실제 generic completeAfter 시그니처를 계획과 adapter에 맞췄다.
- 최종 안내 재독에서 base test의 PER_CLASS 표기를 보완하고 무제한 join을 2초 get으로 바꿨다.
  네 공유 suite 44건과 core/backend detekt, ABI를 다시 통과했다(16 artifacts, ignored 1, unknown 0).
  Gradle --tests는 detekt 뒤가 아니라 test 바로 뒤에 두어야 한다. 잘못 둔 명령은 실행 전 실패했고 바로 수정했다.
- 독립 native 검토가 반복 timeout/thread limit으로 불가능하여 승인된 직접 검토로 대체했다.
  이를 독립 검토 PASS나 모델 provenance 증거로 사용하지 않는다.

## 후속 작업의 경계

공통 테스트에서 fake cleanup 구현을 검사하지 말고 반드시 각 backend의 실제 dispatcher를 호출한다.
backend barrier 및 single/group lifecycle 테스트는 공통 dispatcher 테스트와 역할이 달라 보존한다.
새 계약은 네 adapter에서 실행되는 XML을 확인한 뒤 기존 테스트를 제거한다.
stacked PR의 base는 fix/issue-916-mongo-cleanup이다. develop/main 대상 자동 CI와 다르므로
체크 부재를 성공으로 바꾸지 않는다. 머지·retarget·workflow dispatch는 이번 전달 범위가 아니다.
GNO update는 성공했지만 현재 docs collection은 linked worktree를 포함하지 않으므로 새 문서 검색 증거는 아니다.

SPW-01~05: 유지보수자 대상, 이슈·diff·실행 결과에 근거, 한국어 용어와 숫자 대조,
증거와 한계를 분리, Markdown read-back 완료.
