# 문제 215-218 이력 검토 수정 사항

## 맥락

PR #214에는 리더 기록 감사 백엔드 및 래퍼가 추가되었습니다. 후속 검토에서는 Mongo 잠금 토큰 엔트로피, Exposed 기록 완료 소유권 검증, 일시 중지 전용 보존 자동 구성 및 테스트 어설션 스타일 드리프트의 네 가지 격차를 발견했습니다.

## 결정

- Mongo 잠금 토큰은 최소 128비트의 엔트로피를 보존하기 위해 22개의 Base58 문자를 사용해야 합니다.
- Exposed JDBC/R2DBC 기록 완료는 `id` 단독이 아닌 `id AND token`에 의해 업데이트되어야 합니다.
- 보존 자동 구성은 구체적인 선택기 인스턴스가 아닌 AOP 선택기 팩토리 및 싱크를 기준으로 해야 합니다.
- 반응형 예약 메서드는 Spring의 시작 시 `@Scheduled` 게시자 검사 중에 AOP 메타데이터/공장 검증을 수행하지 않아야 합니다.

## 결과

Mongo 토큰 길이, Exposed 토큰 불일치 업데이트에 대한 회귀 테스트를 추가하고 `LeaderElector` Bean을 차단하지 않고 보존 등록을 일시 중지합니다. `kotlin.test` 어설션에서 터치 기록 테스트를 마이그레이션했습니다.

Claude 검토에서는 첫 번째 통과 후 하나의 유효한 차단기를 발견했습니다. 정지 보존 래퍼는 프로덕션 코드에서 `runBlocking`를 잠시 사용했습니다. 허용되는 수정 사항: `@Scheduled` 래퍼 분할을 유지하지만 보호된 메서드가 `mono(Dispatchers.IO) { ... }.then()`를 반환하도록 합니다. Claude는 또한 `SuspendLeaderElectorFactory` 조건 삭제를 제안했지만 현재 `LeaderElectionAspect` Mono 분기가 `SuspendLeaderElectorFactory`를 검증하고 사용하기 때문에 거부되었습니다.

## 검증

- `./gradlew --no-daemon :leader-core:test :leader-micrometer:test :leader-mongodb:test :leader-exposed-jdbc:test :leader-exposed-r2dbc:test :leader-spring-boot:test`
- `./gradlew --no-daemon :leader-spring-boot:compileKotlin`
- IDE 진단: 보고된 빌드 오류가 없습니다.

## 퓨쳐 가드

`Mono`를 반환하는 `@Scheduled` 메서드를 검토할 때 `ApplicationContextRunner`를 사용하여 시작 동작을 검증하세요. Spring은 게시자를 얻기 위해 컨텍스트 초기화 시 반응형 예약 메서드를 한 번 호출합니다. 예정된 코루틴 보존 작업을 연결하기 위해 `runBlocking`를 사용하지 마세요. 지연된 Reactor 브리지를 사용하고 일정 계약에서 요구하는 경우 외부 `@Scheduled` 플랫폼 스레드 경계에서만 계속 차단합니다.
