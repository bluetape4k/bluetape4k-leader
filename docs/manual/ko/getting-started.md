---
title: "시작하기"
description: "로컬 elector로 경쟁 시 건너뛰는 규칙부터 확인한 뒤, 운영 환경에서는 elector만 분산 백엔드 구현으로 바꿉니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 시작하기

로컬 elector로 경쟁 시 건너뛰는 규칙부터 확인한 뒤, 운영 환경에서는 elector만 분산 백엔드 구현으로 바꿉니다.

## 버전은 중앙 BOM 하나로 관리한다

애플리케이션에서는 bluetape4k 중앙 플랫폼과 핵심 라이브러리를 추가합니다. 사용자가 선택할 버전은 `bluetape4k-dependencies` 하나면 충분합니다. Leader BOM을 별도의 버전 축으로 만들지 않습니다.

```kotlin
implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
```

## 가장 작은 계약부터 실행한다

```kotlin
val elector = LocalLeaderElector()
val result = elector.runIfLeader("daily-report") { generateReport() }
if (result == null) logger.debug { "다른 인스턴스가 daily-report를 실행했다" }
```

모든 인스턴스가 같은 lock name을 사용해야 합니다. 빈 이름은 허용되지 않습니다. 기본값은 최대 5초 대기, 60초 리스, 최소 보유 시간 없음, 자동 연장 비활성입니다.

## 운영 백엔드로 옮긴다

보호할 작업과 멱등성 규칙은 그대로 두고 `LocalLeaderElector`만 분산 백엔드 구현으로 교체합니다. `waitTime`은 실제로 허용할 대기 시간만큼, `leaseTime`은 정상 실행 시간과 지연 변동을 합친 값보다 길게 잡습니다. 배포 전에는 두 인스턴스 경쟁, 작업 예외, 백엔드 단절, 프로세스 재시작을 반드시 시험합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt`](../../../leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt`](../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt)
- [`leader-core/src/test/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElectorTest.kt`](../../../leader-core/src/test/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElectorTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](index.md)
- [실행 결과의 의미](core/result-semantics.md)
- [백엔드 선택](guides/backend-selection.md)
