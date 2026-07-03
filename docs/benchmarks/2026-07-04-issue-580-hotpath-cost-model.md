# Issue 580 Hot-Path Cost Model

Issue: #580
Milestone: 0.5.0

## Consul Group Acquisition

Before this change, a saturated Consul group acquisition attempted every slot on every retry:

```text
remote acquire calls ~= maxLeaders * retryCount
```

The new policy caps each retry to `CONSUL_GROUP_SLOT_PROBE_LIMIT` randomized slot probes and uses jittered backoff before the next retry:

```text
remote acquire calls <= 3 * retryCount
```

The delegation tests run with `maxLeaders = 64` and a saturated fake client, then assert the acquire call count stays below the fixed probe budget instead of scanning all slots.

## Webhook Poller Claim Path

The poller now creates mandatory MongoDB indexes before entering the polling loop:

- `idx_webhook_claim_pending_created_at`: `(status, createdAt, attempts)`
- `idx_webhook_claim_expired_created_at`: `(status, createdAt, attempts, claimExpiresAt)`
- `idx_webhook_event_id`: unique `(eventId)`

Index creation failure now fails startup instead of silently leaving the poller on a collection-scan fallback.

## Validation

- `./gradlew :bluetape4k-leader-consul:test --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectorDelegationTest' --tests 'io.bluetape4k.leader.consul.ConsulSuspendLeaderElectorDelegationTest' :examples:webhook-poller:test --tests 'io.bluetape4k.leader.examples.webhook.WebhookPollerTest' --no-build-cache --rerun-tasks`
- Result: Consul delegation tests `27 passing`; webhook poller tests `11 passing`.
