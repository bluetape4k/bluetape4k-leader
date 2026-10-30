# #994 backend inline facade 호출 경로 구현 계획

> **실행 지침:** 사용자 계획 승인을 받은 뒤 `$executing-plans`로 순서대로 실행한다. 구현 중에는 `$test-driven-development`와 `$bluetape-kotlin-patterns`를 적용하고, Kotlin 변경은 테스트를 먼저 추가한다.

**Goal:** PR #992에서 inline으로 바뀐 backend facade 36개를 일반 함수로 되돌려 새로 컴파일한 소비자가 라이브러리 JVM facade를 호출하게 하고, `etcdClientOf`의 non-local return 계약과 DynamoDB의 기존 JVM shim 연결을 보존한다.

**Architecture:** 여섯 backend의 36개 callback facade에서 `inline`/`crossinline`만 제거하고, 의미가 같은 경우 adapter lambda를 callback 직접 전달로 바꾼다. `etcdClientOf`는 inline으로 유지한다. source consumer 테스트, bytecode 호출 확인, 두 DynamoDB shim의 사전 컴파일 consumer 확인, 기존 binary compatibility 검사로 소스 계약과 JVM 연결을 각각 검증한다.

**Tech Stack:** Kotlin/JVM, Gradle, JUnit 5, 기존 backend test fixtures와 Testcontainers, `javap`, 기존 `checkBinaryCompatibility`.

---

## 기준과 범위

