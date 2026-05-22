# 2026-05-22 Issue #345 Leader Consul

## L1. Keep Consul dependency decisions behind a bluetape4k-owned boundary

The first Consul slice should publish only bluetape4k-owned DTOs and options.
The implementation boundary is `internal`, so the runtime slice can use Java 21
HttpClient without freezing a stale third-party Consul client into the public
API.

## L2. Treat endpoint strings as credential-bearing until proven otherwise

`aclToken` masking is not enough if `baseUrl` accepts `user:password@host`.
Reject URI user-info at construction time and force Consul ACL material through
the dedicated token field.

## L3. New publishable modules need both scheduled and PR workflow wiring

For `leader-consul`, add the module to `settings.gradle.kts`, the BOM, root
README pairs, CI path filters, per-module test jobs, and both summary
aggregator `needs:` lists. Missing any one of these silently removes coverage.

## L4. Test resource files are part of backend module parity

Even early contract-only backend modules should include the JUnit and logback
test resources used by sibling modules. Claude review caught the missing files
before PR creation.

## L5. Platform version must be verified with the actual Gradle graph

The parent release branch now uses `bluetape4k-bom` 1.9.0. Verify the resolved
artifact, not only `libs.versions.toml`; the prior check confirmed
`bluetape4k-testcontainers:1.9.0` and its ConsulServer fixture are available.

## L6. Renew waiting Consul sessions when waitTime can exceed TTL

Consul `acquire` returns `false` while another session owns the key, but the
waiting candidate's own session still expires on its TTL. If `waitTime` is
longer than the session TTL and the candidate does not renew while polling,
Consul eventually returns `invalid session` instead of a clean takeover. Renewal
must cover both active holders and waiting candidates.

## L7. Cleanup must survive interrupted minLeaseTime waits

`minLeaseTime` enforcement sleeps before releasing the backend lock. If that
sleep is interrupted, restore the interrupt flag but continue backend
`release`/`destroy`; otherwise the lock survives until Consul TTL expiry and can
mask the action result with an interruption failure.

## L8. Passive ownership checks must not extend Consul sessions

`ExtendDelegate.isHeld()` is a read/check operation. For Consul, implement it by
reading the KV entry and comparing the session id, not by calling
`session/renew`; otherwise assertions or health probes can lengthen the lease
outside explicit `extend` or watchdog control.
