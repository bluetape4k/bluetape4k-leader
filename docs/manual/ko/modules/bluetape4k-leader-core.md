---
manualId: "bluetape4k-leader-core"
id: "bluetape4k-leader-core"
title: "Leader 핵심 라이브러리"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-core"
sourceDir: "leader-core"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-core
---

# Leader 핵심 라이브러리

> 라이브러리 모듈

## 제공하는 기능 {#problem}

모든 백엔드가 따르는 선출 계약과 블로킹·`CompletableFuture`·가상 스레드·코루틴용 로컬 구현을 제공합니다. 경쟁에서 밀리면 `null`, 본문이 실패하면 예외가 전달됩니다.

## 사용하기 좋은 경우 {#when-to-use}

한 JVM 안의 조율과 빠른 테스트에는 로컬 elector를 씁니다. 프로세스 사이를 조율하려면 백엔드 모듈을 선택하세요.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-core`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
}
```

## 핵심 개념 {#concepts}

단일 선출은 lock 하나를 보호하고 그룹 선출은 고정 슬롯을 최대 `maxLeaders`개까지 허용합니다. `LeaderRunResult`는 `Elected`, `Skipped`, `ActionFailed`를 구분합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = LocalLeaderElector(
    LeaderElectionOptions(waitTime = 500.milliseconds, leaseTime = 30.seconds)
)
val result = elector.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

본문의 정상 결과가 `null`일 수 있으면 `runIfLeaderResult`를 씁니다. 호출자의 실행 모델에 맞춰 suspend, async, 가상 스레드, 그룹, 전략 elector를 고릅니다.

## 권장 패턴 {#patterns}

lock name은 업무 기준으로 안정적으로 유지합니다. 본문은 멱등하게 만들고 lease에 여유를 두며, `autoExtend`는 지원되는 단일 리더 경로에서만 씁니다.

## 연동 {#integrations}

모든 백엔드가 이 인터페이스를 구현합니다. Spring은 어노테이션을 factory에 연결하고 Ktor는 suspend 작업을 예약하며 Micrometer는 결과를 계측합니다.

## 설정 {#configuration}

`waitTime`은 획득 시간, `leaseTime`은 백엔드별 임대 시간, `minLeaseTime`은 성공 후 최소 보유 시간을 정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`/`Skipped`, 백엔드 장애는 예외입니다. 취소는 다시 던지고 블로킹 interrupt는 flag를 복원합니다.

## 운영 {#operations}

획득, skip, 본문 실패, 백엔드 실패, 연장 실패를 따로 측정하세요. Listener의 lease 정보는 소유권 증명이 아닙니다.

## 테스트 {#testing}

승자·패자, 본문 예외, 취소, 해제, 그룹 용량, listener 순서를 검증합니다. 분산 백엔드는 실제 인프라 통합 테스트도 필요합니다.

## 학습 경로와 예제 {#workshops}

생명주기와 모델 선택 가이드를 읽고 batch-scheduler와 strategic-election 예제를 실행한 뒤 백엔드를 고르세요.

## 제약 사항 {#limitations}

로컬 elector는 JVM 하나만 조율합니다. 분산 lease도 외부 부수 효과를 되돌리지 못하므로 중복이 위험하면 멱등성이나 fencing이 필요합니다.

## 근거 자료 {#sources}

[Core 계약](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt) · [옵션](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt) · [계약 테스트](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)

