---
title: "벤치마크 결과 해석"
description: "0.5.0의 JMH 자료를 통제된 비교에 사용하되 인프라 구매 순위표로 읽지 않습니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 벤치마크 결과 해석

0.5.0의 JMH 자료를 통제된 비교에 사용하되 인프라 구매 순위표로 읽지 않습니다.

## 무엇을 측정했나

benchmark 모듈은 kotlinx-benchmark의 JMH runner를 사용합니다. 기록된 교차 백엔드 실행은 한 머신에서 fork 1개, thread 1개, warmup 2회, 1초 measurement 3회로 수행했습니다. PostgreSQL, MySQL, Kubernetes, Redis 연장, history recorder, 일부 반복 측정의 결과와 제약은 별도 raw artifact에 기록돼 있습니다.

## 방향과 불확실성

비교 가능한 row 안에서만 throughput은 높을수록, average time은 낮을수록 좋습니다. container 기반 결과 중에는 오차 범위가 큰 항목이 많으므로 구간이 겹치면 우열을 단정하지 않습니다. Local과 H2는 프로세스 내부 또는 로컬 SQL overhead를 측정하므로 분산 백엔드 chart에서 분리합니다.

## 개발에 활용하는 법

같은 머신에서 코드 변경 전후에 정확히 같은 명령을 반복합니다. 환경, raw JSON, warmup, fork, 오차 데이터를 함께 보존합니다. 백엔드를 선택할 때는 실제 배포 topology와 운영할 작업으로 다시 측정해야 합니다. 릴리스 결과는 사용자의 네트워크, 내구성 설정, pool 경쟁을 대신 모델링하지 못합니다.

## 릴리스 소스

- [`benchmark/README.ko.md`](../../../../benchmark/README.ko.md)
- [`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`](../../../benchmarks/2026-05-21-leader-cross-backend-baseline.md)
- [`docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json`](../../../benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [백엔드 선택](../guides/backend-selection.md)
- [리더 선출 테스트](../guides/testing.md)
