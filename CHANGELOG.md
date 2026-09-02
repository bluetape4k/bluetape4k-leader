# 변경 내역

`bluetape4k-leader`에 대한 모든 주요 변경 사항은 여기에 문서화되어 있습니다.

형식은 [변경 로그 유지](https://keepachangelog.com/en/1.0.0/)를 따릅니다. 버전은 [의미적 버전 관리](https://semver.org/spec/v2.0.0.html)를 따릅니다.

---

## [미공개]

## [1.0.0] — 2026-09-02

### 추가

- `LeaderBackendDiagnosticsProbe` 공통 계약과 `UNKNOWN` 원인(`CLIENT_STATE_UNCONFIRMED`, `PROVIDER_UNSUPPORTED`, `PROVIDER_EXCEPTION`)을 built-in backend, Ktor, Spring, Micrometer 경로에 연결했습니다([Issue #766](https://github.com/bluetape4k/bluetape4k-leader/issues/766), [PR #812](https://github.com/bluetape4k/bluetape4k-leader/pull/812), [PR #813](https://github.com/bluetape4k/bluetape4k-leader/pull/813), [PR #814](https://github.com/bluetape4k/bluetape4k-leader/pull/814), [PR #816](https://github.com/bluetape4k/bluetape4k-leader/pull/816), [PR #819](https://github.com/bluetape4k/bluetape4k-leader/pull/819), [PR #820](https://github.com/bluetape4k/bluetape4k-leader/pull/820)).
- Prometheus dashboard 예제에 active connectivity probe, bounded reason metric/alert, HTTP scrape readiness 계약을 추가했습니다([PR #823](https://github.com/bluetape4k/bluetape4k-leader/pull/823), [PR #836](https://github.com/bluetape4k/bluetape4k-leader/pull/836), [PR #840](https://github.com/bluetape4k/bluetape4k-leader/pull/840)).

### 변경

- Kotlin 2.4, JDK 25, Gradle 9.7 기준의 1.0.0 개발선을 완료하고, 배포 후 중앙 catalog ref를
  `3c203aa9f8ba80685aac766c5fb8f24e23d0058e`로 수렴해 전체 stable BOM 경계를
  사용하도록 정리했습니다([Issue #666](https://github.com/bluetape4k/bluetape4k-leader/issues/666), [Issue #753](https://github.com/bluetape4k/bluetape4k-leader/issues/753), [Issue #860](https://github.com/bluetape4k/bluetape4k-leader/issues/860), [PR #838](https://github.com/bluetape4k/bluetape4k-leader/pull/838), [PR #862](https://github.com/bluetape4k/bluetape4k-leader/pull/862)).
- `UNKNOWN` reason, active probe, Micrometer counter, Spring health, Ktor route, Prometheus alert/runbook을 운영 해석이 가능한 bounded contract로 연결했습니다([PR #819](https://github.com/bluetape4k/bluetape4k-leader/pull/819), [PR #820](https://github.com/bluetape4k/bluetape4k-leader/pull/820), [PR #822](https://github.com/bluetape4k/bluetape4k-leader/pull/822), [PR #823](https://github.com/bluetape4k/bluetape4k-leader/pull/823)).
- Spring lease-extension 관측 범위를 `ObservationRegistry` identity별 execution scope로 격리해 여러 application context의 telemetry가 섞이지 않도록 했습니다([PR #835](https://github.com/bluetape4k/bluetape4k-leader/pull/835)).

### 버그 수정

- Prometheus scrape 테스트가 첫 scheduled callback을 readiness 신호로 추정하지 않고 대상 job과 connectivity probe를 명시적으로 실행하도록 수정했습니다. 실패 시 HTTP status/body와 누락 metric 이름을 진단 메시지에 남깁니다([Issue #724](https://github.com/bluetape4k/bluetape4k-leader/issues/724), [PR #840](https://github.com/bluetape4k/bluetape4k-leader/pull/840)).
- Redis strategic 후보 heartbeat 재등록이 결과 카운터와 `lastCompletionTime`을 되돌리지 않도록 하고, Redisson entry-lock 취소·late-acquisition·unlock cleanup과 Micrometer probe interrupt flag 보존을 같은 lifecycle 계약으로 닫았습니다([Issue #804](https://github.com/bluetape4k/bluetape4k-leader/issues/804), [Issue #826](https://github.com/bluetape4k/bluetape4k-leader/issues/826), [Issue #828](https://github.com/bluetape4k/bluetape4k-leader/issues/828), [PR #830](https://github.com/bluetape4k/bluetape4k-leader/pull/830)).

## [0.5.0] — 2026-08-06

### 추가됨

- 리더 선출 시도, 선택한 작업, 건너뛴 작업 및 실패에 대한 Micrometer 관찰 및 OpenTelemetry 추적 지원을 추가했습니다([#529](https://github.com/bluetape4k/bluetape4k-leader/issues/529)).
- 지표 내보내기 전에 리더 잠금 이름과 리더 ID를 정규화하거나, 허용 목록에 추가하거나, 수정할 수 있도록 지표 태그 카디널리티 제어를 추가했습니다([#530](https://github.com/bluetape4k/bluetape4k-leader/issues/530)).
- 정체된 리더십, 오류 급증 및 백엔드 위험 신호에 대한 Prometheus 경고 규칙, Grafana 지침 및 리더 운영 런북을 추가했습니다([#534](https://github.com/bluetape4k/bluetape4k-leader/issues/534)).
- 활성 백엔드 후보, 관리 엔드포인트 노출, 레지스트리 시드 격차 및 높은 카디널리티 태그 위험에 대한 Spring Boot 구성 메타데이터 및 시작 진단을 추가했습니다([#538](https://github.com/bluetape4k/bluetape4k-leader/issues/538)).
- 모든 Kotlin production source를 대상으로 하는 Detekt 검증, `0.4.0` 기준 ABI 호환성 gate, 중앙 catalog를 인식하는 CodeQL Kotlin pinning workflow를 추가했습니다([#640](https://github.com/bluetape4k/bluetape4k-leader/issues/640), [#641](https://github.com/bluetape4k/bluetape4k-leader/issues/641), [#643](https://github.com/bluetape4k/bluetape4k-leader/issues/643)).

### 변경됨

- 관찰 가능성, 운영 준비 상태, 진단 및 벤치마크 지원 해석에 대한 새로워진 README 및 현지화된 README 지침([#529](https://github.com/bluetape4k/bluetape4k-leader/issues/529), [#530](https://github.com/bluetape4k/bluetape4k-leader/issues/530), [#534](https://github.com/bluetape4k/bluetape4k-leader/issues/534), [#538](https://github.com/bluetape4k/bluetape4k-leader/issues/538)).
- 0.5.0 범위를 확장하는 대신([#561](https://github.com/bluetape4k/bluetape4k-leader/issues/561)) 백로그에 나머지 관리, 상태, 감사 내보내기, 경로 도우미, Ktor 및 리스 연장 후속 조치를 유지했습니다.
- release-readiness 수정과 문서/KDoc localization train을 각각 PR [#648](https://github.com/bluetape4k/bluetape4k-leader/pull/648) 및 PR [#625](https://github.com/bluetape4k/bluetape4k-leader/pull/625)–[#632](https://github.com/bluetape4k/bluetape4k-leader/pull/632)로 `develop`에 반영했습니다.
- release-facing `CHANGELOG.md`, `WIP.md` 및 preflight evidence를 현재 `develop` head에 맞춘 문서 정합성 PR [#650](https://github.com/bluetape4k/bluetape4k-leader/pull/650)을 병합했습니다.
- 단일 리더와 그룹 리더 선출의 bilingual visual companion을 추가하고 manual 링크와 source anchor를 정렬했습니다([#633](https://github.com/bluetape4k/bluetape4k-leader/issues/633), [PR #636](https://github.com/bluetape4k/bluetape4k-leader/pull/636)).

### 버그 수정

- Redis, Hazelcast, ZooKeeper, etcd, Consul 및 Spring Boot 테스트 전반에 걸쳐 소유권 보존, 리스 TTL 보고, 세션 손실 감지 및 공유 컨테이너 안정성을 위한 0.5.0 정확성 및 통합 강화 레인을 폐쇄했습니다. ([#511](https://github.com/bluetape4k/bluetape4k-leader/issues/511), [#512](https://github.com/bluetape4k/bluetape4k-leader/issues/512), [#513](https://github.com/bluetape4k/bluetape4k-leader/issues/513), [#514](https://github.com/bluetape4k/bluetape4k-leader/issues/514), [#515](https://github.com/bluetape4k/bluetape4k-leader/issues/515), [#516](https://github.com/bluetape4k/bluetape4k-leader/issues/516), [#517](https://github.com/bluetape4k/bluetape4k-leader/issues/517), [#518](https://github.com/bluetape4k/bluetape4k-leader/issues/518), [#519](https://github.com/bluetape4k/bluetape4k-leader/issues/519)).
- R2DBC group state를 lock name별로 격리하고 blocking interruption을 보존했으며, 전체 lease timing 회귀를 안정화했습니다([#637](https://github.com/bluetape4k/bluetape4k-leader/issues/637), [#639](https://github.com/bluetape4k/bluetape4k-leader/issues/639), [#642](https://github.com/bluetape4k/bluetape4k-leader/issues/642)).
- 선택한 backend와 지원하지 않는 operational state가 Spring surface에 일관되게 반영되도록 정리하고, JVM-global lease extender의 ownership conflict와 close order를 명시했습니다([#638](https://github.com/bluetape4k/bluetape4k-leader/issues/638), [#644](https://github.com/bluetape4k/bluetape4k-leader/issues/644), [#646](https://github.com/bluetape4k/bluetape4k-leader/issues/646)).

### 성능

- 그룹 세마포어 동작, 경합 및 건너뛰기 경로, Spring 주석 조언 오버헤드, Micrometer/history-recorder 오버헤드, Kubernetes Lease 충돌 및 갱신 시나리오에 대한 벤치마크 적용 범위를 추가했습니다([#520](https://github.com/bluetape4k/bluetape4k-leader/issues/520), [#521](https://github.com/bluetape4k/bluetape4k-leader/issues/521), [#522](https://github.com/bluetape4k/bluetape4k-leader/issues/522), [#523](https://github.com/bluetape4k/bluetape4k-leader/issues/523), [#524](https://github.com/bluetape4k/bluetape4k-leader/issues/524)).

---

## [0.4.0] — 2026-06-27

### 추가됨

- 리더 채택 가이드에 대한 확장 가능한 다국어 README 스위치 순서, 모듈 아키텍처 다이어그램, 시나리오 다이어그램 및 의미론적 잠금 상태 다이어그램 색상을 추가하고 문서화했습니다([#486](https://github.com/bluetape4k/bluetape4k-leader/issues/486), [#489](https://github.com/bluetape4k/bluetape4k-leader/issues/489), [#490](https://github.com/bluetape4k/bluetape4k-leader/issues/490), [#491](https://github.com/bluetape4k/bluetape4k-leader/issues/491), [#494](https://github.com/bluetape4k/bluetape4k-leader/issues/494)).
- 지원되는 리더 선출 백엔드에 대한 확장된 실행 가능 백엔드 채택 예 및 성능 증거([#413](https://github.com/bluetape4k/bluetape4k-leader/issues/413), [#414](https://github.com/bluetape4k/bluetape4k-leader/issues/414), [#416](https://github.com/bluetape4k/bluetape4k-leader/issues/416), [#423](https://github.com/bluetape4k/bluetape4k-leader/issues/423), [#424](https://github.com/bluetape4k/bluetape4k-leader/issues/424), [#427](https://github.com/bluetape4k/bluetape4k-leader/issues/427), [#428](https://github.com/bluetape4k/bluetape4k-leader/issues/428), [#429](https://github.com/bluetape4k/bluetape4k-leader/issues/429)).

### 변경됨

- `0.3.1` 퍼블리싱 후 `0.4.0` 개발 라인을 오픈했습니다.
- 로컬 bluetape4k BOM 참조를 `1.11.0` 릴리스 트레인과 정렬하고 조정된 종속성 트레인을 위해 다음 개발 라인을 준비했습니다.

### 버그 수정

- K8s 예제 런타임 호환성을 복원하고, K3s 테스트를 위한 Fabric8 Vert.x 4 런타임을 격리하고, 릴리스 임계값([#480](https://github.com/bluetape4k/bluetape4k-leader/issues/480) 이상으로 `leader-spring-boot` 적용 범위를 높였습니다. [#497](https://github.com/bluetape4k/bluetape4k-leader/issues/497), [#499](https://github.com/bluetape4k/bluetape4k-leader/issues/499)).

---

## [0.3.1] — 2026-06-01

### 변경됨

- `bluetape4k-exposed` 테스트 도우미 줄을 `1.10.0`로 업데이트하고 `bluetape4k-exposed-bom`를 게시 가능한 Exposed 리더 모듈의 구현 범위 플랫폼으로 가져왔습니다.
- 릴리스 워크플로 정렬을 위해 기본 bluetape4k 종속성 카탈로그 참조를 `catalog/2026-06-01-00`으로 업데이트했습니다.

---

## [0.3.0] — 2026-06-01

### 추가됨

- 안전한 슬롯 계약에 대한 Nightly 지원 적용 범위와 함께 Kubernetes Lease 슬롯별 그룹 선출 지원을 추가했습니다.
- Spring 또는 Ktor에 바인딩하지 않고 수명 주기 이벤트가 필요한 호출자를 위해 프레임워크 중립 리더 이벤트 콜백 핸들을 추가했습니다.
- etcd 조정자, Consul 유지 관리 기간, 전략적 선출, 가상 스레드 리더 작업 및 Redisson 지원 장기 실행 리더 작업에 대한 실행 가능한 채택 예가 추가되었습니다.
- 동시성이 높은 리더 전용 유지 관리 작업을 위해 실행 가능한 가상 스레드 리더 러너 예제가 추가되었습니다. (#426)
- bluetape4k 리스 자동 연장으로 보호되는 장기 실행 리더 전용 작업에 대해 실행 가능한 Redisson 감시 예제를 추가했습니다. (#425)

### 변경됨

- `0.2.2` 퍼블리싱 후 `0.3.0` 개발 라인을 오픈했습니다.
- 공개 README 종속성 조각을 `0.3.0` 릴리스 좌표로 업데이트했습니다.
- 재현 가능한 렌더링 증거가 포함된 새로워진 README 아키텍처, 벤치마크 및 예제 워크플로 다이어그램.
- Ktor 예시와 Ktor 관련 소비자 지침을 공유 bluetape4k Ktor 상태 모듈과 일치시켰습니다.
- Nightly 요약 문서 및 워크플로 화면에서 미리 보기 백엔드 릴리스 게이트 증거를 명시적으로 만들었습니다. (#417)
- `bluetape4k-exposed` 테스트 도우미 참조를 `1.9.2-SNAPSHOT`에서 게시된 `1.9.2` 릴리스로 업데이트했습니다.
- AWS SDK BOM을 중앙 `bluetape4k-dependencies` 카탈로그에 맞게 조정했습니다.
- `bluetape4k-projects` `1.10.0` BOM 라인을 사용했습니다.

### 버그 수정

- Dependabot 보안 경고에 대해 중앙 카탈로그 관리 Netty 4.1, Protobuf, Fabric8 및 Vert.x 4 종속성 재정의를 적용했습니다. (#389)
- 플러그인 클래스 경로 Dependabot 경고를 지우기 위해 Gradle 플러그인 클래스 경로 MySQL 및 Protobuf 종속성을 중앙 카탈로그 라인에 강제 적용합니다. (#389)
- 사용되지 않는 루트 `apply false` Exposed 플러그인 선언을 제거하여 GitHub 종속성 제출이 더 이상 오래된 플러그인 POM 전이문을 보고하지 않습니다. (#389)
- `examples/migration-gate`에서 사용되지 않는 Exposed 마이그레이션 플러그인을 제거했습니다. 이 예에서는 런타임 Exposed API를 직접 사용하며 플러그인 작업이 필요하지 않습니다. (#389)
- 슬롯별 그룹 리스 연장 의미가 정의될 ​​때까지 Spring 그룹 스트림 지원이 명시적으로 거부됩니다.

### 성능

- Lettuce 및 Redisson에 대한 Redis 리스 연장 벤치마크 증거를 추가했습니다.
- 릴리스 권장사항이 측정을 뒷받침하도록 SQL 백엔드 및 미리보기 백엔드 벤치마크 증거를 추가했습니다.

---

## [0.2.2] — 2026-05-25

### 추가됨

- DynamoDB 리더 선거 엣지 케이스 적용 범위를 확장했습니다. (#366, PR #368)
- Consul, DynamoDB 및 etcd에 대한 미리 보기 백엔드 상태 스냅샷 계약 적용 범위를 추가했습니다. (#374, PR #381)
- DynamoDB 리더 팩토리 클래스 및 확장 기능에 대해 KDoc을 추가했습니다. (#365, PR #369)

### 변경됨

- 공개 README 종속성 조각을 `0.2.2` 릴리스 좌표로 업데이트했습니다. (#375)
- Consul, DynamoDB Local, etcd 및 Kubernetes Lease에 대해 미리 보기 백엔드 릴리스 게이트를 명시적으로 만들었습니다. K3s 런타임 적용 범위는 Nightly 전체로 유지됩니다. (#376, PR #382)
- 중복된 컨테이너 배선을 제거하기 위해 `bluetape4k-testcontainers`을 통해 DynamoDB Local 테스트 실행기를 공유했습니다. (#367, PR #370)

### 버그 수정

- 바운드 Consul 블로킹 대기 및 구성된 요청 제한 시간 예산에 대한 정리 경로 획득. (#372, PR #379)
- 바운드 etcd 정리는 구성된 제한 시간 예산을 기다립니다. (#373, PR #380)

---

## [0.2.1] — 2026-05-23

### 변경됨

- `bluetape4k-exposed` `1.9.1` 릴리스를 사용하도록 릴리스 라인을 업데이트했습니다.

---

## [0.2.0] — 2026-05-23

### 추가됨

- `leader-k8s` Kubernetes Lease 백엔드(K3s 통합 적용 범위 포함) (#335)
- `leader-etcd` etcd v3 백엔드(블로킹, 비동기 및 일시 중지 선출 경로 포함). (#227)
- `leader-consul` Consul 리더 선출을 블로킹 및 일시 중단하는 KV/세션 백엔드. (#345)
- `leader-dynamodb` AWS DynamoDB 조건부 쓰기 기반 리더 선출을 위한 백엔드. (#228)
- 코루틴 기본 상태 관찰을 위한 잠금별 `StateFlow<LeaderState>` 지원. (#222)
- Exposed JDBC/R2DBC 백엔드에 대한 DB 서버 시간 기반 잠금 획득을 지원합니다. (#39)
- Kubernetes 운영자 리더십 및 리더 조정 속도 제한에 대한 실행 가능한 채택 예입니다. (#231, #229)
- K3s 리스 통합 예시 적용 범위. (#248)

### 변경됨

- 내부 `runBlocking` 브리지 위임을 순수 일시 중단 `ExtendDelegate` 계약으로 대체했습니다. (#271)
- `1.9.0`에 대한 `bluetape4k-exposed` 테스트 도우미 참조를 업데이트했습니다.
- 공개 README 종속성 조각을 `0.2.0` 릴리스 좌표로 업데이트했습니다.

### 버그 수정

- `runCatching`을 통해 삼키는 대신 `CancellationException`을 다시 발생시키기 위해 Exposed JDBC 취소 처리를 재작업했습니다. (#304)
- Exposed JDBC, MongoDB 및 Lettuce 획득 루프에서 벽시계 대기 기한을 단조로운 시간 초과 예산으로 대체했습니다. (305호, 306호, 308호, 309호)
- 원래 Lease 인스턴스를 내부에서 변경하지 않도록 Kubernetes Lease 주석 변환을 수정했습니다. (#347)
- 누락된 Kubernetes Lease 일시 중지 선출기 K3s 시나리오를 추가했습니다. (#349)
- 관찰 가능성 및 Ktor 관리 경로 문서를 사용하여 한국어 README 콘텐츠를 동기화했습니다. (#287)
- Ktor 관리 레지스트리 KDoc을 확장하고 WebhookPoller 공개 문서/설명을 영어로 번역했습니다. (#288, #348)

### 제거됨

- 0.1.0 GA 라인 이전에 더 이상 사용되지 않는 모든 API를 제거했습니다. (#269)

---

## [0.1.0] — 2026-05-16

`bluetape4k-leader`의 첫 번째 공개 릴리스입니다. 실험적이라고 언급되지 않는 한 모든 API는 안정적입니다.

### 주요 변경 사항

- **`runIfLeader()`은 잠금 경합을 발생시키지 않습니다**: 잠금이 획득되지 않은 경우 백엔드 예외를 전파하는 대신 `null`을 반환합니다. ShedLock 경합 건너뛰기 의미 체계와 일치합니다. `CancellationException` 및 `InterruptedException`은 여전히 다시 발생합니다. (PR #15)

  ```kotlin
  // null은 "선출되지 않음" 또는 "작업이 예외를 던짐"을 의미합니다. 로그를 확인하세요.
  val result = leaderElector.runIfLeader("job") { riskyWork() }

  // 예외 전파를 유지하려면 작업 내부에서 래핑합니다.
  leaderElector.runIfLeader("job") {
      try { riskyWork() } catch (e: MyException) { handleError(e); throw e }
  }
  ```

- **`leader-exposed-jdbc`**: `ExposedJdbcLeaderElector.runIfLeader()`은 이제 작업 예외를 다시 발생시키는 대신 `null`를 반환합니다. `CancellationException` 및 `InterruptedException`은 여전히 ​​다시 발생합니다. (문제 #50)

- **`leader-exposed-r2dbc`**: `ExposedR2DbcSuspendLeaderElector.runIfLeader()`에도 동일한 변경이 적용됩니다. `CancellationException`은(는) 여전히 다시 발생합니다.

- **`leader-exposed-jdbc` / `leader-exposed-r2dbc`**: 선출기 팩토리는 이제 선택적 `historyRecorder` 매개변수(`SafeLeaderHistoryRecorder?` / `SuspendSafeLeaderHistoryRecorder?`)를 허용합니다. 선출 옵션의 이전 `recordHistory` 옵션이 대체되었습니다.

- **`LeaderElection` / `LeaderGroupElection` 이름 변경**: 모든 인터페이스의 일관성을 위해 `LeaderElector` / `LeaderGroupElector`로 변경되었습니다. (PR #106, #123, #125)

- **기간 API**: `java.time.Duration`에서 `kotlin.time.Duration`로 마이그레이션되었습니다. (PR #126)

- **`LeaderElectionEvent.Elected`**에는 이제 선택적 `leaderId: String?` 및 `leaseExpiry: Instant?` 필드가 있습니다(둘 다 기본값은 `null`임). `Elected(lockName)`을 위치적으로 생성하는 컴파일된 호출자는 새 바이트코드에 대한 링크에 실패합니다. 재컴파일이 필요합니다(소스 호환).

- **Spring Boot 3/4 분할 통합**: `leader-spring-boot-common`, `leader-spring-boot3` 및 `leader-spring-boot4`가 단일 `leader-spring-boot` 모듈로 병합되었습니다. (PR #105)

### 추가됨

**`leader-core`** — 핵심 인터페이스 및 로컬 프로세스 내 구현:

- `LeaderElector` — 단일 리더 인터페이스 차단
- `AsyncLeaderElector` — `CompletableFuture` 기반 비동기 인터페이스
- `VirtualThreadLeaderElector` — 선출당 가상 스레드 인터페이스
- `SuspendLeaderElector` — Kotlin 코루틴 정지 인터페이스
- `LeaderGroupElector` — 복수 리더(세마포어) 인터페이스 차단
- `SuspendLeaderGroupElector` — 코루틴 다중 리더 인터페이스
- `LeaderElectionOptions(waitTime, leaseTime)` — 공유 옵션 데이터 클래스
- `LeaderGroupElectionOptions(maxLeaders, waitTime, leaseTime)` — 그룹 옵션 데이터 클래스
- 로컬 구현: `LocalLeaderElector`, `LocalLeaderGroupElector`, `LocalSuspendLeaderElector`, `LocalSuspendLeaderGroupElector`, `LocalAsyncLeaderElector`, `LocalVirtualThreadLeaderElector`
- `LockAssert` — `assertLocked()` / `assertLocked(lockName)` / `isLocked()` 및 변형 일시 중지
- `LockExtender` — `extendActiveLock(Duration): Boolean` + 상세 봉인된 `ExtendOutcome` 결과 + suspend 변형
- `LeaderLockHandle` 봉인 클래스(`Real` / `FailOpen`) — 명시적 리스 핸들
- `LeaderLeaseAutoExtender` — `shutdown()` / `restart()`를 사용한 정기적인 백그라운드 리스 갱신
- `ListeningLeaderElector` / `ListeningLeaderGroupElector` — 핫 `events: Flow<LeaderElectionEvent>` 스트림이 있는 청취자 인식 데코레이터(문제 #40, PR #146)
- `TenantScopedLeaderElectors` — `forTenant(tenantId)` 다중 테넌트 잠금 이름 범위 지정을 위한 확장 함수(문제 #42)
- 전략적 선거 API(문제 #29, #31, #32):
  - `CandidateInfo`, `ElectionStrategy`, `CandidateScorer` 인터페이스
  - 내장 전략: `FifoElectionStrategy`, `RandomElectionStrategy`, `ScoredElectionStrategy`
  - 내장 득점자: `IdleTimeScorer`, `SuccessRateScorer`, `RecentSuccessScorer`, `WeightedScorer`
  - Redis `CandidateRegistry` (Redisson 정렬 집합/해시 + TTL, Lettuce 변형 포함)
- `LeaderSlot` 감사 ID 전파: `LeaderSlot(lockName, leaderId)`이 `LeaderRunResult.Elected.leaderId`로 전파됩니다(문제 #72).

**`leader-redis-lettuce`** — Lettuce Redis 백엔드:

- `LettuceLeaderElector` — 차단, `LettuceLock`를 통해 `SET NX PX` 사용
- `LettuceLeaderGroupElector` — `LettuceSlotTokenGroup`(ZSET + Lua TTL)을 통해 복수 리더 차단
- `LettuceSuspendLeaderElector` — 코루틴 단일 리더
- `LettuceSuspendLeaderGroupElector` — 코루틴 다중 리더
- `LettuceLock`, `LettuceSuspendLock` — Redis 분산 잠금 기본 요소(자체 포함, `bluetape4k-lettuce` 종속성 없음)

**`leader-redis-redisson`** — Redisson Redis 백엔드:

- `RedissonLeaderElector` — `RLock.tryLock()`을 통해 차단
- `RedissonLeaderGroupElector` — `RPermitExpirableSemaphore`을 통한 복수 리더 차단
- `RedissonSuspendLeaderElector` — PID 시드 Snowflake 잠금 ID가 있는 코루틴 단일 리더
- `RedissonSuspendLeaderGroupElector` — 코루틴 다중 리더

**`leader-exposed-core`**: JDBC 및 R2DBC 백엔드에 대한 공유 Exposed 테이블 DDL(`LeaderLockTable`, `LeaderGroupLockTable`). (문제 #23)

**`leader-exposed-jdbc`** — Exposed JDBC 블로킹 백엔드(문제 #21, PR #52):

- `ExposedJdbcLeaderElector` — 단일 리더 블로킹(동기화 + `CompletableFuture` 비동기)
- `ExposedJdbcLeaderGroupElector` — 복수 리더 차단
- H2/PostgreSQL/MySQL Testcontainers 통합 테스트

**`leader-exposed-r2dbc`** — Exposed R2DBC 코루틴 백엔드(문제 #22, PR #62):

- `ExposedR2DbcSuspendLeaderElector` — 코루틴 단일 리더
- `ExposedR2DbcSuspendLeaderGroupElector` — 코루틴 다중 리더
- R2DBC PostgreSQL Testcontainers 통합 테스트

**`leader-mongodb`** — MongoDB 백엔드(문제 #8, PR #46):

- `MongoLeaderElector` — 차단, `findOneAndUpdate` upsert + `deleteOne(token)` 소유자 전용 릴리스
- `MongoSuspendLeaderElector` — 코루틴, Kotlin 코루틴 드라이버
- `MongoLeaderGroupElector` — 복수 리더 블로킹(`lockName:slot:N` 슬롯 모델)
- `MongoSuspendLeaderGroupElector` — 코루틴 다중 리더(이중 컬렉션 디자인)
- 시작 시 TTL 색인 자동 생성
- 82.4% 라인 커버리지(42개 테스트, Testcontainers MongoDB)

**`leader-hazelcast`** — Hazelcast `IMap` 토큰 잠금 백엔드(문제 #9):

- `HazelcastLeaderElector`, `HazelcastLeaderGroupElector` — 단일/복수 리더 차단
- `HazelcastSuspendLeaderElector`, `HazelcastSuspendLeaderGroupElector` — 코루틴 변형

**`leader-zookeeper`** — ZooKeeper/큐레이터 백엔드. (PR #138)

**`leader-micrometer`** — Micrometer 측정항목 통합:

- `MicrometerLeaderElectionListener`은 `lock.name` 및 `event` 태그를 사용하여 `leader.election.events`을 기록합니다(문제 #40, PR #146).

**`leader-spring-boot`** — Spring Boot 4 자동 구성 + AOP:

- `@LeaderElection` / `@LeaderGroupElection` AspectJ 컴파일 타임 위빙(CTW)을 사용한 주석
- `suspend`, `Mono`, `Flux` 및 Kotlin `Flow` 반환 유형(#74, #90, #91) 지원
  - `@LeaderElection(streamBounded = true)` 제한된 스트림에 대한 명시적인 선택
- `LeaderAnnotationValidatorBeanPostProcessor` — 시작 유효성 검사; 안전하지 않은 반환 유형(`CompletableFuture`, `Deferred` 등)을 차단합니다(PR #79)
- `LockAssert` / `LockExtender` AOP 측면 통합(문제 #79)
- `LeaderMetricsHealthIndicator` — Spring Boot 액추에이터 상태 표시기(`leaderMetricsHealthIndicator`로 등록됨)
- `LeaderLeaseAutoExtenderLifecycle` — 컨텍스트 수명 주기 인식 자동 확장기 통합
- 백엔드 자동 구성: Lettuce, Redisson, Exposed JDBC, Exposed R2DBC, MongoDB, Hazelcast
- `LeaderProperties` — `bluetape4k.leader.*` 구성 속성

**`leader-ktor`** — Ktor 3.x 통합(문제 #37, PR #164):

- `LeaderElectionPlugin` — `createApplicationPlugin` DSL, `SuspendLeaderElector` 기반
- `Application.leaderScheduled(lockName, period) { }` — Spring `@Scheduled` 스타일 리더 전용 주기적 작업 도우미. `ApplicationStopped`에 자동 취소되었습니다.

**`leader-bom`** — 소비자용 BOM(Bill of Materials). 모든 `leader-*` 모듈이 포함되어 있습니다. BOM 사용자는 개별 버전을 지정할 필요가 없습니다.

**`examples/`** — 실행 가능한 예제 애플리케이션(문제 #36):

- `batch-scheduler` — Lettuce Redis 주기적 일괄 단일 실행(PR #159)
- `migration-gate` — Exposed JDBC 부팅 시 스키마 마이그레이션 게이트(PR #160)
- `webhook-poller` — MongoDB 단일 인스턴스 웹훅 폴링(PR #161)
- `cache-warmer` — Hazelcast 파티션별 리더 캐시 워밍(PR #162)
- `tenant-aggregator` — Exposed R2DBC 코루틴 다중 테넌트 집계(PR #163)
- `ktor-app` — Ktor 3.x + Lettuce Redis `leaderScheduled()` 데모(PR #166)
- `prometheus-dashboard` — Spring Boot + Prometheus/Grafana 리더 지표 대시보드

**CI/CD**(문제 #13, #35, PR #19, #20, #44, #135):

- GitHub Actions 빌드, 테스트, 비밀 스캔, Gradle 래퍼 검증
- Nightly SNAPSHOT 자동 게시(테스트 성공 시에만)
- Lettuce 및 Redisson 백엔드에 대한 병렬 테스트 작업

### 변경됨

- `LeaderElectionAspect` / `LeaderGroupElectionAspect` 외부 캐치가 `Exception`(`Throwable` 아님)로 좁아져 `OutOfMemoryError` / `StackOverflowError`가 전파될 수 있습니다.
- `LeaderElectionOptions`, `LeaderGroupElectionOptions`, `LeaderGroupState`는 `init {}`(`waitTime ≥ 0`, `leaseTime > 0`, `maxLeaders ≥ 1`)에서 열심히 검증합니다. (PR #25)
- `ExposedJdbcGroupLock.tryLock()` 반환 유형이 `Boolean?`로 변경됨: `null` = DB 오류, `false` = 슬롯 경합, `true` = 획득됨. (문제 #60, PR #63)
- suspend 테스트에서는 실제 IO(MongoDB / Testcontainers) 테스트에 `runTest` 대신 `runBlocking(Dispatchers.IO)`을 사용합니다.
- CI는 불필요한 작업과 일시적인 오류를 줄이기 위해 경로 필터 및 재시도 구성을 사용합니다. (PR #135)
- Prometheus 내보내기 범위는 `PrometheusServer` 스크랩 테스트를 통해 확인되었습니다. (PR #144)
- `leader-bom` NMCP 집계 및 중앙 스냅샷 게시가 수정되었습니다. (PR #140)
- 공장 `create()` I/O 오류는 이제 구성된 오류 모드를 따릅니다. (PR #107)
- 누락된 AOP 속성 바인딩에 `@ConfigurationProperties`이 추가되었습니다. (PR #93)

### 버그 수정

- **코루틴 취소 안전성**: 취소 시 잠금 누출을 방지하기 위해 모든 코루틴 백엔드(Lettuce, Redisson, Hazelcast, MongoDB)의 `unlock`/`release`을 `withContext(NonCancellable)`로 래핑합니다. (PR #25, 리뷰 2026-05-01)
- **`CancellationException` 다시 발생**: `withContext(NonCancellable)` 내부를 포함하여 모든 `catch(Exception)` 블록 앞에 `catch(CancellationException) { throw e }`이 추가되었습니다. (PR #45)
- **Lettuce 관찰 가능성**: `runCatching { unlock }` 실패는 이제 `.onFailure { log.warn }`을 통해 기록됩니다. 이전에는 토큰 불일치/Redis 오류가 자동으로 삭제되었습니다.
- **`ExposedJdbcGroupLock.isHeldByCurrentInstance()`**: 누락된 토큰 + `lockedUntil > NOW()` 확인을 추가했습니다. (문제 #59, PR #63)
- **`ExposedJdbcGroupLock.tryLock()` DB 오류 전파**: `Boolean?` 3단계는 DB 오류를 슬롯 경합과 분리합니다. (문제 #60, PR #63)
- **Exposed SELECT 조건자 잠금**: `lockedUntil > NOW()`이 JDBC `tryAcquireOnce`에 추가되었습니다. 3단계 분할 브레인을 방지하기 위해 SELECT; R2DBC과 대칭입니다. (리뷰 2026-05-04)
- **`leader-redis-redisson` 코루틴 잠금 ID**: `bluetape4k-idgenerators` compileOnly 종속성(런타임에 `ClassNotFoundException` 발생)을 자체 포함 PID 시드 미니 눈송이 생성기로 대체했습니다. (문제 #3, PR #17)
- **`leader-redis-lettuce`**: `LettuceLock` 기본 요소를 직접 포팅하여 `bluetape4k-lettuce`에 대한 런타임 종속성을 제거했습니다. (PR #2)
- **Kover 적용 범위 집계**: CI 적용 범위 스크립트에서 누락된 모듈 집계 버그를 수정했습니다.
- **더 이상 사용되지 않는 API가 대체됨**: `TimebasedUuid.Epoch` → `Uuid.V7`(Kotlin 2.3+).

### 제거됨

다음과 같은 더 이상 사용되지 않는 API는 0.1.0 GA(#264) 이전에 제거되었습니다.

| Item | Replacement |
|------|-------------|
| `LeaderLease.leaderId` property | `LeaderLease.auditLeaderId` |
| `LeaderLeaseAutoExtender.start(Boolean lambda)` overload | `start(ExtendDelegate)` form |
| `HistoryStatus` typealias (`HistoryStatus.kt` deleted) | `LeaderHistoryStatus` |
| `RetryStrategy` typealias (`RetryStrategy.kt` deleted) | (zero callers — removed) |
| `ExposedJdbcGroupLock.extend()` | (no production callers — removed) |
| `ExposedJdbcLock.extend()` | (no production callers — removed) |
| `MongoLock.extend()` | (no callers — removed) |
| `MongoSuspendLock.extend()` | (no callers — removed) |
| `LettuceSemaphore` class | `LettuceLeaderGroupElector` (slot-token TTL model) |
| `LettuceSuspendSemaphore` class | `LettuceSuspendLeaderGroupElector` |

---

[미공개]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.4.0...HEAD
[0.4.0]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.3.1...0.4.0
[0.3.1]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.2.2...0.3.0
[0.2.2]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.2.1...0.2.2
[0.2.1]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/bluetape4k/bluetape4k-leader/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/bluetape4k/bluetape4k-leader/releases/tag/0.1.0
