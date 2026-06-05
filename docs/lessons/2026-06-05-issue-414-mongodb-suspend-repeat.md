# Issue 414 MongoDB Suspend Benchmark Repeat

## Context

The suspend MongoDB `runIfLeader` benchmark row was marked noisy in the
cross-backend baseline. Issue #414 asked for repeated same-machine evidence
before deciding whether to open a tuning task.

## Decision or Finding

Three focused repeats against Lettuce, Redisson, and Hazelcast confirmed that
MongoDB stays slower, but the short-window score range and JMH error are too
wide for a narrow production optimization target.

## Outcome

No production code changed and no follow-up tuning issue was opened. The raw
throughput and average-time JSON files plus the decision record are preserved in
`docs/benchmarks/2026-06-05-issue-414-mongodb-suspend-repeat.md`.

## Verification

- `./gradlew :benchmark:tasks --all --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --rerun-tasks`
- JMH throughput repeat for `lettuce,redisson,mongo,hazelcast`, 3 runs.
- JMH average-time repeat for `lettuce,redisson,mongo,hazelcast`, 3 runs.

## Future Guidance

For focused benchmark repeats, verify generated kotlinx-benchmark task names
first. If runtime filters are needed, build the official benchmark JAR with
Gradle and document why direct JMH execution is being used instead of the
generated JavaExec task.
