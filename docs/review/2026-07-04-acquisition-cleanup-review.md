# 인수 정리 7단계 검토

날짜: 2026-07-04 범위: Issue #566 및 #567, 마일스톤 0.5.0

## 검토된 모듈

- `leader-consul`: 비동기 단일 및 그룹 정리 콜백.
- `leader-k8s`: 비동기 단일/그룹 정리 콜백 및 단일 수집 정리를 일시 중지합니다.
- `leader-exposed-jdbc`: 비동기 단일 및 그룹 정리 콜백.
- `leader-mongodb`: 비동기 단일/그룹 정리 콜백 및 단일 수집 정리를 일시 중지합니다.
- `leader-hazelcast`: 비동기 단일/그룹 정리 콜백 및 단일 수집 정리를 일시 중지합니다.
- `leader-redis-lettuce`: 단일 획득 정리를 일시 중지합니다.
- `leader-redis-redisson`: 단일 획득 정리를 일시 중지합니다.
- `leader-exposed-r2dbc`: 단일 획득 정리를 일시 중지합니다.
- `leader-zookeeper`: 단일 획득 정리를 일시 중지합니다.

## 7계층 결과

1. 정확성: 통과
   - 비동기 정리는 직접 완료 콜백과 함께 첨부되므로 작업 시작 후 호출자 실행기 종료는 릴리스/워치독 닫기를 건너뛸 수 없습니다.
   - suspend 선출기는 성공적으로 획득한 후 감사, 핸들, 감시 또는 작업 설정이 중단되기 전에 즉시 정리 범위에 들어갑니다.

2. API 및 계약 호환성: 통과
   - 공개 유형, 생성자 또는 메소드 서명은 변경되지 않습니다.
   - `CompletableFuture` 작업 결과/실패 전파는 작업 미래에 계속 위임됩니다.

3. 동시성 및 취소: PASS
   - 코루틴 취소는 여전히 전파됩니다.
   - 필수 릴리스는 기존 `NonCancellable` 정리 블록에서 실행됩니다.
   - Watchdog 정리는 Watchdog 생성 전에 취소가 발생하는 경우 Null 안전합니다.

4. 백엔드 소유권 안전성: 통과
   - 기존 소유자/토큰/스레드 ID 릴리스 검증은 유지됩니다.
   - 백엔드별 `minLeaseTime` 릴리스 동작은 변경되지 않았습니다.

5. 테스트: 합격
   - 호출자 실행기 종료가 해제/삭제 정리를 건너뛸 수 없는 Consul 가짜 클라이언트 비동기 회귀를 추가했습니다.
   - Kubernetes K3s 비동기 회귀 및 소스 구조 일시 중지 가드가 추가되었습니다.
   - H2, PostgreSQL 및 MySQL에 Exposed JDBC 비동기 회귀를 추가했습니다.
   - 실행기 종료 및 `recordAcquired` 취소를 위한 Mongo 비동기 및 일시 중지 회귀를 추가했습니다.
   - 향후 작업이 완료되기 전에 호출자 실행기가 종료되는 Hazelcast 비동기 단일/그룹 회귀를 추가했습니다.
   - 잠금 획득 후 `recordAcquired` 취소가 발생하지만 작업 시작 전에 다음 경쟁자가 다시 획득하는 Lettuce 일시 중지 회귀를 추가했습니다.
   - H2, PostgreSQL 및 MySQL 전반에 걸쳐 Exposed R2DBC 일시 중지 회귀를 추가했습니다.
   - 즉각적인 획득 후 정리 범위를 위해 Redisson 및 ZooKeeper 소스 구조 가드를 추가했습니다.

6. 보안 및 관찰 가능성: 통과
   - 새로운 토큰, 자격 증명 또는 소유자 페이로드 로깅이 없습니다.
   - 기존 정리 실패 로깅 경로는 유지됩니다.

7. 유지보수성: 합격
   - 변경 사항은 영향을 받는 선출기 패턴에 따라 다릅니다.
   - 종속성, 모듈 또는 공개 API 변동이 없습니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-consul:compileKotlin :bluetape4k-leader-consul:compileTestKotlin :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin :bluetape4k-leader-exposed-jdbc:compileKotlin :bluetape4k-leader-exposed-jdbc:compileTestKotlin :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin :bluetape4k-leader-hazelcast:compileKotlin :bluetape4k-leader-hazelcast:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin :bluetape4k-leader-zookeeper:compileKotlin :bluetape4k-leader-zookeeper:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-consul:test --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectorDelegationTest.runAsyncIfLeader cleanup runs after caller executor shutdown' --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:test --tests 'io.bluetape4k.leader.k8s.KubernetesLeaseSuspendCancellationSafetyTest.suspend elector opens cleanup scope immediately after acquisition' --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:k8sTest --tests 'io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElectorK3sTest.async cleanup runs after caller executor shutdown' --warning-mode all`
- `./gradlew :bluetape4k-leader-exposed-jdbc:test --tests 'io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.MongoLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --tests 'io.bluetape4k.leader.mongodb.MongoSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 그룹 슬롯 cleanup 이 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastSuspendCancellationSafetyTest.suspend elector unlock failure handling rethrows CancellationException' --warning-mode all`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests 'io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest.suspend elector opens cleanup scope immediately after acquisition' :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElectorTest.suspend elector opens cleanup scope immediately after acquisition' --warning-mode all`
- `git diff --check`
- `rg -n "whenCompleteAsync|handleAsync" leader-consul/src/main/kotlin leader-k8s/src/main/kotlin leader-exposed-jdbc/src/main/kotlin leader-mongodb/src/main/kotlin leader-hazelcast/src/main/kotlin`는 일치하는 항목을 반환하지 않았습니다.

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
