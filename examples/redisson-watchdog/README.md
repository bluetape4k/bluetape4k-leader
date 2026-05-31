# Redisson Watchdog Example

[한국어](README.ko.md)

This example demonstrates a Redisson-backed long-running leader job protected
by bluetape4k lease auto-extension. One node holds the leader lock beyond the
initial lease time, a contending node skips while the job is still running, and
the contender can acquire the lock after the leader releases it.

## Scenario

Use this pattern for Redis-backed jobs that can run longer than their initial
lease budget:

- configure `LeaderElectionOptions(autoExtend = true)`;
- keep `waitTime` short so non-leaders skip quickly;
- size `leaseTime` for normal renewal cadence, not for the maximum job runtime;
- keep the leader action bounded by your service shutdown or job timeout.

The current Redisson elector acquires locks with an explicit lease time and
uses bluetape4k `LeaderLeaseAutoExtender` for renewals. It does not rely on
Redisson's implicit lock-watchdog acquisition mode, so `autoExtend=true` and
`minLeaseTime > 0` remain valid when the application explicitly needs both.

## Run

```bash
./gradlew :examples:redisson-watchdog:run
```

Docker is required because the demo starts Redis with Testcontainers.

## Test

```bash
./gradlew :examples:redisson-watchdog:test
```

The key test starts one leader job with `leaseTime = 250.milliseconds`, waits
longer than that initial lease, verifies a contender still skips, then verifies
the contender can acquire leadership after release.

## Key APIs

- `RedissonLeaderElector`
- `LeaderElectionOptions(autoExtend = true)`
- `LeaderLeaseAutoExtender`

Prefer the suspend Redisson elector for coroutine-native services. Use this
blocking example for synchronous workers, scheduler callbacks, and migration
code that already runs on blocking threads.
