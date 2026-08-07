---
title: "학습 경로"
description: "5분짜리 계약 확인에서 시작해 백엔드 운영과 장애 훈련까지 단계별로 익힙니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 학습 경로

5분짜리 계약 확인에서 시작해 백엔드 운영과 장애 훈련까지 단계별로 익힙니다.

## 1단계 — 실행 의미

로컬 contender 두 개를 동시에 실행해 한쪽만 작업을 수행하고 다른 쪽은 `null`을 받는지 확인합니다. 이어서 `runIfLeaderResult()`로 `Skipped`와 선출된 작업이 반환한 `null`을 구분해 봅니다. 작업에서 예외를 던져 호출자에게 그대로 전달되는지도 확인합니다.

## 2단계 — 모델과 백엔드

업무가 허용하는 동시 실행 수에 따라 단일·그룹·전략 선출을 고릅니다. 애플리케이션의 실행 방식에 맞춰 API를 정한 뒤, 팀이 이미 운영할 줄 아는 백엔드를 선택합니다. 모듈 문서에서는 생성자와 설정, 장애 특성을 확인하고, 실행 가능한 예제에서는 전체 구성을 살펴봅니다.

## 3단계 — 운영 훈련

작업 시간을 측정해 리스와 대기 예산을 정하고, skipped 급증·작업 실패·리스 연장 실패를 볼 수 있는 메트릭과 알림을 붙입니다. 백엔드 단절, 소유권 만료, 중복 전달, 정상 종료, 재시작을 미리 연습합니다. 무엇을 보고 어떤 조치를 할지 팀이 설명할 수 있어야 학습이 끝난 것입니다.

## 릴리스 소스

- [`leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt`](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)
- [`examples/batch-scheduler/README.ko.md`](../../../../examples/batch-scheduler/README.ko.md)
- [`examples/prometheus-dashboard/README.ko.md`](../../../../examples/prometheus-dashboard/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [선출 모델 선택](election-model-selection.md)
- [리더 선출 테스트](testing.md)
- [관측과 운영](observability-and-operations.md)
