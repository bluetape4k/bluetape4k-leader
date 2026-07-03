# Backend Namespace Safety Review

Issue: #579
Milestone: 0.5.0

## Scope

- `bluetape4k-leader-mongodb`
- `bluetape4k-leader-dynamodb`
- `bluetape4k-leader-redis-lettuce`
- `bluetape4k-leader-redis-redisson`
- `bluetape4k-leader-hazelcast`
- `examples/ktor-app`
- `examples/prometheus-dashboard`

## 7-Tier Findings

### Tier 1 - Correctness

- Mongo history collection namespace and TTL are validated at configuration construction time.
- DynamoDB table names and key prefixes are validated before key composition or client calls.
- Redis and Hazelcast single-election lock names now share the core `validateLockName` contract before backend calls.

### Tier 2 - Security

- Redis URL logging no longer exposes credentials from `REDIS_URL`.
- Prometheus dashboard defaults to `REDACT` lock-name tags instead of raw labels.
- Redis hash-tag manipulation through Lettuce slot group lock names is rejected before key construction.

### Tier 3 - Concurrency

- No locking or coroutine ownership semantics changed; validation is performed before acquiring backend locks.

### Tier 4 - API

- Existing public APIs are preserved.
- Invalid namespace values now fail early with `IllegalArgumentException`.

### Tier 5 - Observability

- Metric tag defaults are safer for tenant/user-derived lock names.
- Startup logging remains useful while redacting Redis credentials.

### Tier 6 - Tests

- Added pure validation tests for MongoDB and DynamoDB options.
- Added backend-boundary validation tests for Lettuce, Redisson, and Hazelcast.
- Added example-level regression tests for Redis URL redaction and Prometheus lock-name tag defaults.

### Tier 7 - Documentation

- Prometheus dashboard README files now match the safe `REDACT` default and describe when `HASH` or `RAW` may be used.

## Validation

- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.history.MongoHistoryConfigTest' :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.DynamoDbLeaderOptionsValidationTest' :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroupTest.slot key rejects Redis hash-tag manipulation in lock name' :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonLeaderElectionTest.lock name validation rejects Redis namespace manipulation before backend calls' :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderElectionTest.lock name validation rejects map namespace manipulation before backend calls' :examples:ktor-app:test --tests 'io.bluetape4k.leader.examples.ktor.KtorAppTest.Redis URL redaction*' :examples:prometheus-dashboard:test --tests 'io.bluetape4k.leader.examples.prometheus.PrometheusAssetsTest.application config redacts lock name metric tags by default' --no-build-cache`
- `git diff --check`
