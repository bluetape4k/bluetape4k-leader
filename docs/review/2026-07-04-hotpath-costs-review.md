# Hot-Path Cost Review

Issue: #580
Milestone: 0.5.0

## Scope

- `bluetape4k-leader-consul`
- `examples/webhook-poller`

## 7-Tier Findings

### Tier 1 - Correctness

- Consul group election still returns `null` under saturated contention and releases candidate sessions.
- Webhook poller startup now fails if mandatory claim indexes cannot be created.

### Tier 2 - Security

- No credential or authorization surface changed.
- Mandatory indexes avoid a hidden production fallback to unbounded collection scans.

### Tier 3 - Concurrency

- Consul retry loops retain session renewal and cancellation/interrupt handling.
- Jittered retry delay reduces synchronized contention against the same slot set.

### Tier 4 - API

- No public API changes.
- The Consul slot probe limit is an internal policy constant.

### Tier 5 - Performance

- Saturated Consul group acquisition is capped to three remote slot-acquire calls per retry.
- Webhook poller claim indexes include `createdAt` ordering fields used by the claim sort path.

### Tier 6 - Tests

- Blocking and suspend Consul delegation tests assert bounded acquire call counts with `maxLeaders = 64`.
- Webhook poller integration tests verify index names and key ordering against real MongoDB metadata.

### Tier 7 - Documentation

- Added `docs/benchmarks/2026-07-04-issue-580-hotpath-cost-model.md` with the cost model and validation evidence.
