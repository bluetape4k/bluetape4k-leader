# WIP - bluetape4k-leader

Snapshot: 2026-05-23 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 1 issue.

## Refresh Notes

Verified with `gh` on 2026-05-23 KST.

- Milestone `0.2.0` is release-ready: 17 closed issues, 0 open issues.
- Milestone `0.1.1` is also complete and is folded into the `0.2.0` release
  because `develop` already contains both milestone scopes.
- The only open assigned issue is scheduled for `0.2.1`.

## Current Direction

Release `0.2.0` from `develop`, then open the next snapshot train and continue
with the small cross-repo promotion item.

## Priority Queue

| Priority | Issue | Milestone | Difficulty | Notes |
|---|---|---|---:|---|
| P1 | [#270](https://github.com/bluetape4k/bluetape4k-leader/issues/270) feat: promote StringTruncateSupport to bluetape4k-support after v1 stabilizes | 0.2.1 | S | Cross-repo support promotion after 0.2.0 release. |

## Completed For 0.2.0

| Area | Issues |
|---|---|
| Correctness / cancellation | #304, #305, #306, #308, #309 |
| Coroutine delegate cleanup | #271 |
| State observation | #222 |
| New backends | #227, #228, #335, #345 |
| Examples / adoption | #229, #231, #248 |
| Documentation / cleanup | #269, #287, #288, #348, #349 |

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Release | 1 | Publish `0.2.0`, then open the next snapshot line. |
| Cross-repo support promotion | 1 | Start #270 after release validation and downstream version sync. |
