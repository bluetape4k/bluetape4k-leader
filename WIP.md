# WIP — 작업 중 (Work In Progress)

> 현재 진행 중이거나 예정된 작업을 추적합니다.  
> 완료된 항목은 CHANGELOG.md로 이동합니다.

---

## 현재 단계: 0.1.0-SNAPSHOT

| 이슈 | 제목 | 상태 | PR |
|------|------|------|----|
| #2 | LettuceLock/Semaphore 이식 및 bluetape4k-lettuce 의존성 제거 | ✅ 완료 | merged |
| #3 | leader-redis-redisson 테스트 자립화 (AbstractRedissonLeaderTest) | ✅ 완료 | #17 merged |
| #15 | runIfLeader() 반환 타입 T?로 변경 (skip 동작) | ✅ 완료 | merged |

---

## 예정 작업

### #4 — 테스트 커버리지 확대

- [ ] leader-core: `LocalLeaderElection` 엣지 케이스 (waitTime 만료, 동시성)
- [ ] leader-redis-lettuce: 네트워크 단절 시나리오
- [ ] leader-redis-redisson: HA(복수 JVM) 시나리오
- [ ] 통합 테스트: 다중 백엔드 혼합 사용

### #7 — leader-exposed (Exposed/JDBC 백엔드)

- DB 행 수준 잠금으로 리더 선출 구현 (PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` 활용)
- `ExposedLeaderElection`, `ExposedSuspendLeaderElection`
- 테스트: Testcontainers PostgreSQL

### #8 — leader-mongodb (MongoDB 백엔드)

- MongoDB `findOneAndUpdate` + TTL 인덱스 기반 잠금
- `MongoLeaderElection`, `MongoSuspendLeaderElection`
- 테스트: Testcontainers MongoDB

### #9 — leader-hazelcast (Hazelcast 백엔드)

- Hazelcast `ILock` / `FencedLock` 기반 구현
- `HazelcastLeaderElection`, `HazelcastSuspendLeaderElection`

### #10 — leader-micrometer (Micrometer 메트릭)

- 선출 횟수, 획득/실패 카운터, 보유 시간 히스토그램
- `MicrometerLeaderElectionDecorator` — 데코레이터 패턴

### #11 — leader-spring-boot3 (Spring Boot 3 자동 구성)

- `@EnableLeaderElection` 어노테이션
- 백엔드 자동 감지 (Redis/MongoDB/Exposed/Hazelcast)
- Spring Boot 3.x + Coroutines 지원

### #12 — leader-spring-boot4 (Spring Boot 4 자동 구성)

- Spring Boot 4.x 지원 (`leader-spring-boot3` 기반 분기)

### #13 — CI/CD 파이프라인

- GitHub Actions: build, test, publish (SNAPSHOT/RELEASE)
- Testcontainers 서비스 연동
- Codecov 커버리지 리포팅

### #14 — README 정비

- ✅ 루트 README.md / README.ko.md 작성 (2026-04-29)
- ✅ leader-core README 작성 (2026-04-29)
- ✅ leader-redis-lettuce README 작성 (2026-04-29)
- ✅ leader-redis-redisson README 작성 (2026-04-29)
- [ ] 모듈 구현 완료 후 각 모듈 README 업데이트

---

## 백로그 (우선순위 미정)

- gRPC/RSocket 기반 원격 리더 선출 클라이언트
- 리더 이벤트 리스너 (`onElected`, `onRevoked`)
- `@Leader` 어노테이션 AOP 지원 (Spring 연동)
- Kotlin Multiplatform 지원 (JS/Native 제외, JVM+Linux 타겟)

---

## 개발 규칙 메모

- `develop` 브랜치가 일상 작업 기준; `main`은 릴리즈 전용
- 모든 구현 작업은 `.worktrees/<branch>`에서 수행
- 이슈 1개 = PR 1개 = squash merge
- 커밋: 한국어 + conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
