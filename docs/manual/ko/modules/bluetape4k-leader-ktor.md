---
manualId: "bluetape4k-leader-ktor"
id: "bluetape4k-leader-ktor"
title: "Ktor 연동"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-ktor"
sourceDir: "leader-ktor"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-ktor
---

# Ktor 연동

> 라이브러리 모듈

## 제공하는 기능 {#problem}

`LeaderElectionPlugin`, `leaderScheduled`, 선택적인 관리 route로 suspend elector를 Ktor 3에 연결합니다. Job 수명을 애플리케이션 생명주기에 맞춥니다.

## 사용하기 좋은 경우 {#when-to-use}

Ktor 서비스가 소유한 코루틴 job을 노드 하나에서만 실행할 때 사용합니다. Proxy와 어노테이션 방식이 필요하면 Spring 연동을 고릅니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-ktor`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-ktor")
}
```

## 핵심 개념 {#concepts}

Plugin은 `SuspendLeaderElector` 하나를 해석합니다. 예약 작업은 애플리케이션 소유 coroutine scope에서 실행되고 종료 시 취소됩니다. 경쟁에서 밀린 회차는 서버 실패가 아니라 skip입니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
install(LeaderElectionPlugin) {
    leaderElection = mySuspendElector
}
leaderScheduled("projection-refresh", 1.minutes) {
    refreshProjection()
}
```

## 작업별 API {#api-by-task}

plugin으로 elector를 등록하고 `leaderScheduled`로 주기적 suspend 작업을 만듭니다. 관리 route는 인증된 운영 경계 안에서만 노출하세요.

## 권장 패턴 {#patterns}

plugin은 한 번만 설치하고 lock name을 안정적으로 정합니다. 본문은 lease보다 짧게 끝내거나 안전하게 연장하며 shutdown 취소도 계약에 포함하세요.

## 연동 {#integrations}

모든 suspend 백엔드를 공급할 수 있습니다. ktor-app 예제에 plugin 설치, 예약, 생명주기가 함께 나와 있습니다.

## 설정 {#configuration}

백엔드 모듈에서 elector를 설정한 뒤 schedule interval/delay와 Ktor scope 소유권을 정합니다. Job마다 별도 client를 몰래 만들지 마세요.

## 실패 유형과 해결 방법 {#failures}

플러그인이나 선출기 누락은 시작 설정 실패입니다. 선출기를 직접 호출하면 백엔드 오류와 본문 오류가 호출자에게 전달됩니다. `Application.leaderScheduled`는 취소가 아닌 `Exception`을 잡아 `WARN` 로그를 남기고 해당 회차만 건너뛴 뒤 다음 주기를 계속합니다. 일반적인 경쟁도 실행을 건너뜁니다. 취소되면 예약을 멈추고 선출기가 소유 상태를 정리합니다.

## 운영 {#operations}

예약 시도, 선출 실행, skip, 실패, 실행 시간, 종료 완료를 측정합니다. 관리 endpoint는 인증하고 tag 수를 제한하세요.

## 테스트 {#testing}

Ktor test application에서 plugin 설정과 schedule 수명을 검사하고 소유권은 백엔드 통합 테스트로 확인합니다. Acquire와 본문 중 shutdown도 검증하세요.

## 학습 경로와 예제 {#workshops}

ktor-app을 실행한 뒤 선택한 백엔드 페이지로 이동하세요. 명시적 scheduling과 AOP 어노테이션 중 무엇이 나은지는 Spring 연동과 비교합니다.

## 제약 사항 {#limitations}

이 모듈은 job을 예약하지만 durable scheduling, 누락 회차 복구, cron 영속화, exactly-once 전달을 제공하지 않습니다.

## 근거 자료 {#sources}

[Plugin](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt) · [Scheduling 확장](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/ApplicationExt.kt) · [안정판 안내](../../../../leader-ktor/README.ko.md)
