# 백엔드 네임스페이스 안전성 검토

문제: #579 마일스톤: 0.5.0

## 범위

- `bluetape4k-leader-mongodb`
- `bluetape4k-leader-dynamodb`
- `bluetape4k-leader-redis-lettuce`
- `bluetape4k-leader-redis-redisson`
- `bluetape4k-leader-hazelcast`
- `examples/ktor-app`
- `examples/prometheus-dashboard`

## 7계층 조사 결과

### 1단계 - 정확성

- Mongo 기록 수집 네임스페이스 및 TTL은 구성 생성 시 유효성이 검사됩니다.
- DynamoDB 테이블 이름과 키 접두사는 키 구성 또는 클라이언트 호출 전에 검증됩니다.
- Redis 및 Hazelcast 단일 선출 잠금 이름은 이제 백엔드 호출 전에 핵심 `validateLockName` 계약을 공유합니다.

### 계층 2 - 보안

- Redis URL 로깅은 더 이상 `REDIS_URL`의 자격 증명을 노출하지 않습니다.
- Prometheus 대시보드는 기본적으로 원시 레이블 대신 `REDACT` 잠금 이름 태그를 사용합니다.
- Lettuce 슬롯 그룹 잠금 이름을 통한 Redis 해시 태그 조작은 키 생성 전에 거부됩니다.

### 계층 3 - 동시성

- 잠금 또는 코루틴 소유권 의미가 변경되지 않았습니다. 백엔드 잠금을 획득하기 전에 유효성 검사가 수행됩니다.

### 계층 4 - API

- 기존 공개 API는 유지됩니다.
- 잘못된 네임스페이스 값은 이제 `IllegalArgumentException`에서 조기에 실패합니다.

### 계층 5 - 관찰 가능성

- 측정항목 태그 기본값은 테넌트/사용자 파생 잠금 이름에 대해 더 안전합니다.
- Redis 자격 증명을 수정하는 동안 시작 로깅은 여전히 유용합니다.

### 계층 6 - 테스트

- MongoDB 및 DynamoDB 옵션에 대한 순수 검증 테스트를 추가했습니다.
- Lettuce, Redisson 및 Hazelcast에 대한 백엔드 경계 검증 테스트를 추가했습니다.
- Redis URL 수정 및 Prometheus 잠금 이름 태그 기본값에 대한 예제 수준 회귀 테스트가 추가되었습니다.

### 계층 7 - 문서

- Prometheus 대시보드 README 파일은 이제 안전한 `REDACT` 기본값과 일치하며 `HASH` 또는 `RAW`를 사용할 수 있는 시기를 설명합니다.

## 검증

- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.history.MongoHistoryConfigTest' :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderOptionsValidationTest' :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroupTest.slot key rejects Redis hash-tag manipulation in lock name' :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonLeaderElectionTest.lock name validation rejects Redis namespace manipulation before backend calls' :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderElectionTest.lock name validation rejects map namespace manipulation before backend calls' :examples:ktor-app:test --tests 'io.bluetape4k.leader.examples.ktor.KtorAppTest.Redis URL redaction*' :examples:prometheus-dashboard:test --tests 'io.bluetape4k.leader.examples.prometheus.PrometheusAssetsTest.application config redacts lock name metric tags by default' --no-build-cache`
- `git diff --check`
