---
title: "관측과 운영"
description: "선출 결과, 실행 시간, 소유권 상실, 백엔드 상태를 관측하되 lock name을 무제한 metric label로 만들지 않습니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 관측과 운영

선출 결과, 실행 시간, 소유권 상실, 백엔드 상태를 관측하되 lock name을 무제한 metric label로 만들지 않습니다.

## 봐야 할 신호

elected, skipped, 작업 실패, 실행 시간, 활성 소유권, 리스 연장 결과를 따로 봅니다. skipped 증가는 정상 경쟁일 수도 있고 owner 정체일 수도 있으므로 실행 시간, 상태, 백엔드 지연과 함께 해석합니다. 모든 skip에 경보를 울리기보다 지속적인 실패와 non-transient 연장 오류에 알립니다.

## 카디널리티

lock name에는 tenant나 job 식별자가 들어가기 쉽습니다. 이를 가공 없이 metric tag로 사용하면 안 됩니다. 이름은 애플리케이션에서 정규화하고, 고정된 이름은 미리 등록합니다. recorder가 새로운 이름에 남기는 경고를 확인하고 더는 쓰지 않는 동적 이름은 등록 해제합니다. 지표는 안정된 job family 단위로 집계하고, 정확한 식별자는 보존 범위를 통제할 수 있는 구조화 로그나 trace에 남깁니다.

## 런북

작업이 멈춘 것으로 보이면 마지막 elected/completed 이벤트와 이력의 effective status를 확인하고, 백엔드 연결과 리스 만료를 점검합니다. 이전 작업이 아직 외부 쓰기를 할 수 있는지도 판단합니다. 그 뒤에야 강제 정리나 재실행을 결정합니다. 누가 어떤 fencing 근거를 확인해 결정했는지 기록합니다.

## 릴리스 소스

- [`leader-micrometer/README.ko.md`](../../../../leader-micrometer/README.ko.md)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatusExtensions.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatusExtensions.kt)
- [`examples/prometheus-dashboard/README.ko.md`](../../../../examples/prometheus-dashboard/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Micrometer 연동](../frameworks/micrometer.md)
- [식별자, 상태, 이력](identity-state-and-history.md)
- [리더 선출 테스트](testing.md)
