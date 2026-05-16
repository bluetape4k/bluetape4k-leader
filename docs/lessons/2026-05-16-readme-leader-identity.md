# Lesson: Add LeaderId Identity section to leader-core README

**Date**: 2026-05-16
**Issue**: #211
**PR**: TBD

## Root Cause

PR #209 added ~7,600 lines of leader identity API (`LeaderIdProvider`, `LeaderSlot`, Redis audit
propagation) but `leader-core/README.md` and `leader-core/README.ko.md` were not updated.
`grep -i 'identity|LeaderIdProvider|LeaderSlot'` returned zero hits in both files.

## Changes Made

Added a `## Leader Identity` / `## 리더 Identity` section to both README files covering:

1. **`LeaderIdProvider` interface** — contract (never throw, never block, thread-safe, non-blank)
2. **Built-in providers** — table with `RandomLeaderIdProvider`, `HostnamePidLeaderIdProvider`,
   `CompositeLeaderIdProvider`; includes PII warning for `HostnamePid`
3. **`LeaderIdSource` enum** — provenance tag table (`LITERAL`, `SPEL`, `PROPERTY`, `AUTO`)
4. **`LeaderSlot`** — how leaderId couples to `runIfLeader`, event propagation, result access
5. **Custom provider example** — three variants (random, hostname+PID, tenant-prefixed)
6. **Redis backend audit table** — Lettuce `lg:{lockName}:meta` Hash vs Redisson
   `lg:{lockName}:audit` RMap; TTL-based auto-reclaim without external reaper

## Future Guidance

When a PR adds a new public API surface (>500 lines inserted), the PR checklist must include:

> [ ] README.md + README.ko.md updated for all new public types

This should be added to the PR template and the CLAUDE.md code-change checklist.
