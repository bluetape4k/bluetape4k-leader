# DynamoDB Public API KDoc

Context: Issue #365 identified missing public KDoc on DynamoDB factory classes
and client extension functions.

Decision: Add English public-facing KDoc with a short summary, a
Behavior / Contract section, and minimal usage examples. Document the
null-on-skip contract consistently for blocking, async, suspend, and virtual
thread helpers.

Outcome: DynamoDB public factory and extension APIs now describe when actions
run and when `null` is returned.

Verification: `./gradlew :bluetape4k-leader-dynamodb:compileKotlin`;
`git diff --check`.

Future guard: Any new DynamoDB public helper should state the elected-result
contract and the null-on-skip behavior in KDoc.
