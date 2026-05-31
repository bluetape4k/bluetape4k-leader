# Lesson: issue #425 Redisson watchdog example

## Context

Milestone 0.3.0 needed a runnable Redisson example for long-running leader-only
jobs protected by lease auto-extension.

## Decision

Add `examples/redisson-watchdog` as a Testcontainers Redis example. The example
documents the current implementation precisely: Redisson locks are acquired with
an explicit lease and bluetape4k `LeaderLeaseAutoExtender` renews that lease.

## Outcome

The example starts a leader job that runs beyond its initial lease, verifies a
contender skips while the lease is renewed, and verifies reacquisition after
release.

## Verification

Run `:examples:redisson-watchdog:test`, `:examples:redisson-watchdog:run`,
`./gradlew projects`, `actionlint`, and `git diff --check` before opening the PR.

## Future Rule

Do not describe the current Redisson elector as using Redisson's implicit native
lock watchdog. That path is intentionally disabled by passing an explicit
`leaseTime`; examples and docs should call it bluetape4k lease auto-extension.
