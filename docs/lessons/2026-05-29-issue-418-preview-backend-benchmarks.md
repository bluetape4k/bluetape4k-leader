# Issue 418 미리보기 백엔드 벤치마크

## 맥락

벤치마크 모듈에는 안정적인 백엔드를 위한 크로스 백엔드 행이 있었지만 Consul, etcd, DynamoDB 또는 Kubernetes에 대한 미리 보기 행은 없었습니다.

## 결정

기존의 비공개 `benchmark` 모듈에서 미리보기 행을 유지하고 백엔드 통합 테스트 Testcontainers 실행기를 재사용합니다. 원시 JMH 설정 또는 프로덕션 벤치마크 도우미를 생성하지 마십시오. Fabric8에는 Vert.x 4/Netty 4.1이 필요하고 기본 미리 보기 백엔드 클래스 경로에는 etcd용 Vert.x 5가 필요하므로 별도의 `kubernetesBenchmark` 소스 세트에서 Kubernetes를 실행합니다.

## 결과

이제 차단 및 일시 중단 벤치마크 하네스가 Consul, etcd, DynamoDB Local 및 K3s 지원 Kubernetes Lease 행으로 컴파일됩니다. Kubernetes는 자체 벤치마크 대상에서 격리됩니다. 각 설정은 여전히 ​​타이밍 전에 `runIfLeader` 연기 검사를 수행합니다.

## 검증

- `./gradlew :benchmark:compileBenchmarkKotlin :benchmark:compileKubernetesBenchmarkKotlin --no-daemon`는 2026년 5월 29일에 통과되었습니다.
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon`는 2026년 5월 29일에 통과되었습니다.
- `./gradlew :benchmark:kubernetesBenchmarkBenchmark :benchmark:kubernetesBenchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon`는 2026년 5월 29일에 통과되었습니다.
- 원시 JSON은 `docs/benchmarks/2026-05-29-issue-418-*.json`에 보존되었습니다.
- README 차트 SVG/PNG 자산은 `docs/images/readme-charts/leader-benchmark-*.{svg,png}`에서 생성되었으며 `xmllint --noout`로 검증되었습니다.

## 퓨쳐 가드

벤치마크 README 결과 테이블의 경우 새로운 동일 분기 JSON 결과가 존재한 후에만 측정된 행을 추가하거나 변경하십시오. 동일한 변경 사항에 일치하는 README 차트를 게시하여 테이블과 차트 증거가 표류하지 않도록 하세요. Kubernetes 벤치마크 행은 유용하지만 Docker/K3s 지원 로컬 실행이 필요하고 다른 미리 보기 행보다 무거운 것으로 처리되어야 하며 Fabric8 및 etcd가 하나의 Vert.x 라인을 공유할 수 없는 한 격리된 상태로 유지되어야 합니다.
