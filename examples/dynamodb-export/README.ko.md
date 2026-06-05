# examples-dynamodb-export

[English](README.md) | 한국어

DynamoDB 기반 scheduled export 예제입니다. AWS-only service에서 `leader-dynamodb`를 사용해
여러 애플리케이션 replica 중 하나만 shared schedule의 export record를 쓰는 방법을 보여줍니다.

## 시나리오

두 service instance가 같은 billing export trigger를 받습니다. 각 instance는 같은
`billing-export` leader lock을 사용합니다. 선출된 노드만 export를 생성하고 export table에
한 row를 기록합니다. 경쟁 노드는 예외 없이 `SKIPPED`를 반환합니다.

## Core Features

- N replica 환경에서 scheduled export 단일 실행 보장
- `DynamoDbSuspendLeaderElector` 기반 DynamoDB conditional-write leadership
- Lock table과 export table 분리로 leadership metadata와 application data 격리
- DynamoDB Local/Testcontainers 기반 로컬 및 CI 검증
- DynamoDB TTL cleanup과 logical lease correctness를 분리해서 설명

## Table Shape

Leader lock table:

| Attribute | Type | 목적 |
|---|---|---|
| `lockName` | String hash key | Logical leader lock key |
| `leaseExpiry` | Number | Correctness에 사용하는 logical lease deadline |
| `ttl` | Number | DynamoDB TTL cleanup metadata |

Export table:

| Attribute | Type | 목적 |
|---|---|---|
| `exportId` | String hash key | 고유 export record id |
| `batchId` | String | Scheduled batch 또는 billing period |
| `nodeId` | String | Export를 기록한 선출 노드 |
| `createdAt` | String | ISO-8601 생성 시각 |
| `summary` | String | Demo export summary |

## Usage Example

```kotlin
val runner = DynamoDbScheduledExportRunner(
    options = DynamoDbExportRunnerOptions(
        nodeId = "node-a",
        lockName = "billing-export",
    ),
    elector = DynamoDbSuspendLeaderElector(asyncClient, electionOptions),
    exportTable = DynamoDbExportTable(syncClient, "billing_exports"),
)

val report = runner.runOnce("billing-2026-06-05") {
    billingExporter.writeDailyExport()
}

if (report.status == DynamoDbExportStatus.SKIPPED) {
    log.info { "다른 노드가 scheduled export lock을 보유 중입니다" }
}
```

## Demo

```bash
./gradlew :examples:dynamodb-export:run
```

Demo는 Testcontainers로 DynamoDB Local을 시작하고 lock table과 export table을 만든 뒤,
두 노드가 같은 scheduled export lock을 두고 경쟁하는 상황을 시뮬레이션합니다.

## Configuration Options

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `nodeId` | 필수 | Report와 export record에 기록할 instance 식별자 |
| `lockName` | 필수 | Scheduled export가 공유하는 leader lock 이름 |
| `waitTime` | `150.milliseconds` | 경쟁 시 skip하기 전까지 lock 획득을 기다리는 시간 |
| `leaseTime` | `5.seconds` | Logical lease duration; export critical section 예상 시간보다 길어야 함 |

## Dependency

```kotlin
dependencies {
    implementation(project(":bluetape4k-leader-dynamodb"))
    implementation(project(":examples:dynamodb-export"))
}
```

## Testing

```bash
./gradlew :examples:dynamodb-export:test
```

테스트는 Testcontainers 기반 DynamoDB Local을 사용하므로 Docker가 필요합니다.
