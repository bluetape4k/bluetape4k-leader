# Strategic Election Example

[한국어](README.ko.md)

This example demonstrates a backend-neutral strategic leader election scenario.
Three service nodes compete to run a maintenance task. The winner is selected by
weighted scoring across service readiness, historical success rate, and idle
time.

## Scenario

The demo models a maintenance window where only the best node should run the
work:

- service readiness combines health and available capacity;
- historical success rate favors reliable nodes;
- idle time gives recently unused nodes a small fairness boost;
- non-winner nodes skip the action without throwing.

## Run

```bash
./gradlew :examples:strategic-election:run
```

## Test

```bash
./gradlew :examples:strategic-election:test
```

## Key APIs

- `LocalStrategicLeaderElector`
- `CandidateScorer`
- `WeightedScorer`
- `ScoredElectionStrategy`

Use the same scoring pattern with a distributed strategic elector when the
candidate registry must be shared across processes.
