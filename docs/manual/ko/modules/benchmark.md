---
manualId: "benchmark"
id: "benchmark"
title: "리더 선출 벤치마크"
locale: "ko"
kind: "benchmark"
gradlePath: ":benchmark"
sourceDir: "benchmark"
releaseRef: "0.5.0"
artifact: null
---

# 리더 선출 벤치마크

> 성능 벤치마크

## 제공하는 기능 {#problem}

배포하지 않는 이 모듈은 하나의 `kotlinx-benchmark`/JMH harness로 선출 백엔드를 비교합니다. 0.5.0 매뉴얼은 2026-05-29 근거를 기준으로 삼습니다. Throughput은 높을수록, average time은 낮을수록 좋지만 같은 workload와 runtime target 안의 행만 비교해야 합니다.

## 사용하기 좋은 경우 {#when-to-use}

같은 장비에서 candidate와 baseline을 비교하거나 큰 회귀를 찾을 때 사용하세요. 짧은 microbenchmark 표를 운영 용량 보장으로 받아들이거나 서로 다른 인프라의 절대 순위를 매기는 데 쓰면 안 됩니다.

## 의존성 좌표 {#coordinates}

이 모듈은 배포하지 않습니다. `0.5.0` 태그를 checkout하고 저장소에서 `:benchmark`를 실행합니다.

## 핵심 개념 {#concepts}

Harness는 blocking과 suspend API, local과 분산 백엔드, throughput과 average-time 모드를 나눕니다. Kubernetes는 Fabric8/Vert.x runtime이 기본 etcd target과 달라 별도 target에서 실행합니다. Fork, thread, warmup, 측정 창, container, JDK, OS, hardware까지 모두 결과의 일부입니다.

## 빠르게 시작하기 {#quick-start}

```bash
./gradlew :benchmark:benchmarkBenchmark \
  :benchmark:benchmarkAverageTimeBenchmark \
  --no-configuration-cache --rerun-tasks

./gradlew :benchmark:kubernetesBenchmarkBenchmark \
  :benchmark:kubernetesBenchmarkAverageTimeBenchmark \
  --no-configuration-cache --rerun-tasks
```

## 작업별 API {#api-by-task}

`BackendLeaderElectorBenchmark`는 blocking 경로, `SuspendBackendLeaderElectorBenchmark`는 코루틴 경로를 측정합니다. 좁은 실험에는 filter를 사용하고 콘솔 요약만 복사하지 말고 원본 JSON을 보존하세요.

## 권장 패턴 {#patterns}

변수 하나만 바꾸고 harness와 환경은 고정합니다. 같은 세션에서 baseline과 candidate를 실행해 신뢰 구간을 비교하세요. 지표 방향과 오차 범위를 함께 기록하고 노이즈가 큰 행은 해석으로 덮지 말고 다시 측정합니다.

## 연동 {#integrations}

Harness가 백엔드 인프라를 시작합니다. 2026-05-29 SQL 행은 blocking에 Exposed JDBC, suspend에 Exposed R2DBC를 사용합니다. Kubernetes는 별도 runtime target을 유지합니다.

## 설정 {#configuration}

재현 기록에는 JDK, OS, CPU, container 버전, fork/thread 수, warmup, 측정 시간·횟수, Gradle 명령, filter가 들어가야 합니다. 이 정보가 없으면 다른 장비의 숫자와 비교할 수 없습니다.

## 실패 유형과 해결 방법 {#failures}

container 실패, 다른 작업으로 포화된 노트북, thermal throttling, 섞인 runtime classpath가 있으면 실행 결과가 무효입니다. 오차 범위가 매우 넓은 값으로 튜닝 결론을 내릴 수 없고 한 번 빠른 sample도 수정 근거가 아닙니다.

## 운영 {#operations}

throughput과 average-time 원본 JSON을 짧은 해석과 함께 보존합니다. 두 지표 방향이 다르면 주장부터 만들지 말고 setup 비용과 outlier를 확인하세요. 결과에는 정확한 commit과 환경도 함께 남깁니다.

## 테스트 {#testing}

긴 실행 전에 benchmark source set을 컴파일하고 좁은 filter로 smoke run을 합니다. 그다음 throughput과 average-time 전체 모드를 순서대로 실행하세요. Harness가 안정판 0.5.0 API만 쓰는지도 확인합니다.

## 학습 경로와 예제 {#workshops}

cross-backend baseline을 먼저 읽고 benchmark class에서 실제 측정 연산을 확인하세요. 운영상 trade-off는 각 백엔드 매뉴얼과 함께 해석합니다. 벤치마크가 애플리케이션 대신 백엔드를 골라 주지는 않습니다.

## 제약 사항 {#limitations}

2026-05-29 근거는 한 장비, 짧은 JMH 측정 창, container 기반 의존성을 설명합니다. 네트워크 분할, 운영 데이터 크기, 다중 노드 경쟁, failover, 보호할 업무 비용은 재현하지 않습니다.

## 근거 자료 {#sources}

[벤치마크 안내](../../../../benchmark/README.ko.md) · [2026-05-29 throughput JSON](../../../../docs/benchmarks/2026-05-29-issue-405-rdb-backend-throughput.json) · [2026-05-29 average-time JSON](../../../../docs/benchmarks/2026-05-29-issue-405-rdb-backend-average-time.json) · [Harness](../../../../benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/BackendLeaderElectorBenchmark.kt)
