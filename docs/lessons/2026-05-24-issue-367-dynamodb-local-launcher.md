# 문제 367 DynamoDB 로컬 실행기

## 맥락

`leader-dynamodb`는 공유 `DynamoDbLocalServer` 고정 장치가 `bluetape4k-testcontainers`에 추가된 후에도 개인 `DynamoDbLocalContainer`를 탑재했습니다.

## 결정

`1.9.2-SNAPSHOT` bluetape4k 카탈로그를 사용하고 통합 테스트 베이스에서 `DynamoDbLocalServer.Launcher.dynamoDb`를 사용하세요.

## 결과

리더 저장소는 더 이상 DynamoDB 로컬 이미지, 포트 및 명령 배선을 복제하지 않습니다. 이제 테스트 수명 주기는 싱글톤 컨테이너가 `ShutdownQueue`에 등록되고 클라이언트 리소스가 리더 테스트의 소유로 유지되는 공유 실행 프로그램 패턴을 따릅니다.

루트 빌드가 이미 `resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)`를 설정했기 때문에 임시 `1.9.2-SNAPSHOT` 카탈로그는 이 분기에 허용됩니다. 구현 시 일치하는 `bluetape4k-exposed 1.9.2-SNAPSHOT`가 존재하지 않았으므로 Exposed 리더 테스트 소스를 현재 카탈로그 쌍으로 컴파일하여 Exposed 모듈 바이너리 호환성을 검증했습니다. `DynamoDbLocalServer`는 여전히 `amazon/dynamodb-local:2.6.1`를 고정하여 대체하는 개인 컨테이너와 일치합니다.

## 검증

- `./gradlew :bluetape4k-leader-dynamodb:compileTestKotlin --refresh-dependencies --no-configuration-cache`
- `./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorIntegrationTest' --no-configuration-cache`
- `./gradlew :bluetape4k-leader-exposed-core:compileTestKotlin :bluetape4k-leader-exposed-jdbc:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin --no-configuration-cache`

## 퓨쳐 가드

개인 테스트 컨테이너를 공유 실행기로 바꾸기 전에 스냅샷 또는 릴리스 아티팩트에 새 도우미 클래스가 포함되어 있는지 검증하세요. 최신 개발 병합이 게시되기 전에 스냅샷 메타데이터가 존재할 수 있습니다.

다음 리더 릴리스를 자르기 전에 `bluetape4k`를 `1.9.2-SNAPSHOT`에서 해당 GA 카탈로그에 다시 고정하고 일치하는 버전이 있으면 `bluetape4k-exposed`를 정렬합니다.
