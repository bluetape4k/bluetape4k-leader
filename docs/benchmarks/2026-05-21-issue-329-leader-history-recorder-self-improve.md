# 이슈 #329 리더 이력 기록기 자기 개선 - 2026-05-21

문제 #329는 벤치마크 기반 자체 개선 루프를 사용하여 `LeaderHistoryRecorderSupport` 삭제 핫 경로를 조정했습니다. 승인된 후보는
벤치마크 하네스와 봉인된 벤치마크 아티팩트를 변경하지 않고 유지하며 이미 안전한 기록 기록에 대해 피할 수 있는 삭제제 할당만 제거합니다.

이 보고서는 동일 기계 비교 전/후에만 사용하십시오. 이는 릴리스 등급 성능 주장이 아닙니다.

## Goal

- 기본 측정항목: `HistoryRecorderBenchmark.blockingInMemoryAcquireComplete` 처리량을 1% 이상
- 향상합니다.
- 가드 측정항목: 'HistoryRecorderBenchmark.suspensInMemoryAcquireComplete' 및
- 'HistoryRecorderBenchmark.blockingNoopAcquireComplete'를 기준선의 5% 이내로 유지합니다.
- 중지 조건: 메트릭 게이트를 충족하는 첫 번째 후보를 수락하거나 3번의 반복, 2번의 벤치마크 실행 실패 또는 정체 후에 중지합니다.

## 봉인된 파일

자체 개선 실행에서는 이러한 경로를 봉인된 것으로 처리하고 변경되지 않았는지 확인했습니다.

- `benchmark/build.gradle.kts`
- `벤치마크/src/벤치마크/`
- `benchmark/src/benchmark/resources/logback-test.xml`
- `docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`
- `docs/benchmarks/2026-05-21-leader-core-baseline.md`

## 명령

```bash
./gradlew :benchmark:benchmarkBenchmark --no-configuration-cache --rerun-tasks
```

`kotlinx-benchmark`는 JMH를 호출하기 전에 생성된 실행기 설정 파일을 작성하므로 임시 `--args
'HistoryRecorderBenchmark'` 필터링은 허용된 비교에 사용되지 않았습니다.

기계가 읽을 수 있는 소스 아티팩트:

- 기준선:
- `benchmark/build/reports/benchmarks/main/2026-05-21T22.47.37.838587/benchmark.json`
- 후보:
- `benchmark/build/reports/benchmarks/main/2026-05-21T22.53.57.264618/benchmark.json`

## Result

높을수록 좋습니다. 승인된 후보는 반복 1에서 기본 및 보호 측정항목을 통과했습니다.

| Benchmark | Baseline (ops/s) | After (ops/s) | Delta |
|---|---:|---:|---:|
| `HistoryRecorder.blockingInMemoryAcquireComplete` | 5,601,881.043 | 20,018,125.709 | +257.35% |
| `HistoryRecorder.blockingNoopAcquireComplete` | 7,642,848.188 | 62,740,146.724 | +720.90% |
| `HistoryRecorder.suspendInMemoryAcquireComplete` | 4,843,511.108 | 11,441,889.888 | +136.23% |
| `HistoryRecorder.suspendNoopAcquireComplete` | 5,257,310.052 | 23,153,305.712 | +340.40% |

## 결정

수락됨: 정규식 기반 삭제를 안전한 빠른 경로로 대체합니다.

- 컨트롤 없음, C1 컨트롤, 줄 구분 기호 또는 단락 구분 문자가 포함된 경우 원래 문자열을 반환합니다.
- 키 개수, 키 길이, 값 길이 및 안전하지 않은 문자 검사가 이미 레코더 제한을 충족하는 경우 원본 메타데이터 맵을 반환합니다.
- 잘라내기나 교체가 필요한 경우에만 삭제된 복사본을 할당하세요.

거부된 대안:

- Base58 또는 무작위 토큰 생성을 변경합니다. 이는 기록 레코더 핫 경로를 격리하는 대신 로컬이 아닌 백엔드 의미 체계 및 토큰 엔트로피에 영향을 미칩니다.
- 벤치마크 하네스를 조정합니다. 자체 개선 게이트에는 비교 가능한 전후 증거가 필요하므로 벤치마크 파일은 봉인된 상태로 유지되었습니다.

## 확인

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.history.LeaderHistoryRecorderSupportTest' --no-configuration-cache`
- `./gradlew :benchmark:benchmarkBenchmark --no-configuration-cache --rerun-tasks`
- `/Users/debop/.codex/skills/bluetape4k-self-improve/scripts/validate-sealed.sh --repo .`

## 남은 위험

- 이는 짧은 단일 시스템 처리량 실행이었습니다. 릴리스 노트 성능을 주장하기 전에 더 긴 프로필을 다시 실행하십시오.
- 처리량이 기본 관문이므로 이 자체 개선 반복에서는 평균 시간 모드가 다시 실행되지 않았습니다.
- 동일한 벤치마크 명령의 원격 백엔드 행은 잡음이 많으며 이 로컬 기록 레코더 최적화와 관련이 없습니다.
