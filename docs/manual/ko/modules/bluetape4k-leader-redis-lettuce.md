---
manualId: "bluetape4k-leader-redis-lettuce"
id: "bluetape4k-leader-redis-lettuce"
title: "Redis Lettuce 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-redis-lettuce"
sourceDir: "leader-redis-lettuce"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce
---

# Redis Lettuce 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Lettuce connection, token 소유 Redis key, Lua compare-and-delete/extend로 단일·고정 슬롯 그룹·전략 선출을 구현합니다. 블로킹과 코루틴 API를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Redis를 이미 운영하고 Lettuce의 connection 모델과 명시적인 script 방식을 선호할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce")
}
```

## 핵심 개념 {#concepts}

고유 token이 TTL key를 소유합니다. Lua script가 release와 extension을 조건부로 수행합니다. 전략 선출은 후보 메타데이터를 저장하고 Core 전략을 적용합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = LettuceLeaderElector(connection)
elector.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

connection 확장/factory, blocking/suspend 단일·group elector, 전략 elector를 사용합니다. Connection은 호출자가 소유합니다.

## 권장 패턴 {#patterns}

전용 key prefix를 쓰고 Redis client/connection 생명주기를 명시합니다. 본문은 멱등하게 만들고 failover 중 동작을 미리 정하세요.

## 연동 {#integrations}

Spring은 Lettuce 자원으로 factory를 만들 수 있습니다. strategic-election과 rate-limiter 예제에 후보와 그룹 패턴이 나와 있습니다.

## 설정 {#configuration}

wait/lease/minimum lease, auto-extension, key prefix, group size, retry, client timeout, topology refresh, Redis 내구성 정책을 설정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. Timeout, MOVED/topology, script, 인증, extension 실패는 예외로 드러납니다. 만료 뒤 pause된 이전 작업과 겹칠 수 있습니다.

## 운영 {#operations}

command 지연, reconnect, topology 변경, script 실패, key/TTL, extension 실패, skip 비율, Redis 메모리를 관측합니다.

## 테스트 {#testing}

실제 Redis에서 두 connection, token-safe release, 만료, 그룹 슬롯, 전략 registry, failover, suspend 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

strategic-election으로 후보 점수 방식을, rate-limiter로 그룹 슬롯을 익히세요. Redisson의 고수준 client 모델과도 비교합니다.

## 제약 사항 {#limitations}

Redis 가용성과 failover 의미가 job 경로에 들어옵니다. Redis token은 외부 DB의 fencing이 아닙니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Lettuce Redis leader contract 지도

[![Lettuce Redis leader contract 지도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-class-01.svg)

_배포본 README: [`leader-redis-lettuce/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-lettuce/README.ko.md)_

### Lettuce slot-token acquire release and crash recovery 흐름

[![Lettuce slot-token acquire release and crash recovery 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-sequence-02.svg)

_배포본 README: [`leader-redis-lettuce/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-lettuce/README.ko.md)_

### Lettuce minLeaseTime backend TTL delegation 흐름

[![Lettuce minLeaseTime backend TTL delegation 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-redis-lettuce-sequence-03.svg)

_배포본 README: [`leader-redis-lettuce/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-redis-lettuce/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/LettuceLeaderElector.kt) · [Lua 지원](../../../../leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/lettuce/script/RedisScript.kt) · [안정판 안내](../../../../leader-redis-lettuce/README.ko.md)

