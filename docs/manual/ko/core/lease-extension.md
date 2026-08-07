---
title: "리스 연장"
description: "현재 소유권이 일치할 때만 갱신하고, 연장 결과를 운영 판단에 반영합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 리스 연장

현재 소유권이 일치할 때만 갱신하고, 연장 결과를 운영 판단에 반영합니다.

## 자동 연장과 명시적 연장

지원되는 단일 리더 백엔드에서 `autoExtend=true`를 설정하면 공통 watchdog이 시작됩니다. 선출된 호출 범위 안에서는 `LockExtender.extendActiveLockDetailed()` 또는 suspend variant를 직접 호출할 수도 있습니다. 0.5.0에서 그룹 자동 연장은 일반 계약이 아닙니다.

## 결과 처리

`Extended`에는 백엔드가 확인할 수 있는 범위에서 계산한 만료 시각이 들어갑니다. `NotHeld`는 token 불일치, 만료, takeover를 뜻합니다. `WrongThread`는 Redisson의 thread-bound lock을 다른 thread에서 사용한 경우입니다. `BackendError`는 transient 여부를 보존합니다. Boolean 단축 API는 세부 정보를 숨기므로 운영 런북의 근거로 쓰지 않는 편이 좋습니다.

## 안전 규칙

백엔드는 현재 owner 조건 아래에서 원자적으로 연장해야 합니다. 사용자가 더 긴 시간으로 직접 연장하면 그 deadline을 기록해 다음 watchdog tick이 리스를 오히려 줄이지 않도록 합니다. 소유권을 잃었다면 보호하던 쓰기를 중단해야 합니다. 만료 뒤 연장에 성공해도 이미 수행한 작업이 소급해서 안전해지지는 않습니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [리스 수명 주기](../guides/lease-lifecycle.md)
- [실패와 취소](../guides/failure-and-cancellation.md)