- 이슈: [#994](https://github.com/bluetape4k/bluetape4k-leader/issues/994), 구현 대상은 사용자가 승인한 설계 문서 [`2026-10-30-issue-994-inline-backend-facades-design.md`](../specs/2026-10-30-issue-994-inline-backend-facades-design.md)다.
- 저장소: `bluetape4k/bluetape4k-leader`; 기준 브랜치 `develop`; 작업 브랜치 `refactor/issue-994-inline-backend-facades`.
- 계획 작성 시 기준 SHA: `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`. 구현 시작 때 `develop`, 작업 브랜치, #994, PR #992를 다시 읽고 기준이 바뀌었으면 검토된 설계와의 차이를 먼저 해결한다. 이 SHA는 후보 source declaration inventory의 고정 기준으로 사용한다.
- PR #992에서 바뀐 총 37개 선언 중 Kotlin source-callable facade 34개와 Kotlin-hidden DynamoDB shim 2개를 일반 함수로 바꾼다. `leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/support/ClientSupport.kt`의 `etcdClientOf` 하나만 inline으로 유지한다.
- 변경 가능한 Kotlin metadata 차이는 대상 36개 선언의 inline 함수 flag와 callback parameter의 crossinline flag 제거뿐이다. JVM owner/name/descriptor, visibility, deprecation, generic/type variance, receiver·parameter·return type 및 nullability, suspend 여부, annotation, default-argument 생성 상태와 관련 access flag는 보존한다.
- callback 위임은 현재 동작을 보존한다. inline 전환으로 추가된 adapter lambda만 직접 전달이 동등함을 확인한 경우 제거하며, delegate 계약에 필요한 변환이나 실행 순서는 바꾸지 않는다.
- 새 dependency, 모듈, README/manual/KDoc 변경은 계획 범위에 없다. public signature와 동작은 그대로이므로 문서 변경은 N/A로 기록하고, 구현 과정에서 사용자 계약 변경이 드러나면 계획 승인을 다시 받는다.
- 병합은 이 계획 범위에 포함하지 않는다. PR 생성 대상은 `bluetape4k/bluetape4k-leader`, base `develop`, head `refactor/issue-994-inline-backend-facades`로 고정한다. merge는 정확한 PR head에 대한 별도의 새 승인이 있어야 한다.

## 대상 선언

| 모듈 | source-callable facade | 숨김 shim | 최종 상태 |
|---|---|---:|---|
| DynamoDB | `runIfLeader`, `runAsyncIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup`, `runVirtualIfLeader`, `runVirtualIfLeaderGroup`, `suspendRunIfLeader`, `suspendRunIfLeaderGroup` | 2 | 8개 공개 facade와 2개 shim 모두 non-inline |
| etcd | `runAsyncIfLeader`, `runAsyncIfLeaderGroup`, `suspendRunIfLeader`, `suspendRunIfLeaderGroup`, `runVirtualIfLeader`, `runVirtualIfLeaderGroup` | 0 | 6개 non-inline; `etcdClientOf`는 inline 유지 |
| Exposed JDBC | `runIfLeader`, `runAsyncIfLeader`, `runVirtualIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup` | 0 | 5개 non-inline |
| Kubernetes | `runIfLeader`, `runAsyncIfLeader`, `runAsyncIfLeaderGroup`, `suspendRunIfLeader`, `suspendRunIfLeaderGroup` | 0 | 5개 non-inline |
| MongoDB | `runIfLeader`, `runAsyncIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup`, `suspendRunIfLeaderGroup`, coroutine receiver의 `suspendRunIfLeader` | 0 | 6개 non-inline |
| ZooKeeper | `runAsyncIfLeader(path)`, `runAsyncIfLeader(lockName)`, `runAsyncIfLeaderGroup(path)`, `runAsyncIfLeaderGroup(lockName)` | 0 | 4개 non-inline |
| **합계** | **34개** | **2개** | **36개 전환, `etcdClientOf` 1개 유지** |

## 작업 순서

### 작업 1 — 기준과 이전 ABI를 고정한다

**대상 파일:** 읽기 전용으로 `docs/superpowers/specs/2026-10-30-issue-994-inline-backend-facades-design.md`, 대상 Kotlin source 7개, `scripts/compatibility/check_binary_api.py`, 모듈 `build.gradle.kts`를 확인한다.

- [ ] 구현 직전에 `git status --short --branch`, `git rev-parse HEAD`, `git rev-parse origin/develop`, `gh issue view 994`, `gh pr view 992`를 실행해 dirty state와 live 이슈/PR 상태를 기록한다. 기준이 승인된 SHA와 달라지면 변경된 source inventory 및 PR merge tree를 다시 대조한다.
- [ ] `6f6697b8a10290778ab8e16a5a6441b424f3e6e3`에서 여덟 JVM file facade의 method owner/name/descriptor와 `$default` 존재 여부를 수집한다. `javap -p -s -v` 결과를 대상 선언별로 분리해 현재 baseline으로 보관한다.
- [ ] `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`의 여덟 Kotlin source file에서 대상 37개 선언만 식별해 `facade-declarations-baseline.tsv`를 작성한다. 각 행은 source file, receiver, 함수명과 parameter signature, JVM owner/name/descriptor, 기준 `inline`/`crossinline` 상태, 목표 상태를 포함한다. TSV SHA-256을 verifier에 고정하고 37행·고유 declaration key·36개 전환/`etcdClientOf` 1개 유지가 일치해야 한다. 같은 `ClientSupport.kt`의 별도 `etcdClient` helper는 inventory 대상이 아니며 최종 diff에서 변경되지 않아야 한다.
- [ ] DynamoDB source-visible consumer compile 기준 `a6e17f71cdfde18b6d7e8575c7db26385902e9b4`에서 `DynamoDbAsyncClient.suspendRunIfLeader` 및 `suspendRunIfLeaderGroup`의 명시 인자·기본 인자 호출을 컴파일한다. `javap -c -s`로 `DynamoDbClientExtensionsKt`의 기존 owner/name/descriptor를 기록한다.
- [ ] 기준 결과에서 main method와 `$default`의 descriptor/access flag, `Signature`, nullability/type/declaration annotation attributes, DynamoDB shim의 `@Deprecated(..., level = DeprecationLevel.HIDDEN)`, 여덟 owner를 계획의 acceptance oracle로 고정한다. 원본 source를 읽지 못하거나 baseline 결과가 불일치하면 구현 전에 원인을 해결한다.

**기대 DoD:** 비교 기준 두 SHA와 각각의 목적이 분리되고, 현재 작업 브랜치 및 승인 명세와 모순이 없다.

### 작업 2 — 34개 consumer 호출 fixture와 회귀 테스트를 먼저 추가한다

**생성 파일:**

- `leader-dynamodb/src/test/kotlin/io/bluetape4k/leader/dynamodb/InlineBackendFacadeConsumerTest.kt`
- `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/InlineBackendFacadeConsumerTest.kt`
- `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/support/EtcdClientOfInlineContractTest.kt`
- `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/InlineBackendFacadeConsumerTest.kt`
- `leader-k8s/src/test/kotlin/io/bluetape4k/leader/k8s/InlineBackendFacadeConsumerTest.kt`
- `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/InlineBackendFacadeConsumerTest.kt`
- `leader-zookeeper/src/test/kotlin/io/bluetape4k/leader/zookeeper/InlineBackendFacadeConsumerTest.kt`
- `verification/issue-994/DynamoDbLegacySourceConsumerTest.kt`
- `verification/issue-994/facade-declarations-baseline.tsv`
- `verification/issue-994/facade-call-sites-baseline.tsv`
- `scripts/compatibility/verify_issue_994_facades.py`

- [ ] 여섯 `InlineBackendFacadeConsumerTest`에 표의 34개 source-callable facade를 각각 직접 호출하는 consumer 메서드를 둔다. 각 API마다 필수/선택 인자를 모두 지정하는 explicit callsite와 기본 인자를 생략하는 default callsite를 하나씩 만들어 총 68개 callsite를 고정한다. Kotlin facade를 통하지 않고 elector를 직접 부르는 테스트로 대체하지 않는다.
- [ ] 기존 backend 테스트 fixture를 사용해 callback 성공 시 1회 실행과 반환값 전달, 경합 시 `null`/callback 미실행, 비동기·suspend 완료 및 예외 전파를 API별 capability에 맞게 확인한다. 비동기/virtual-thread 반환 future와 suspend callback의 취소가 delegate에 전파되는지 확인하고, 취소 뒤 같은 lock을 다시 획득할 수 있는지 검증한다. 그룹 API는 슬롯 획득/경합뿐 아니라 callback 예외·취소 뒤 슬롯 재사용을 검증한다. 취소를 지원하지 않는 API는 실행 모델과 기존 계약을 근거로 N/A를 기록한다. Testcontainers가 필요한 API는 해당 모듈의 기존 container base class를 사용한다. 모든 신규 fixture는 Testcontainers의 loopback endpoint 또는 mock만 사용하고, DynamoDB는 테스트용 임시 자격 증명만 사용한다. endpoint와 credential을 ambient 환경변수에서 가져오지 않으며, 원격 endpoint 환경변수가 있으면 실행 전에 거부하고 민감 값을 로그로 출력하지 않는다.
- [ ] `EtcdClientOfInlineContractTest`에서 builder 설정 위임을 검증하고, 아래 호출처럼 builder lambda의 non-local return이 계속 컴파일되고 실행되는지 확인한다. 테스트가 소유한 client와 executor는 기존 `EtcdBuilderSupportTest`처럼 `try/finally` 또는 기존 fixture 수명 관리로 닫고, 테스트 종료 뒤 리소스가 남지 않았음을 확인한다. 테스트가 먼저 컴파일되지 않으면 대상 API/언어 계약을 다시 확인한다.

실제 consumer fixture는 아래처럼 block-body 안에서 non-local `return`을 사용하고, sentinel이 반환되어 marker 경로에 도달하지 않는지 실행으로 확인한다.

```kotlin
private fun returnFromEtcdClientOf(): String {
    etcdClientOf("http://127.0.0.1:2379") {
        return "returned from builder"
    }
    return "builder returned normally"
}
```

- [ ] `DynamoDbLegacySourceConsumerTest.kt`는 과거 공개 source API만 사용해 두 suspend shim 각각을 explicit/default 인자로 호출해 총 네 callsite를 만든다. `verify_issue_994_facades.py`는 이 파일을 `a6e17f71cdfde18b6d7e8575c7db26385902e9b4`의 임시 detached worktree 안에서 컴파일하고, 산출된 consumer class를 candidate test runtime에서 실행해 네 호출이 `NoSuchMethodError` 없이 현재 `DynamoDbClientExtensionsKt` shim에 연결되는지 확인한다. 임시 worktree와 임시 산출물은 스크립트 종료 때 해당 경로만 정리한다.
- [ ] `facade-call-sites-baseline.tsv`는 작업 1의 `6f6697b8…` ABI 결과에서 source-callable API 34개의 모듈, consumer test class/method, `call_style=explicit|default`, `expected_entry=main|$default`, JVM owner/name/descriptor를 가져와 작성한다. API별로 explicit/default 행이 정확히 2개씩 있어 현재 consumer 행은 68개다. DynamoDB legacy consumer는 `a6e17f71…` 결과에서 두 hidden shim의 explicit/default callsite 4개를 더해 전체 72개 행으로 고정한다. 검증기는 checksum, 고유 callsite key, API 34개, current 68개, legacy 4개 수를 확인한다. 후보 코드나 후보 bytecode에서 기대값을 자동 생성하지 않는다.
- [ ] `verify_issue_994_facades.py --self-test`는 두 TSV의 승인된 SHA-256을 검사하고, 복사본의 owner/descriptor를 바꾸면 무결성 검증이 실패하는지 확인한다. 또한 임시 source fixture에서 대상 modifier를 뒤집거나 overload를 추가했을 때 source scanner가 실패하는지 확인한다. 스크립트의 `--source-inventory`는 후보 source에서 TSV의 고정된 declaration key만 찾아 정확히 37개와 대조한다. 같은 파일의 비대상 inline `etcdClient`는 37개 수에 넣지 않고 변경되지 않았는지 별도로 확인한다. 37개 target 중 36개 facade는 최종 `inline=false`/`crossinline=false`, `etcdClientOf`는 `inline=true`여야 한다.
- [ ] Python 검증기는 고정 callsite baseline과 `javap -c -p -s` 결과를 비교해 68개 current consumer callsite가 `call_style`에 맞는 `main` 또는 `$default` method를 예상 owner/name/descriptor의 `INVOKESTATIC`으로 호출하는지 각각 확인한다. legacy consumer 검증도 4개 historical callsite의 main/`$default` 선택을 각각 검사한다. 누락·중복·잘못된 entry·owner/name/descriptor 변경이면 실패한다.
- [ ] 테스트 source를 현재 baseline에 대해 컴파일·실행해 동작 characterization이 통과하는지 확인한다. bytecode 검증기는 현재 inline 코드가 callsite에 복사된 상태를 정확히 탐지해 **호출 검증 실패**를 내야 한다. 다른 컴파일/테스트 오류가 섞이면 inline 상태의 RED로 간주하지 않는다.

**실행 예시:**

```bash
./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.InlineBackendFacadeConsumerTest' --console=plain --no-build-cache
./gradlew :bluetape4k-leader-etcd:test --tests 'io.bluetape4k.leader.etcd.InlineBackendFacadeConsumerTest' --tests 'io.bluetape4k.leader.etcd.support.EtcdClientOfInlineContractTest' --console=plain --no-build-cache
./gradlew :bluetape4k-leader-exposed-jdbc:test --tests 'io.bluetape4k.leader.exposed.jdbc.InlineBackendFacadeConsumerTest' --console=plain --no-build-cache
./gradlew :bluetape4k-leader-k8s:test --tests 'io.bluetape4k.leader.k8s.InlineBackendFacadeConsumerTest' --console=plain --no-build-cache
./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.InlineBackendFacadeConsumerTest' --console=plain --no-build-cache
./gradlew :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.InlineBackendFacadeConsumerTest' --console=plain --no-build-cache
python3 scripts/compatibility/verify_issue_994_facades.py --self-test
python3 scripts/compatibility/verify_issue_994_facades.py --source-inventory
python3 scripts/compatibility/verify_issue_994_facades.py --consumer-bytecode
```

baseline의 `--self-test`는 통과해야 한다. `--source-inventory`는 37개 선언을 찾되 36개 target modifier가 아직 inline인 상태를 정확히 알려 실패해야 하고, `--consumer-bytecode`는 68개 current facade callsite가 inline된 구현을 탐지해 실패해야 한다. 이 검증을 수행하기 전에는 production source를 바꾸지 않는다.

**기대 DoD:** 34개 API별 explicit/default 68개 Kotlin callsite가 컴파일되고 runtime 계약이 characterization 되며, bytecode 검증기가 inline callsite를 원인으로 한 단일한 RED를 낸다. 역사적 DynamoDB consumer source가 `a6e17f71…`에서 컴파일되고 4개 historical callsite가 고정된다.

### 작업 3 — 승인된 36개 선언만 일반 함수로 바꾼다

**수정 파일:**

- `leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbClientExtensions.kt`
- `leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbAsyncClientExtensions.kt`
- `leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdClientExtensions.kt`
- `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElectorExtensions.kt`
- `leader-k8s/src/main/kotlin/io/bluetape4k/leader/k8s/KubernetesClientExtensions.kt`
- `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoCollectionExtensions.kt`
- `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/CuratorFrameworkExtensions.kt`

- [ ] 작업 2의 실패 원인을 기록한 뒤, 승인 명세의 36개 target에서 `inline`과 callback의 `crossinline`만 제거한다. 다른 `inline` 함수와 `leader-etcd/.../support/ClientSupport.kt`의 `etcdClientOf`는 수정하지 않는다.
- [ ] 각 함수 body를 기존 구현과 한 줄씩 비교한다. callback을 `{ action() }`로 감싸는 코드가 inline으로 인해 추가된 adapter뿐이면 `action` 직접 전달로 고친다. receiver, 예외 처리, callback 횟수, 반환 및 async/suspend dispatch 순서는 변경하지 않는다.
- [ ] DynamoDB hidden shim 두 개의 `@Deprecated(..., level = DeprecationLevel.HIDDEN)`, `@JvmName`, signature, receiver, defaults 및 기존 위임 대상은 그대로 두고 modifier만 승인 범위대로 바꾼다.
- [ ] 변경 후 고정 source inventory에서 37개 target 중 36개 facade가 non-inline이고 `etcdClientOf`는 inline인지 확인한다. 별도 inline helper `etcdClient`와 다른 비대상 선언은 변경하지 않는다. `git diff --` 대상 7개 source 외 파일이 함께 바뀌면 scope를 분리한다.

**기대 DoD:** source diff가 36개 modifier 변경과 의미가 같은 callback 직접 전달에 한정되고, 유지 대상 등 다른 API는 손대지 않는다.

### 작업 4 — consumer bytecode, JVM ABI, 숨김 shim 연결을 검증한다

- [ ] 여섯 module test를 다시 실행한 뒤 `python3 scripts/compatibility/verify_issue_994_facades.py --source-inventory`와 `python3 scripts/compatibility/verify_issue_994_facades.py --consumer-bytecode`를 실행한다. source inventory 37/37, current consumer 68/68의 expected `INVOKESTATIC` owner/name/descriptor/main/`$default` 조합을 보고하고, 잘못된 modifier/entry와 누락된 API가 0개여야 한다.
- [ ] `javap -p -s -v`로 여덟 JVM file facade의 public method와 `$default` bridge를 작업 1 baseline과 비교한다. method owner/name/descriptor/access flag, generic `Signature`, nullability와 declaration/parameter/type annotation attributes가 일치해야 한다. `kotlin.Metadata` payload에서만 승인 대상 modifier 변화가 허용된다. DynamoDB 두 shim은 기존 owner/name/descriptor, main 및 `$default` method, `@Deprecated(HIDDEN)`과 access/synthetic flag가 일치해야 한다.
- [ ] `verification/issue-994/DynamoDbLegacySourceConsumerTest.kt`를 기준 SHA에서 다시 컴파일하고 candidate classpath에서 실행한다. 두 shim 각각의 explicit/default 네 callsite가 candidate hidden shim의 예상 main/`$default` method로 연결되고 `NoSuchMethodError`가 없어야 한다.
- [ ] `EtcdClientOfInlineContractTest`의 non-local return과 inline metadata를 확인한다. builder block 호출이 facade method callsite로 바뀌지 않았고 기존 동작을 유지해야 한다.
- [ ] `javap -c -p`로 변경된 facade body를 확인해 callback parameter가 delegate에 직접 전달되고 adapter 용도의 `action()` wrapper나 그 wrapper를 위한 synthetic lambda/`invokedynamic`이 남지 않았음을 기록한다. 이는 inline adapter 제거라는 구조 검증이며 처리량 향상을 측정했다는 주장은 하지 않는다.
- [ ] 검증기 `--self-test`가 baseline TSV의 owner 변조를 거부하는지 실행한다. 역사적 worktree, 복사된 class와 임시 보고서는 `TemporaryDirectory`/`try-finally` 정리로 처리하고, 원래 작업 worktree의 파일은 건드리지 않는다.

```bash
python3 scripts/compatibility/verify_issue_994_facades.py --self-test
python3 scripts/compatibility/verify_issue_994_facades.py --source-inventory
python3 scripts/compatibility/verify_issue_994_facades.py --consumer-bytecode
python3 scripts/compatibility/verify_issue_994_facades.py --dynamodb-legacy-consumer
```
- [ ] `./gradlew checkBinaryCompatibility --console=plain --no-build-cache`를 실행한다. descriptor/visibility/deprecation/API dump 변경이 없어야 하며 checker의 `unknown` 항목을 새로 만들지 않는다.

**기대 DoD:** bytecode의 consumer 경로가 library facade로 연결되고, JVM binary surface와 source-visible DynamoDB의 과거 consumer 연결이 모두 기준과 일치한다.

### 작업 5 — 전체 backend 검증과 동시성/리소스 회귀를 확인한다

Testcontainers backend는 동시 container 부하를 만들지 않도록 각 명령을 **순서대로 하나씩** 실행한다. 현재 macOS/Colima 환경에서는 관리된 Docker socket 환경을 상속한다. bind mount 오류가 나면 Colima를 재시작하지 않고 `colima status`, `docker context show`, `docker info` 결과와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`/`DOCKER_HOST`의 적용 여부를 기록해 VM·context·socket 원인을 구분한다. 사용자 설정이 정상인 `operation not supported`는 제품 실패로 분류하지 않는다.

```bash
./gradlew :bluetape4k-leader-dynamodb:test --console=plain --no-build-cache
./gradlew :bluetape4k-leader-etcd:test --console=plain --no-build-cache
./gradlew :bluetape4k-leader-exposed-jdbc:test --console=plain --no-build-cache
./gradlew :bluetape4k-leader-k8s:test --console=plain --no-build-cache
./gradlew :bluetape4k-leader-mongodb:test --console=plain --no-build-cache
./gradlew :bluetape4k-leader-zookeeper:test --console=plain --no-build-cache
./gradlew detekt --console=plain --no-build-cache
./gradlew checkBinaryCompatibility --console=plain --no-build-cache
git diff --check
```

- [ ] 각 실패는 module, 테스트 이름, backend/container startup, 제품 코드 원인으로 분리한다. 실패한 모듈의 관련 consumer fixture와 전체 해당 module test를 다시 실행한 다음 downstream review를 갱신한다. skip, 미실행, 환경 discovery 실패는 PASS로 처리하지 않는다.
- [ ] 여섯 모듈의 `build/test-results/test/*.xml`에서 실행·성공·실패·skip 수와 새 consumer test class의 실제 실행 건수를 수집한다. 신규 consumer 테스트의 skip 수는 0이어야 한다. Testcontainers 또는 임시 client를 소유하는 fixture가 종료 시 이를 닫는 기존 lifecycle 경로를 사용했는지 확인하고, 별도 executor는 `finally`에서 종료한다. 취소/예외 뒤 같은 lock 또는 group slot의 재획득 성공은 리소스 반납 증거로 기록한다.
- [ ] 기존 `ZooKeeperApiCoverageTest`, `EtcdLeaderElectorFactoryTest`, `ExposedJdbcLeaderElectionTest`가 유지되며 새 facade test가 mega-test의 실패 원인 구분을 악화하지 않는지 확인한다.
- [ ] 단위/통합 테스트, ABI 검사, Detekt, consumer bytecode proof와 `git diff --check`를 정확한 구현 head에 묶어 기록한다.

**기대 DoD:** 여섯 module의 기존 테스트와 신규 facade 계약 테스트, Detekt, ABI 검사 및 whitespace 검사가 모두 통과하고, 실행되지 않은 항목이 명시된다.

### 작업 6 — 범위/문서 검토와 PR 전달 준비

- [ ] approved spec의 각 acceptance item을 source, test, ABI 또는 consumer evidence에 연결한 traceability table을 완성한다.
- [ ] Kotlin pattern, callback 실행/경합, lifecycle/취소, secret/log, JVM binary/source compatibility, consumer ergonomics 관점의 7-Tier final diff review를 실행한다. Type A six-lens independent review에서 P0/P1이 0인지 통합 검토한다.
- [ ] README, Korean/English manual, KDoc, CI/Nightly, BOM, module registration 변경은 source signature와 사용자 동작이 유지되고 `settings.gradle.kts` 및 workflow 변경이 없다는 구체적 diff evidence로 N/A 처리한다. 새로운 migration 문구가 필요하다는 finding이 나오면 문서를 추가하고 한국어/영어 짝을 검증한다.
- [ ] 변경이 다른 backend에도 적용할 수 있는 재사용 guard를 남겼다면 `docs/lessons/2026-10-30-issue-994-inline-backend-facades.md`에 한국어 lesson을 작성하고 `gno update`, `gno embed --collection bluetape4k-docs`, 대표 검색으로 확인한다. 재사용할 신규 규칙이 없으면 구체적 evidence를 근거로 lesson N/A를 기록한다.
- [ ] 승인된 저장소/브랜치/base/head에 한해 Lore trailer를 포함한 한국어 커밋을 만들고 exact head를 push한다. `bluetape4k/bluetape4k-leader`, `develop` ← `refactor/issue-994-inline-backend-facades`의 PR을 한국어로 작성하고, issue link·milestone·labels 및 `## DoD Status`를 live read-back 한다.
- [ ] exact PR head CI, live review/thread, 독립 code review 및 single-developer lane의 human-review N/A 근거를 다시 읽는다. `gh pr view <PR> --repo bluetape4k/bluetape4k-leader --json headRefOid,baseRefOid,state,mergeable,reviewDecision,reviews,reviewRequests,statusCheckRollup,url`과 `gh pr checks <PR> --repo bluetape4k/bluetape4k-leader --json name,state,bucket,link,workflow,startedAt,completedAt` 결과로 PR head와 check matrix를 고정한다. 실패 run은 `gh run view <RUN_ID> --repo bluetape4k/bluetape4k-leader --json url,headSha,conclusion,jobs` 및 `gh run view <RUN_ID> --repo bluetape4k/bluetape4k-leader --log-failed`로 URL, head SHA, 실패 job/log, retry 전 첫 실패와 최종 결과를 기록한다. `skipped` 및 미실행 job은 PASS로 세지 않는다. merge-ready DoD를 보고한 뒤 멈춘다. merge는 해당 exact PR/head에 대한 새 승인 전에는 실행하지 않는다.

**기대 DoD:** 구현이 승인 설계와 일치하고, 필수 검증과 독립 review가 exact head에 대해 완료되며, PR의 live state가 보고된다. fresh merge approval 전까지 merge하지 않는다.

## 수용 기준 추적

| 승인 명세 기준 | 구현 작업 | 통과 증거 |
|---|---|---|
| 37개 target 선언을 정확히 분류하고 36개를 non-inline, `etcdClientOf` 하나를 inline으로 유지 | 작업 1–4 | 기준 HEAD에서 만든 checksum-pinned 37 declaration manifest와 candidate source scanner 결과, 비대상 `etcdClient` 불변 확인, `etcdClientOf` non-local return consumer test |
| callback, 기본값, 반환값, 경합, async/suspend 완료·취소·정리와 예외 계약 유지 | 작업 2, 3, 5 | 34개 API의 explicit/default 68 callsite, cancellation/lock-slot 재획득 test, 여섯 전체 module test |
| 34개 consumer가 explicit/default 인자 모두에서 재컴파일 후 대응 facade method를 호출 | 작업 1, 2, 4 | checksum-pinned 68-row callsite manifest, `--self-test`, `javap` 검증기에서 68/68 `INVOKESTATIC` owner/name/descriptor 및 main/`$default` entry 확인 |
| 과거 DynamoDB source-visible API와 현재 hidden JVM shim의 연결/ABI 보존 | 작업 1, 2, 4 | `a6e17f71…` consumer compile/run의 네 explicit/default callsite, `6f6697b8…` baseline descriptor/access/Signature/annotation 대조 |
| JVM API surface와 Kotlin source metadata 보존, 불필요한 문서/모듈/dependency 변경 없음 | 작업 1, 3, 4, 6 | `checkBinaryCompatibility`, `javap -v` method attributes, source inventory/diff, N/A evidence |

## 위험, 중단 기준, 되돌리기

| 위험 | 막는 증거 | 실패 시 조치 |
|---|---|---|
| inline 제거 중 callback wrapper 의미/실행 시점 변경 | API별 callback 수·경합·반환·예외 테스트와 body diff review | 영향 받은 함수의 기존 위임 구조를 복원하고 테스트를 보강한다. |
| DynamoDB hidden shim 이름·descriptor 또는 `$default` bridge 드리프트 | 두 기준 SHA, `javap -p -s -v`, 역사적 consumer test | shim annotation/name/signature를 복원하고 consumer/ABI 검증을 다시 한다. |
| `etcdClientOf`의 inline/non-local return 소스 계약 손실 | non-local return fixture와 consumer bytecode 비교 | factory 선언을 inline으로 복원하고 관련 검증을 다시 한다. |
| Testcontainers 환경 오류를 제품 실패 또는 성공으로 오분류 | module별 로그와 Docker context/socket 사전 확인 | 실행 환경을 진단하고 미실행 검증은 PENDING으로 둔다. |
| 대상 외 formatting 또는 API 변경 유입 | `git diff --stat`, path allowlist, `git diff --check` | scope 밖 변경을 분리하고 승인된 파일만 남긴다. |

되돌릴 때는 먼저 현재 diff와 파일 소유권을 확인한 뒤 이 계획이 추가한 7개 source 파일만 복원하고, 새 테스트·fixture·검증기 파일만 각각 제거한다. 계획/명세, 기존 사용자 파일, 다른 브랜치 변경은 reset/clean으로 지우지 않는다.

## 실행 전 승인 상태

- 설계 방향: 승인됨 — facade 36개 전환, `etcdClientOf` 유지.
- 설계 명세: 사용자 승인 및 독립 ABI/test 리뷰 완료.
- 구현 계획: 사용자 승인 및 독립 6 관점·통합 검토·SPW-01–05 완료. [통합 검토 결과](../reviews/2026-10-30-issue-994-inline-backend-facades-plan-review.md)는 P0/P1/P2/P3=0이다.
- 구현 및 테스트 실행: 승인됨. 승인된 정확한 scope에서만 진행하며, 사용자 계약 또는 target을 넓히면 다시 계획 승인을 받는다.
