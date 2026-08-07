---
title: "MongoDB and DynamoDB"
description: "Coordinate with atomic document updates or conditional key-value writes, then account for TTL and clock behavior."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# MongoDB and DynamoDB

Coordinate with atomic document updates or conditional key-value writes, then account for TTL and clock behavior.

## MongoDB

The MongoDB backend uses atomic `findOneAndUpdate` ownership changes and a TTL index for cleanup. It offers blocking and suspend variants and supports the shared single-leader extension path. TTL cleanup is not an exact deadline, so correctness relies on the acquisition predicate, not deletion timing.

## DynamoDB

The preview DynamoDB backend uses conditional writes and logical TTL. Design capacity for synchronized contender bursts and monitor throttling. DynamoDB TTL deletion is asynchronous; the stored expiry condition, not physical row removal, determines acquisition.

## When to use

Use these backends when the application already treats the store as an operational dependency. Test with the real service or a faithful emulator, including clock skew, conditional conflicts, throttling, and retry ambiguity. A client timeout may leave the write outcome unknown.

## Release sources

- [`leader-mongodb/README.md`](../../../../leader-mongodb/README.md)
- [`leader-dynamodb/README.md`](../../../../leader-dynamodb/README.md)
- [`examples/dynamodb-export/README.md`](../../../../examples/dynamodb-export/README.md)

## Continue learning

- [Bluetape4k Leader manual](../index.md)
- [Choose a backend](../guides/backend-selection.md)
- [Failure and cancellation](../guides/failure-and-cancellation.md)
