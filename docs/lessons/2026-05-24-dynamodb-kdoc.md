# DynamoDB 공개 API KDoc

컨텍스트: Issue #365에서는 DynamoDB 팩토리 클래스 및 클라이언트 확장 기능에서 공개 KDoc가 누락된 것으로 검증되었습니다.

결정: 간단한 요약, 행동/계약 섹션 및 최소한의 사용 예가 포함된 영어 공개 KDoc를 추가합니다. 차단, 비동기, 일시 중단 및 가상 스레드 도우미에 대해 건너뛰기 시 null 계약을 일관되게 문서화합니다.

결과: 이제 DynamoDB 공개 팩토리 및 확장 API는 작업이 실행되는 시기와 `null`가 반환되는 시기를 설명합니다.

검증: `./gradlew :bluetape4k-leader-dynamodb:compileKotlin`; `git diff --check`.

미래 보호: 새로운 DynamoDB 공공 도우미는 선출된 결과 계약과 KDoc의 건너뛰기 동작을 명시해야 합니다.
