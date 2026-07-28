# 이슈 #326 leader-core 벤치마크

## 맥락

`bluetape4k-leader`는 자체 개선 작업을 수행하기 전에 벤치마크 기준이 필요했습니다. 저장소에는 JMH 설정이 없었지만 기록 감사 사양에는 이미 메모리 내 레코더 오버헤드가 1ms 핫 경로 목표 미만으로 유지된다는 증거가 필요했습니다.

## 결정

`me.champeau.jmh` 0.7.3을 사용하여 작은 `buildSrc` JMH 규칙을 추가하고 `leader-core`에만 적용했습니다. 첫 번째 벤치마크에서는 로컬 선택기 실행 모델과 기록 레코더 래퍼 오버헤드를 다룹니다. 크로스 백엔드 Testcontainers 벤치마크는 별도의 후속 작업으로 남아 있습니다.

## 결과

`compileJmhKotlin`, `jmhRunBytecodeGenerator` 및 `jmh`는 모두 Kotlin 2.3을 사용하여 Gradle 9.5.1을 통과합니다. 생성된 보고서는 `leader-core/build/reports/jmh/`에 있고 내구성 기준은 `docs/benchmarks/2026-05-21-leader-core-baseline.md`에 있습니다.

## 검증

- `./gradlew :bluetape4k-leader-core:compileJmhKotlin --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:jmhRunBytecodeGenerator --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:jmh --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:test --no-configuration-cache`
- `codex review --uncommitted`

## 향후 지침

- Issue #327은 직접 `me.champeau.jmh` Gradle 플러그인 설정을 대체했습니다. 새로운 리더 벤치마크는 JVM 백엔드로 `kotlinx-benchmark` 및 JMH와 함께 중앙 `benchmark/` 모듈을 사용해야 합니다.
- 코루틴 선택기 벤치마크에는 `waitTime = 0.seconds`를 사용하지 마세요. 선택한 경로 대신 `withTimeoutOrNull(0)` 건너뛰기 경로를 측정할 수 있습니다.
- 실행 모델을 비교하는 모든 벤치마크 차트 옆에 `runBlocking` 및 가상 스레드 예약 주의 사항을 문서화하세요.
- README 차트를 하네스 PR에서 제외하세요. 백엔드 데이터를 비교할 수 있는 경우에만 추가하세요.
