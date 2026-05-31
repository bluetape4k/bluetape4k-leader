# Virtual Thread Runner Example

[한국어](README.ko.md)

This example demonstrates a high-concurrency leader-only runner using Java
virtual threads. A group of service nodes races for one local leader lock; one
node executes bounded maintenance work on a virtual thread, and the rest skip
without throwing.

## Scenario

Use this pattern when a service has many blocking tasks but still needs a
single leader-only action per maintenance window:

- each node submits leader work through `VirtualThreadLeaderElector`;
- the leader action runs on a Java virtual thread;
- losers return skipped reports instead of blocking a carrier thread;
- the demo bounds the leader action with a timeout so shutdown is predictable.

## Run

```bash
./gradlew :examples:virtual-thread-runner:run
```

Docker is not required. The example uses the local in-JVM leader backend.

## Test

```bash
./gradlew :examples:virtual-thread-runner:test
```

## Key APIs

- `VirtualThreadLeaderElector`
- `LocalVirtualThreadLeaderElector`
- `LeaderElectionOptions`

Use virtual-thread electors for blocking actions that benefit from Java 21
virtual threads. Prefer coroutine electors for suspend-first application code,
and blocking electors for simple synchronous services.
