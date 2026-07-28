# Issue #421 etcd Reconciler Example Plan

## 한국어 해설

이 문서는 `Issue #421 etcd Reconciler Example Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Steps

1. Create `examples/etcd-reconciler` as an application module depending on
   `bluetape4k-leader-etcd`.
2. Add `ControlPlaneReconciler` and `EtcdReconcilerDemo`.
3. Add a Testcontainers-backed test for active leader, skipped contender, and
   reacquire after release.
4. Add English/Korean README files for the example.
5. Register the module in `settings.gradle.kts`, root README locale set, CI, and
   Examples workflow.
6. Validate with the targeted example test and workflow lint.

## Verification

- `./gradlew :examples:etcd-reconciler:test`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
