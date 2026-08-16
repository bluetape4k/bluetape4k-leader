# Issue #533 backend diagnostics 7-tier 코드 리뷰

## 범위와 기준

- 대상 분기: `feat/epic-obs-01-diagnostics`
- 검토 기준 커밋: `49cb2e9fa63ef9f3d07541e15dd8be02ae31a960`
- 관련 이슈: #533
- 상위 Epic: #699
- 후속 범위: #559
- 승인된 설계: `docs/superpowers/specs/2026-08-16-issue-533-backend-diagnostics-design.md`
- 승인된 계획: `docs/superpowers/plans/2026-08-16-issue-533-backend-diagnostics-plan.md`

검토 범위는 Core diagnostics SPI와 decorator, 11개 외부 backend provider, Spring Boot endpoint와 health indicator, Ktor route, capability manifest와 EN/KO 문서다. management write action과 audit export는 각각 #532와 #535 범위이므로 제외했다.

## 발견 사항과 조치

첫 번째 독립 아키텍처 검토는 Lettuce의 열린 connection, Redisson의 실행 중 client, Hazelcast의 실행 중 lifecycle을 connectivity `UP`으로 판정한 점을 차단했다. lifecycle 상태는 객체가 닫히지 않았다는 사실만 증명하며 backend 도달 가능성은 증명하지 않는다. `49cb2e9f`에서 명시적으로 중지된 상태만 `DOWN`으로 유지하고 실행 중 상태는 `UNKNOWN`으로 변경했다.

메인 세션 통합 검토에서는 Micrometer decorator가 `LeaderBackendDiagnosticsAware`를 구현하지 않아 진단 기능을 잃는 문제를 추가로 발견했다. 세 wrapper가 nullable provider를 전달하도록 수정하고, provider가 있는 delegate와 없는 delegate를 모두 검증했다. 테스트는 강제 cast 대신 다음 nullable capability 표현을 사용한다.

```kotlin
val provider = (wrapper as? LeaderBackendDiagnosticsAware)?.backendDiagnosticsProvider

provider.shouldNotBeNull().backendDescriptor shouldBe expectedDescriptor
```

## 7-tier 최종 검토

| Tier | 검토 항목 | 결과 | 근거 |
|---|---|---|---|
| 1. 정확성·계약 | 정적 descriptor, opt-in probe, provider 선택, decorator 전달, 상태 매핑 | PASS | lifecycle 신호를 reachability `UP`으로 승격하지 않으며 provider 유무를 nullable capability로 보존한다. |
| 2. 보안·개인정보 | credential, endpoint, lock name, raw exception 노출 | PASS | 응답 모델은 고정 descriptor와 정규화된 connectivity만 제공한다. 원문 연결 정보와 예외 메시지를 노출하지 않는다. |
| 3. 동시성·취소·timeout | probe 호출 횟수, timeout 입력, lease 영향, blocking 위험 | PASS | 정적 조회는 probe를 호출하지 않고, active probe는 양수·유한 timeout을 받는다. 현재 provider는 수동 상태만 읽거나 즉시 `UNKNOWN`을 반환한다. |
| 4. 성능·리소스 수명주기 | client 재사용, 신규 thread/scheduler/cache, 외부 I/O | PASS | 기존 client 상태만 읽으며 진단 전용 client, thread pool, polling, cache를 추가하지 않았다. |
| 5. Kotlin·API·ABI | null safety, immutable 모델, decorator API, binary compatibility | PASS | production `!!`가 없고 safe cast를 사용한다. ABI 검사 대상 16개 artifact에서 unknown 변경이 없다. |
| 6. 테스트·CI·drift | Core/backend/Spring/Ktor 테스트, Detekt, manifest validator | PASS | 3,283개 테스트와 Detekt가 통과했고 source-backed manifest validator 11개 테스트와 self/static 검사가 통과했다. |
| 7. 문서·운영성 | README EN/KO, 기본 비활성화, `UNKNOWN` 의미, source anchor | PASS | 운영 표면, timeout, 보안 경계와 `UNKNOWN` 의미를 두 locale에 맞췄고 runtime source anchor를 validator가 확인한다. |

## 독립 리뷰 결과

| Lane | Verdict | P0 | P1 | P2 | P3 |
|---|---|---:|---:|---:|---:|
| Code review | APPROVE | 0 | 0 | 0 | 0 |
| Architecture review | CLEAR | 0 | 0 | 0 | 0 |

두 최종 리뷰는 `49cb2e9fa63ef9f3d07541e15dd8be02ae31a960`을 대상으로 다시 수행했다. 이전 차단 사항인 false-green health와 Micrometer capability 손실이 해소됐고, 보안·API·동시성 회귀는 발견되지 않았다.

## 새 검증

| 명령 또는 검사 | 결과 |
|---|---|
| `./gradlew test detekt --no-daemon --no-configuration-cache` | PASS, XML 345개, tests 3,283, failures 0, errors 0, skipped 0 |
| `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=0.6.0 ./gradlew checkBinaryCompatibility --no-daemon --no-configuration-cache` | PASS, artifacts 16, ignored 4, unknown 0 |
| Core와 Micrometer diagnostics targeted `cleanTest` + `--no-build-cache` | PASS, Core 5개와 Micrometer 17개 |
| capability validator unit/self/static | PASS, unit 11개 |
| README JVM 25·locale inventory 검사 | PASS |
| `git diff --check` | PASS |

기본 configuration cache를 사용한 root 실행은 기존 `detektProductionSourceGuard`가 Gradle script `Project`를 직렬화하지 못해 테스트 시작 전에 실패했다. 동일 범위를 `--no-configuration-cache`로 실행하면 통과하므로 Issue #533의 회귀로 분류하지 않았다.

## 남은 관찰 항목

`LeaderBackendDiagnosticsProvider`의 timeout은 SPI 계약이며 framework가 호출을 강제로 중단하는 deadline은 아니다. 현재 provider는 수동 상태를 즉시 읽거나 즉시 `UNKNOWN`을 반환하므로 차단 사항이 아니다. 향후 네트워크 I/O를 수행하는 active probe를 추가할 때는 호출자 deadline, timeout 전달, 연결 단절 회귀 테스트를 함께 추가해야 한다.

## 최종 판정

`PASS`: P0=0, P1=0, P2=0, P3=0.
