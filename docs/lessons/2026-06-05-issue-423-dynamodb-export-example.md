# Issue #423 - DynamoDB Scheduled Export Example

Context: `leader-dynamodb`는 preview backend로 구현되어 있었지만 `examples/`
에는 DynamoDB 기반 실행 예제가 없었다. 이번 작업은 scheduled export/billing
job을 소재로 DynamoDB conditional-write leadership을 로컬에서 확인할 수
있게 만드는 것이었다.

Decision: 새 코드는 `examples/dynamodb-export` 안에만 두고 `leader-dynamodb`
공개 API는 변경하지 않았다. Demo와 테스트는 기존 `DynamoDbLocalServer.Launcher`
를 재사용하고, lock table과 application export table을 분리했다.

Outcome: 예제는 `DynamoDbSuspendLeaderElector`로 같은 `lockName`에 대해 한
노드만 export record를 쓰고 경쟁 노드는 `SKIPPED`를 반환한다. Release 후 다음
batch는 다른 노드가 다시 획득할 수 있다.

Verification:
- `./gradlew :examples:dynamodb-export:compileKotlin --no-daemon --no-configuration-cache`
- `./gradlew :examples:dynamodb-export:test --no-daemon --no-configuration-cache`
- `./gradlew :examples:dynamodb-export:run --no-daemon --no-configuration-cache`
- `./gradlew projects --no-daemon --no-configuration-cache`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

Future guard: 새 예제 모듈은 `settings.gradle.kts`, root/examples README locale
set, repo-local `AGENTS.md`, `ci.yml` paths-filter/job/aggregator, 그리고
`examples.yml` matrix를 한 번에 갱신해야 한다.
