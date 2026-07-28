# 문제 271 위임 연장 일시 중단

## 맥락

Issue #271은 코루틴 기본 백엔드 확장 대리자에서 `runBlocking` 브리지를 제거했습니다. 영향을 받는 모듈은 Lettuce, Redisson, MongoDB, Hazelcast 및 Exposed R2DBC입니다.

## 결정

`SuspendExtendDelegate`를 코루틴 기본 SPI로 도입하고 대리자 로컬을 정적으로 입력하여 정지 선택기를 새로운 `LeaderLeaseAutoExtender.start(..., SuspendExtendDelegate, ...)` 오버로드로 라우팅합니다. 정지 감시는 기존 실행기의 스케줄러 흐름을 유지하지만 `runBlocking`를 코어 감시로 이동하지 않고 개인 코루틴 범위에서 백엔드 확장을 실행합니다.

## 결과

`LockExtender`를 일시 중단하고 감시 경로는 이제 `extendSuspend()`를 직접 호출합니다. `SuspendExtendDelegate`의 동기화 오용은 `BackendError(UnsupportedOperationException)`를 반환하고 `isHeld()`는 false를 반환하므로 실수로 동기화 호출이 차단되는 대신 눈에 띄게 failure합니다.

## 검증

- `./gradlew :bluetape4k-leader-core:test`
- `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-hazelcast:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin`
- `rg -n "runBlocking|: ExtendDelegate|import io\\.bluetape4k\\.leader\\.internal\\.ExtendDelegate" ... -g '*Suspend*ExtendDelegate.kt'`는 대상 백엔드 모듈과 일치하는 항목을 반환하지 않았습니다.

## 향후 지침

코루틴 네이티브 백엔드를 추가할 때 `SuspendExtendDelegate`를 구현하고, 선택자 대리자를 `SuspendExtendDelegate`로 입력하고, 일시 중지 대리자 메서드에서 광범위한 `catch (Exception)` 앞에 `CancellationException`를 다시 던집니다.
