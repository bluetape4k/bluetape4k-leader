---
manualId: "bluetape4k-leader-spring-boot"
id: "bluetape4k-leader-spring-boot"
title: "Spring Boot 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-spring-boot"
sourceDir: "leader-spring-boot"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-spring-boot
---

# Spring Boot 연동

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Spring Boot auto-configuration, 백엔드 factory, `@LeaderElection`/`@LeaderGroupElection`용 compile-time-woven AOP, SpEL lock name, 실패 정책, lock scope 도구를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Spring이 관리하는 job에 선언적으로 선출을 붙일 때 사용합니다. 소유권 흐름을 애플리케이션 코드에 명시하려면 elector를 직접 호출하는 편이 낫습니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-spring-boot")
}
```

## 핵심 개념 {#concepts}

Aspect는 factory를 고르고 옵션을 만든 뒤 acquire에 성공했을 때만 메서드를 호출합니다. Private 메서드는 가로채지 않습니다. 이 프로젝트는 AspectJ compile-time weaving을 쓰므로 `@EnableAspectJAutoProxy`가 활성화 스위치가 아닙니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
@Service
class Jobs {
    @LeaderElection(name = "daily-settlement", leaseTime = "30m")
    fun settle(): SettlementReport? = settlementService.settle()
}
```

## 작업별 API {#api-by-task}

한 명만 실행하면 `@LeaderElection`, 제한된 병렬 실행이면 `@LeaderGroupElection`을 씁니다. `@LeaderElectionBackend`로 factory를 고르며 `LockAssert`/`LockExtender`는 활성 scope 안에서만 동작합니다.

## 권장 패턴 {#patterns}

어노테이션 메서드는 woven aspect가 접근할 수 있게 만들고 SpEL name은 안정적으로 검증합니다. 신뢰하지 않는 식의 method invocation은 켜지 말고 `FAIL_OPEN_RUN`은 멱등 작업에만 사용하세요.

## 연동 {#integrations}

사용 가능한 백엔드 client와 Micrometer recorder를 auto-configure할 수 있습니다. batch-scheduler와 webhook-poller에서 어노테이션 job을 볼 수 있습니다.

## 설정 {#configuration}

`bluetape4k.leader` 기본값, 백엔드별 property, AOP order, failure mode, SpEL 정책, single/group lease, factory bean 선택을 설정합니다.

## 실패 유형과 해결 방법 {#failures}

`RETHROW`는 백엔드 실패를 전달하고 `SKIP`은 본문을 건너뛰며 `FAIL_OPEN_RUN`은 소유권 없이 실행합니다. 잘못된 어노테이션과 모호한 factory는 시작 시 실패해야 합니다. 장기 stream은 명시적 renewal이 필요합니다.

## 운영 {#operations}

선출/skip/실패, 선택한 factory, 해석된 lock name, 실행 시간, extension을 관측합니다. Fail-open 결정은 runbook에 남기세요.

## 테스트 {#testing}

application-context 테스트로 auto-configuration과 CTW interception을 검증합니다. Private/non-intercepted 메서드, SpEL, meta-annotation, failure mode, suspend/reactive 결과, 취소를 다룹니다.

## 학습 경로와 예제 {#workshops}

batch-scheduler로 시작한 뒤 webhook-poller와 prometheus-dashboard를 보세요. Client와 lease 운영은 선택한 백엔드 매뉴얼에서 확인합니다.

## 제약 사항 {#limitations}

AOP가 임의의 부수 효과를 exactly-once로 바꿔 주지는 않습니다. Self/private 호출과 장기 stream은 경계를 직접 검증해야 하며 `FAIL_OPEN_RUN`은 의도적으로 중복을 허용합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader spring boot 아키텍처

[![leader spring boot 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg)

_배포본 README: [`leader-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-spring-boot/README.ko.md)_

### 처리 순서: AOP-triggered runIfLeader 다이어그램

[![처리 순서: AOP-triggered runIfLeader 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-sequence-01.svg)

_배포본 README: [`leader-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-spring-boot/README.ko.md)_

### — reentrant @LeaderElection 다이어그램

[![— reentrant @LeaderElection 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-spring-boot-sequence-02.svg)

_배포본 README: [`leader-spring-boot/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-spring-boot/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Auto-configuration](../../../../leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderElectionAutoConfiguration.kt) · [Aspect](../../../../leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt) · [안정판 안내](../../../../leader-spring-boot/README.ko.md)

