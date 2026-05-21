# Issue 337 current coroutine context liveness

## Context

`WebhookPoller.runLoop()` checked its loop liveness through
`kotlin.coroutines.coroutineContext[Job]?.isActive != false`. That property is
valid in suspend code, but it is easier to miss imports and diverges from the
repository's established suspend-loop style.

## Decision

Use `currentCoroutineContext()[Job]?.isActive != false` for suspend-loop
liveness checks. Keep `CancellationException` rethrow before the generic
exception handler so cancellation still terminates the poller promptly.

## Outcome

The webhook-poller example now imports `kotlinx.coroutines.currentCoroutineContext`
and uses it in `runLoop()`. The stale `null` expression in the generic catch
block was also removed because it had no effect.

## Verification

Run the webhook-poller targeted test and a repository search proving the old
`coroutineContext[Job]?.isActive != false` liveness pattern is absent.

## Future Guidance

For suspend loops in bluetape4k code, prefer `currentCoroutineContext()` when
reading context elements or calling `ensureActive()`. Keep raw
`kotlin.coroutines.coroutineContext` usage for narrow cases where the source
module already needs the low-level property.
