---
manualId: "bluetape4k-leader-consul"
id: "bluetape4k-leader-consul"
title: "Consul 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-consul"
sourceDir: "leader-consul"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-consul
---

# Consul 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

> **프리뷰:** 운영에 적용하기 전에 API와 운영 동작을 직접 검증하세요.

Consul session과 KV acquire/release로 단일 선출과 고정 슬롯 그룹 선출을 구현한 프리뷰 백엔드입니다. 블로킹, future, 코루틴, Spring factory 경로를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

이미 Consul을 운영하고 session 모델이 작업에 맞을 때 선택하세요. ACL, session, watch 운영 부담을 감수할 이유가 없다면 선출만 위해 Consul을 새로 두지는 않는 편이 낫습니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-consul`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-consul")
}
```

## 핵심 개념 {#concepts}

session이 encode된 KV key를 소유합니다. TTL은 10~86,400초이며 기본 `lockDelay`는 0입니다. 만료 뒤 이전 holder가 아직 실행 중이면 새 holder와 겹칠 수 있습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = ConsulLeaderElector(
    ConsulEndpoint("http://localhost:8500"),
    ConsulLeaderElectionOptions(
        leaderOptions = LeaderElectionOptions(leaseTime = 10.seconds)
    )
)
elector.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

블로킹은 `ConsulLeaderElector`와 group, 코루틴은 `ConsulSuspendLeaderElector`와 group을 씁니다. 호출자가 소유한 `ConsulEndpoint`에 URL, datacenter, token, timeout을 담습니다.

## 권장 패턴 {#patterns}

애플리케이션별 key prefix와 최소 권한 ACL을 사용하세요. 본문은 멱등하게 만들고 겹침이 위험하면 외부 fencing을 둡니다.

## 연동 {#integrations}

Spring은 호출자가 제공한 `ConsulEndpoint`로 factory를 만듭니다. Core listener decorator는 쓸 수 있지만 장기 blocking-query watch는 애플리케이션이 운영합니다.

## 설정 {#configuration}

key/session prefix, request timeout, TTL 범위의 lease, wait time, group size, `lockDelay`를 정합니다. Client와 agent 생명주기는 호출자 소유입니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 skip입니다. HTTP, ACL, session, timeout 실패는 예외로 드러납니다. `lockDelay=0`이면 만료된 이전 작업과 새 작업이 잠시 겹칠 수 있습니다.

## 운영 {#operations}

session renewal, KV 지연, ACL 실패, orphan session, skip 비율을 관측하세요. Runbook에는 datacenter와 prefix를 함께 적습니다.

## 테스트 {#testing}

실제 Consul로 single/group과 blocking/suspend를 검증합니다. TTL 경계, owner payload, 오류 분류, release도 테스트하세요.

## 학습 경로와 예제 {#workshops}

consul-maintenance 예제를 실행하고 소유권·장애 의미를 기준으로 etcd, ZooKeeper와 비교하세요.

## 제약 사항 {#limitations}

프리뷰라 API와 운영 계약이 바뀔 수 있습니다. Consul lock은 fencing이 아니며 endpoint와 agent 생명주기도 라이브러리가 관리하지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 현재 개발 브랜치가 아니라 `0.4.0` 배포 태그의 README 자산을 바이트 단위로 그대로 옮긴 것입니다. 따라서 이후 SNAPSHOT 변경이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 SVG 원본이 열립니다.

### leader-consul 아키텍처

[![leader-consul 아키텍처](../../assets/readme-diagrams/leader-consul-architecture-01.png)](../../assets/readme-diagrams/leader-consul-architecture-01.svg)

_배포본 README: [`leader-consul/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-consul/README.ko.md)_

### Consul acquire release 시퀀스 다이어그램

[![Consul acquire release 시퀀스 다이어그램](../../assets/readme-diagrams/leader-consul-sequence-02.png)](../../assets/readme-diagrams/leader-consul-sequence-02.svg)

_배포본 README: [`leader-consul/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-consul/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElector.kt) · [옵션](../../../../leader-consul/src/main/kotlin/io/bluetape4k/leader/consul/ConsulLeaderElectionOptions.kt) · [안정판 안내](../../../../leader-consul/README.ko.md)

