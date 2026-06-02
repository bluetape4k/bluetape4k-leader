# WIP - bluetape4k-leader

Snapshot: 2026-06-02 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 9 issues.
The release-train dependency alignment shipped in the `0.3.1` patch release.

## Current Direction

The `0.3.1` patch release carried only the release-train dependency alignment
for the Exposed-backed modules. Remaining assigned issues are parked in Backlog
and should be scheduled into a later minor release.

The current `0.4.0` development line carries the `bluetape4k-bom`
`1.11.0-SNAPSHOT` and `bluetape4k-exposed` `1.11.0-SNAPSHOT` alignment. The
publishable Exposed leader modules should keep
`bluetape4k-exposed-bom` on `implementation(platform(...))` so the BOM does not
leak as an API platform dependency.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#413](https://github.com/bluetape4k/bluetape4k-leader/issues/413) preview backend stable-promotion checklist | Backlog | Epic for Consul, DynamoDB, etcd, and Kubernetes promotion criteria. |
| P2 | [#416](https://github.com/bluetape4k/bluetape4k-leader/issues/416) reconcile Spring AOP return-type notes | Backlog | Documentation cleanup; do not block 0.3.x patch releases. |
| P2 | [#423](https://github.com/bluetape4k/bluetape4k-leader/issues/423) DynamoDB scheduled export example | Backlog | Example lane for a future minor release. |
| P2 | [#424](https://github.com/bluetape4k/bluetape4k-leader/issues/424) ZooKeeper legacy scheduler example | Backlog | Example lane for a future minor release. |
| P2 | [#427](https://github.com/bluetape4k/bluetape4k-leader/issues/427) watchdog overhead benchmark | Backlog | Performance lane under epic #428. |
| P3 | [#414](https://github.com/bluetape4k/bluetape4k-leader/issues/414) noisy MongoDB suspend benchmark tuning | Backlog | Performance follow-up; verify fresh benchmark variance first. |
| P3 | [#428](https://github.com/bluetape4k/bluetape4k-leader/issues/428) performance benchmark epic | Backlog | Umbrella issue. |
| P3 | [#429](https://github.com/bluetape4k/bluetape4k-leader/issues/429) examples epic | Backlog | Umbrella issue. |

## Open PRs

None after the `0.3.1` patch release.

## Recently Completed

- [#417](https://github.com/bluetape4k/bluetape4k-leader/issues/417) / [PR #454](https://github.com/bluetape4k/bluetape4k-leader/pull/454) surfaced preview backend release gates in Nightly summaries.
- [#426](https://github.com/bluetape4k/bluetape4k-leader/issues/426) / [PR #455](https://github.com/bluetape4k/bluetape4k-leader/pull/455) added the virtual-thread runner example.
- [#425](https://github.com/bluetape4k/bluetape4k-leader/issues/425) / [PR #456](https://github.com/bluetape4k/bluetape4k-leader/pull/456) added the Redisson watchdog example.
- [#415](https://github.com/bluetape4k/bluetape4k-leader/issues/415) normalized the misspelled performance label.

## Refresh Notes

- Verified with `gh` on 2026-06-02 KST.
- Keep `bluetape4k-*` issue and resolving PR milestones aligned. Prefer minor
  release grouping for non-bugfix work.
