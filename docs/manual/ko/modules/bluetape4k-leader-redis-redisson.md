---
manualId: "bluetape4k-leader-redis-redisson"
id: "bluetape4k-leader-redis-redisson"
title: "Redis Redisson 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-redis-redisson"
sourceDir: "leader-redis-redisson"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson
---

# Redis Redisson 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Redisson lock/semaphore로 단일·그룹·전략 선출을 구현하며 블로킹과 코루틴 API를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

이미 Redisson을 Redis client로 쓰고 직접 Lua script보다 distributed-object API를 선호할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-redisson")
}
```

## 핵심 개념 {#concepts}

acquire할 때 항상 명시적인 `leaseTime`을 넘기므로 Redisson native watchdog은 꺼집니다. 자동 연장을 켜면 Core의 `LeaderLeaseAutoExtender`만 동작합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = RedissonLeaderElector(redissonClient)
elector.runIfLeader("webhook-poller") { pollWebhooks() }
```

## 작업별 API {#api-by-task}

blocking/suspend 단일·group·전략 elector와 factory를 사용합니다. 그룹 선출은 semaphore 방식 permit에 대응합니다.

## 권장 패턴 {#patterns}

호출자 소유 client 하나, 안정적인 lock name, 명시적인 lease 크기, 멱등 본문을 사용하세요. Shared `autoExtend` 사용 여부도 의도적으로 정합니다.

## 연동 {#integrations}

Spring factory는 `RedissonClient`를 사용합니다. redisson-watchdog 예제에서 native watchdog과 라이브러리 extender의 차이를 설명합니다.

## 설정 {#configuration}

wait/lease/minimum lease, auto-extension, group size와 Redis topology, client timeout, retry, codec을 각각 설정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. Interrupt, Redis/client 장애, 소유권 상실, extend/release 오류는 백엔드 분류에 따라 예외로 드러납니다.

## 운영 {#operations}

Redis 지연, connection 상태, lock TTL, permit 사용량, extension 실패, skip 비율, failover를 관측하세요. Native watchdog이 갱신했다고 가정하면 안 됩니다.

## 테스트 {#testing}

두 client, 명시적 lease 만료, shared extender, owner-safe release, group permit, 전략 registry, failover, suspend 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

운영 적용 전에 redisson-watchdog를 실행하고 rate-limiter와 strategic-election 예제로 이어가세요.

## 제약 사항 {#limitations}

명시적 lease는 긴 pause 중 만료될 수 있습니다. Redisson 소유권도 다른 저장소의 쓰기를 fence하지 못합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader redis redisson 클래스 구조도

[![leader redis redisson 클래스 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-class-01.svg)

_배포본 README: [`leader-redis-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-redisson/README.ko.md)_

### 1 — acquire/release crash recovery 다이어그램

[![1 — acquire/release crash recovery 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-sequence-02.svg)

_배포본 README: [`leader-redis-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-redisson/README.ko.md)_

### 2 — updateLeaseTime minLeaseTime 다이어그램

[![2 — updateLeaseTime minLeaseTime 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-redisson-sequence-03.svg)

_배포본 README: [`leader-redis-redisson/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-redisson/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/RedissonLeaderElector.kt) · [Shared extender delegate](../../../../leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redisson/internal/RedissonLockExtendDelegate.kt) · [안정판 안내](../../../../leader-redis-redisson/README.ko.md)

