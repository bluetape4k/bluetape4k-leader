# #920 Lease 기간 변환 수정 계획

승인: develop(ded0504b) → fix/issue-920-k8s-duration, PR 생성까지. 외부 dispatch/머지 제외.

1. [완료] import 컴파일 오류를 고친 후 행동 RED 8건을 확인했다.
2. [완료] 생성 시 검증과 seconds/nano 올림값을 세 경로에 재사용했다.
3. [완료] 일반 test 13건/detekt 및 별도 K3s k8sTest 2건 PASS.
4. [진행 중] README 영/한·KDoc·교훈 및 직접 검토 완료. 커밋·PR/CI는 다음 단계다.

기존 backend의 양수·올림·Int 상한 정책을 재사용하되 Java/Kotlin Duration의 정밀도 차이를 보존한다.
milliseconds 변환은 1s+1ns를 잘라낼 수 있으므로 nanos 필드를 직접 사용한다.
leader-k8s 의존성·public helper를 추가하지 않고 Fabric8 저수준 학습 범위를 유지한다.
문제가 생기면 해당 경계 테스트부터 다시 검증하며 되돌리기는 이 예제 및 task 문서로 제한한다.
SPW-01~05: 한국어, issue/backend/소스 대조, 정밀도·검증 범위 및 read-back 확인.
