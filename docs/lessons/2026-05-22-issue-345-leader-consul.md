# 2026-05-22 이슈 #345 리더 Consul

## L1. bluetape4k 소유 경계 뒤에 Consul 종속성 결정 유지

첫 번째 Consul 슬라이스는 bluetape4k 소유 DTO 및 옵션만 게시해야 합니다. 구현 경계는 `internal`이므로 런타임 슬라이스는 오래된 타사 Consul 클라이언트를 공용 API에 고정하지 않고도 Java 21 HttpClient를 사용할 수 있습니다.

## L2. 달리 입증될 때까지 엔드포인트 문자열을 자격 증명 보유로 취급합니다.

`baseUrl`가 `user:password@host`를 허용하는 경우 `aclToken` 마스킹으로는 충분하지 않습니다. 생성 시 URI 사용자 정보를 거부하고 전용 토큰 필드를 통해 Consul ACL 자료를 강제 적용합니다.

## L3. 새로운 게시 가능 모듈에는 예약된 작업 흐름과 PR 작업 흐름 연결이 모두 필요합니다.

`leader-consul`의 경우 `settings.gradle.kts`, BOM, 루트 README 쌍, CI 경로 필터, 모듈별 테스트 작업 및 요약 집계기 `needs:` 목록에 모듈을 추가합니다. 이들 중 하나라도 누락되면 자동으로 적용 범위가 제거됩니다.

## L4. 테스트 리소스 파일은 백엔드 모듈 패리티의 일부입니다.

초기 계약 전용 백엔드 모듈에도 형제 모듈에서 사용하는 JUnit 및 로그백 테스트 리소스가 포함되어야 합니다. Claude Review는 PR 생성 전에 누락된 파일을 발견했습니다.

## L5. 플랫폼 버전은 실제 Gradle 그래프로 검증해야 합니다.

이제 상위 릴리스 분기에서는 `bluetape4k-bom` 1.9.0을 사용합니다. `libs.versions.toml`뿐만 아니라 해결된 아티팩트도 검증하세요. 사전 검증을 통해 `bluetape4k-testcontainers:1.9.0` 및 ConsulServer 고정 장치를 사용할 수 있음이 검증되었습니다.

## L6. waitTime이 TTL을 초과할 수 있는 경우 대기 중인 Consul 세션을 갱신합니다.

Consul `acquire`는 다른 세션이 키를 소유하고 있는 동안 `false`를 반환하지만 대기 중인 후보의 자체 세션은 여전히 TTL에 만료됩니다. `waitTime`가 세션 TTL보다 길고 후보가 폴링 중에 갱신되지 않는 경우 Consul는 결국 완전한 인수 대신 `invalid session`를 반환합니다. 갱신에는 활성 보유자와 대기 후보자 모두가 포함되어야 합니다.

## L7. 정리는 중단된 minLeaseTime 대기 동안 유지되어야 합니다.

`minLeaseTime` 시행은 백엔드 잠금을 해제하기 전에 절전 모드로 전환됩니다. 해당 절전 모드가 중단되면 중단 플래그를 복원하고 백엔드 `release`/`destroy`를 계속합니다. 그렇지 않으면 잠금은 Consul TTL 만료까지 유지되며 중단 failure로 작업 결과를 마스킹할 수 있습니다.

## L8. 수동 소유권 검증은 Consul 세션을 확장해서는 안 됩니다.

`ExtendDelegate.isHeld()`는 읽기/검증 작업입니다. Consul의 경우 `session/renew`를 호출하는 것이 아니라 KV 항목을 읽고 세션 ID를 비교하여 구현합니다. 그렇지 않으면 어설션이나 상태 프로브가 명시적인 `extend` 또는 감시 제어 외부에서 임대를 연장할 수 있습니다.

## L9. Consul 정리 일시 중단은 NonCancellable 및 세션 범위여야 합니다.

선출된 기관이 정지된 동안 코루틴 취소가 발생할 수 있습니다. 일시 중지 선택기는 호출자의 `CancellationException`를 다시 발생시켜야 하지만 먼저 `NonCancellable` 섹션에서 Watchdog 닫기, KV 릴리스 및 세션 삭제를 실행해야 합니다. 정리는 획득한 Consul 세션 ID로 범위를 유지해야 대체 소유자를 해제할 수 없습니다.

## L10. Suspend Consul 확장은 SuspendExtendDelegate를 사용해야 합니다.

`runBlocking`를 통해 일시 중지 잠금 확장을 연결하지 마세요. 일시 중지 선택기는 `SuspendExtendDelegate`를 생성하고 `LeaderLockHandle.Real` 및 `LeaderLeaseAutoExtender.start(...)` 모두에 동일한 참조를 전달해야 하므로 `LockExtender.extendActiveLockSuspend(...)` 및 감시 장치는 `extendSuspend()`를 직접 호출합니다.

## L11. 임시 대기열 잠금이 아닌 고정 KV 슬롯으로 모델 Consul 그룹

Consul에는 기본 분산 세마포어가 아닌 KV 획득/릴리스 의미 체계가 있습니다. 안정적인 `group/{lockName}/slot-{index}` 키와 선택된 작업당 하나의 Consul 세션을 사용합니다. 이를 통해 상태 스냅샷을 예측 가능하게 만들고, 세션 TTL이 충돌 복구를 처리할 수 있게 하며, `LockAssert` / `LockExtender`가 단일 리더 선택과 동일한 세션 범위 위임을 재사용할 수 있습니다.

## L12. Consul 시계 게시를 명시적으로 유지

핵심 리스너/이벤트 데코레이터는 Consul 선택기와 함께 작동하지만 백엔드 네이티브 Consul 차단 쿼리 감시 게시자는 자동으로 자동 생성되어서는 안 됩니다. 시계 수명 주기, 재시도/백오프, ACL 범위 및 데이터 센터 정책은 애플리케이션에 대한 운영 선택 사항입니다. 이후 문제에서 해당 런타임 계약을 정의하지 않는 한 제외 사항을 문서화하세요.
