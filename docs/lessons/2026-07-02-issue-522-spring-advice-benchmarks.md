# Issue #522 Spring 조언 벤치마크 레슨

## 맥락

Issue #522에는 백엔드 잠금 I/O에서 `@LeaderElection` Spring AOP 조언 오버헤드를 분리하는 벤치마크가 필요했습니다.

## 결정

로컬 차단 및 일시 중단 선택기를 사용하고 작은 벤치마크 전용 `ProceedingJoinPoint` 고정 장치를 사용하여 `LeaderElectionAspect`를 직접 호출합니다. 이를 통해 벤치마크는 주석 메타데이터, SpEL, AspectJ 디스패치, 코루틴 연속 연결, Bean 선택 및 레코더 반복에 중점을 둡니다.

## 결과

이제 벤치마크에서는 직접 경로와 조언 경로, 정적 잠금 이름과 SpEL 잠금 이름, 동기화 및 일시 중지 방법, `instrumentation=none|noop` 레코더 구성을 다룹니다. README 차트와 원시 JSON은 짧은 JMH 스냅샷을 문서화합니다.

## 검증

- `:benchmark:compileBenchmarkKotlin`
- `:benchmark:benchmarkBenchmarkJar`
- `SpringLeaderAdviceBenchmark`에 대한 JMH 처리량 및 평균 연기 실행 시간
- 새로운 SVG 차트용 `xmllint --noout`

## 미래의 메모

실제 Micrometer 레지스트리 오버헤드를 별도의 벤치마크에 유지하여 이 픽스처가 AOP 디스패치 및 표현 평가 비용을 계속 격리하도록 합니다.
