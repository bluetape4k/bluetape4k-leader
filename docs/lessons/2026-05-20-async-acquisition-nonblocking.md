# 비동기 획득으로 호출자 실행자가 무료로 유지되어야 합니다.

## 맥락

Issue #320은 단조로운 기한 수정 후 `CompletableFuture` 리더 획득을 강화했습니다. 일부 비동기 선택기는 미래를 반환했지만 여전히 호출자 실행기에서 차단 `tryLock(waitTime, leaseTime)` 루프를 래핑하므로 재시도 대기가 경합 하에서 제공된 실행기를 차지할 수 있습니다.

## 결정

이를 모든 백엔드에 대한 IO 기반 비차단이 아닌 CPU 제한 비동기 경계로 설명합니다.

- Lettuce 단일 리더 획득은 기본 Lettuce 비동기 Redis 명령을 사용할 수 있습니다.
- MongoDB `MongoCollection`는 여기에서 동기화 드라이버를 사용하므로 각 데이터베이스 시도는 여전히 I/O를 차단합니다. `VirtualThreadExecutor`에서 이러한 시도를 격리하고 `CompletableFuture.delayedExecutor`를 사용하여 재시도 대기를 예약합니다.
- Mongo 동기화 드라이버 획득이 IO 비차단이라고 주장하지 마세요. 보장은 호출자가 제공한 작업 실행자가 획득 재시도 절전 모드에 의해 유지되지 않는다는 것입니다.

## 결과

`MongoLeaderElector`, `MongoLeaderGroupElector` 및 `LettuceLeaderElector` 비동기 경로는 더 이상 호출자 실행기에서 차단 대기 루프 `tryLock` 호출을 래핑하지 않습니다. 새로운 경합 테스트는 단일 스레드 호출 실행기를 사용하고 획득이 시간 초과를 기다리는 동안 마커 작업이 실행될 수 있음을 증명합니다.

## 검증

- `./gradlew :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-mongodb:test --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-build-cache --stacktrace`

## 퓨쳐 가드

비동기 리더-선출자 경로를 변경할 때 획득 시도와 재시도 대기를 모두 검증하세요. I/O를 차단하는 동기화 드라이버를 격리하는 데 가상 스레드가 허용되지만 재시도 대기는 호출자 실행기 내부에서 휴면하는 대신 타이머/지연 메커니즘을 사용해야 합니다.
