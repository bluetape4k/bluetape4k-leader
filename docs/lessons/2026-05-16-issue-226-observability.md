# 이슈 226 리더 관찰성

## 맥락

Spring Boot 및 Ktor 사용자에게는 알려진 리더 선출 잠금에 대한 옵트인 상태 엔드포인트와 Spring `LeaderElectionEventPublisher` Bean 표면이 필요했습니다.

## 결정

백엔드 잠금을 열거하는 대신 JVM 로컬 잠금 이름 레지스트리와 엔드포인트 계층을 추가하세요. 정적 잠금 이름은 레지스트리를 시드하고 수신기 인식 선출기 또는 Ktor `leaderScheduled()` 호출은 관찰된 이름을 추가할 수 있습니다.

Spring의 경우 `LeaderElectionEventPublisher` Bean이 없으면 게시자 전용 수신기 어댑터를 노출합니다. `LeaderElector` 주입을 모호하게 만들고 기존 자동 구성 테스트를 중단시킬 수 있으므로 `ListeningLeaderElector` 폴백을 자동 연결 후보로 노출하지 마세요.

## 결과

- Spring은 `LeaderElectionObservabilityAutoConfiguration`를 추가하고 `LeaderElectionActuatorAutoConfiguration`를 선택합니다.
- Ktor는 옵트인 `GET /management/leaderElection`를 추가합니다.
- 두 표면 모두 `auditLeaderId` 및 `leaseUntil`를 사용하여 잠금별 단일 리더 상태를 반환합니다.

## 검증

- `./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' --no-configuration-cache --console=plain`
- `./gradlew :leader-ktor:test --tests 'io.bluetape4k.leader.ktor.LeaderElectionManagementRouteTest' --no-configuration-cache --console=plain`
- `./gradlew :leader-spring-boot:test :leader-ktor:test --no-configuration-cache --console=plain`
- 결과: `leader-ktor` 16 테스트 및 `leader-spring-boot` 293 테스트를 통과했습니다. 전체 실행은 기존 종료/로깅 스레드 경고를 인쇄했지만 성공적으로 완료되었습니다.

## 미래 노트

잠금 열거를 명시적으로 유지하거나 관찰합니다. 향후 문제에 완전한 백엔드 잠금 검색이 필요한 경우 상태 쿼리에서 이름을 유추하는 대신 백엔드별 목록 계약을 추가하세요. PR 이후 피드백에서는 Spring Actuator 경로가 HTTP 계층에서 다루어져야 하고 Ktor 관리 경로가 기본 애플리케이션 포트를 공유하기 때문에 명시적인 인증 경계 문서가 필요하다는 것을 검증했습니다.
