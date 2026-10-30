# #994 backend facade `inline` 설계

## 상태와 기준

- GitHub 이슈: [#994](https://github.com/bluetape4k/bluetape4k-leader/issues/994)
- 기준 브랜치: `develop` at `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`
- PR #992 기록: base `9160010265b6c2cc95e91bcda7b5d5e7620ea095`, head `6170195b29cacc3efa09e6a41d36e2d2cc563d33`, squash 결과 `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`
- squash 결과의 실제 첫 부모 및 inline 이전 develop 트리: `6f6697b8a10290778ab8e16a5a6441b424f3e6e3`. GitHub PR에 기록된 base와 merge 시 실제 대상 트리는 다르므로 둘을 구분한다.
- DynamoDB 이전 공개 API source baseline: `a6e17f71cdfde18b6d7e8575c7db26385902e9b4` (`8cd7c5c6`의 부모). 이 시점에는 suspend extensions가 `DynamoDbClientExtensions.kt`에서 Kotlin source-visible이었다.
- 설계 및 구현 범위 승인: 2026-10-31, Kotlin 사용자만 지원하고 Java API와 JVM ABI 호환성을 약속하지 않으며 facade/shim 36개를 일반 함수로 전환하고 `etcdClientOf`는 non-local return 소스 계약을 위해 inline으로 유지
- 사용자 허용 범위: 필요한 경우 ABI 충돌을 허용한다. 현재 후보에서 확인된 두 DynamoDB shim main method의 generic JVM `Signature` 소실도 수용한다.

## 문제와 목표

PR #992는 여섯 backend 모듈의 기존 선언 37개를 `inline`으로 전환했다. 여기에는 Kotlin 소스에서 호출 가능한 facade 34개, Kotlin 소스에서 숨긴 DynamoDB JVM shim 2개, `etcdClientOf` 1개가 있다. Kotlin/JVM 소비자는 public inline 함수 본문을 호출 지점에 복사할 수 있으므로, 이미 컴파일된 소비자의 동작은 라이브러리 업데이트만으로 새로운 facade 구현을 따라가지 않는다. 현재 benchmark는 backend elector를 직접 호출하며 이 facade들이나 inline/non-inline 차이를 측정하지 않는다.

목표는 37개 선언을 하나씩 분류하고, 측정된 이득이나 필요한 Kotlin 제어 흐름 계약이 없는 backend 실행 facade와 hidden shim 36개를 일반 함수로 바꾸는 것이다. 새로 컴파일한 Kotlin 소비자가 facade를 호출하고 callback 동작이 유지되는지 검증한다. Java source/reflection 사용과 JVM ABI 호환성은 지원 계약에서 제외한다. ABI 검사는 이번 후보의 차이와 실수로 생긴 서명 변경을 구분하는 보조 증거로 남긴다.

## 변경 범위 37개

| 모듈 | inline 전환 선언 수 | 소스 owner / 선언 | JVM facade owner | 선언별 기대 상태 |
|---|---:|---|---|---|
| DynamoDB | 10 | `DynamoDbClientExtensions.kt` receiver `DynamoDbClient`: `runIfLeader`, `runAsyncIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup`, `runVirtualIfLeader`, `runVirtualIfLeaderGroup`; receiver `DynamoDbAsyncClient`: `legacySuspendRunIfLeader`, `legacySuspendRunIfLeaderGroup`; `DynamoDbAsyncClientExtensions.kt` receiver `DynamoDbAsyncClient`: `suspendRunIfLeader`, `suspendRunIfLeaderGroup` | `DynamoDbClientExtensionsKt`; `DynamoDbAsyncClientExtensionsKt` | 10개 모두 non-inline. 이 중 2개는 Kotlin source에서 숨긴 JVM shim이다. |
| etcd | 7 | `EtcdClientExtensions.kt` receiver `Client`: `runAsyncIfLeader`, `runAsyncIfLeaderGroup`, `suspendRunIfLeader`, `suspendRunIfLeaderGroup`, `runVirtualIfLeader`, `runVirtualIfLeaderGroup`; `support/ClientSupport.kt`: `etcdClientOf(endpoint: String, ..., block: ClientBuilder.() -> Unit)` | `EtcdClientExtensionsKt`; `ClientSupportKt` | operation facade 6개는 non-inline, `etcdClientOf` 1개는 inline 유지 |
| Exposed JDBC | 5 | `ExposedJdbcLeaderElectorExtensions.kt` receiver `Database`: `runIfLeader`, `runAsyncIfLeader`, `runVirtualIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup` | `ExposedJdbcLeaderElectorExtensionsKt` | 5개 모두 non-inline |
| Kubernetes | 5 | `KubernetesClientExtensions.kt` receiver `KubernetesClient`: `runIfLeader`, `runAsyncIfLeader`, `runAsyncIfLeaderGroup`, `suspendRunIfLeader`, `suspendRunIfLeaderGroup` | `KubernetesClientExtensionsKt` | 5개 모두 non-inline |
| MongoDB | 6 | `MongoCollectionExtensions.kt` receiver `MongoCollection<Document>`: `runIfLeader`, `runAsyncIfLeader`, `runIfLeaderGroup`, `runAsyncIfLeaderGroup`, `suspendRunIfLeaderGroup`; receiver `CoroutineMongoCollection<Document>`: `suspendRunIfLeader` | `MongoCollectionExtensionsKt` | 6개 모두 non-inline |
| ZooKeeper | 4 | `CuratorFrameworkExtensions.kt` receiver `CuratorFramework`: `runAsyncIfLeader(path: ZooKeeperElectionPath)`, `runAsyncIfLeader(lockName: String)`, `runAsyncIfLeaderGroup(path: ZooKeeperElectionPath)`, `runAsyncIfLeaderGroup(lockName: String)` | `CuratorFrameworkExtensionsKt` | 4개 모두 non-inline |
| **합계** | **37** | 여섯 핵심 facade 파일(`DynamoDbClientExtensions.kt`, `EtcdClientExtensions.kt`, `ExposedJdbcLeaderElectorExtensions.kt`, `KubernetesClientExtensions.kt`, `MongoCollectionExtensions.kt`, `CuratorFrameworkExtensions.kt`)의 34개 외에 `DynamoDbAsyncClientExtensions.kt` 2개와 `support/ClientSupport.kt`의 factory 1개를 포함한다. | 여덟 JVM file facade | 36개 전환, `etcdClientOf` 1개 유지 |

이 목록은 merge 시 실제 대상 트리 `6f6697…`와 squash 결과 `ad4110…` 사이의 main source diff에서 추출하고, GitHub가 기록한 PR base→head diff와 대조했다. 두 경로 모두 대상 API에서 37개 전환을 확인했다. PR 기록 base(`916001…`)와 squash 결과의 첫 부모(`6f6697…`)는 다르므로 둘을 같은 SHA로 취급하지 않는다. 위 여섯 핵심 facade 파일만 보면 34개이며, 그중 hidden shim 두 개는 Kotlin 소스에서 호출할 수 없다. 나머지 소스에서 호출 가능한 facade 두 개는 `DynamoDbAsyncClientExtensions.kt`, 유지 대상 하나는 `support/ClientSupport.kt`에 있다. 기존 inline 선언, private inline helper, 테스트 helper는 37개에 포함하지 않는다.

## 설계 결정

### 1. 36개 `crossinline` callback facade와 DynamoDB hidden shim에서 `inline`과 `crossinline`을 제거한다

36개 선언은 callback parameter를 `crossinline`으로 받으며 `reified` type parameter를 사용하지 않는다. `crossinline`은 inline callback의 non-local return을 금지한다. 해당 함수를 일반 함수로 만들면 일반 lambda도 non-local return을 허용하지 않으므로 이 제한은 유지된다.

이 변경은 새 소비자 호출이 consumer bytecode에 복사된 facade 구현 대신 공개 JVM facade method로 이어지게 한다. `crossinline` 때문에 PR #992에서 추가한 어댑터 lambda는 직접 callback 전달로 복원한다. 별도 측정 결과가 없으므로 이 설계는 성능 개선을 주장하지 않으며, stack trace와 미세 호출 비용은 기능 호환성 주장에 포함하지 않는다.

소스에서 호출 가능한 대상 34개는 새 Kotlin 소비자가 업데이트된 라이브러리 함수를 호출하게 한다. 기존에 이미 inline된 소비자 bytecode는 재컴파일 전까지 변경되지 않는다. 나머지 두 개는 DynamoDB `@Deprecated(HIDDEN)` legacy suspend shim이며 Kotlin source API가 아니다. 제품은 Java source/reflection 또는 JVM ABI 호환성을 약속하지 않는다. 두 shim의 `@Deprecated(HIDDEN)`, `@JvmName`, JVM descriptor, access/synthetic flags와 default bridge 비교는 현재 결과를 기록하는 보조 검증이다. 현재 후보는 이 항목을 유지하지만 ABI 변경 자체는 승인 범위 안이다.

두 shim의 기준 owner는 `io/bluetape4k/leader/dynamodb/DynamoDbClientExtensionsKt`다. 실제 main entry point와 `$default` bridge를 구현 전후 artifact에서 `javap -v -p -s`로 확인한다. 이름과 descriptor 비교는 현재 결과를 기록하기 위한 것이며 ABI 유지 보증이 아니다.

```text
suspendRunIfLeader:
(Lsoftware/amazon/awssdk/services/dynamodb/DynamoDbAsyncClient;Ljava/lang/String;Lio/bluetape4k/leader/dynamodb/DynamoDbLeaderElectionOptions;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
suspendRunIfLeader$default:
(Lsoftware/amazon/awssdk/services/dynamodb/DynamoDbAsyncClient;Ljava/lang/String;Lio/bluetape4k/leader/dynamodb/DynamoDbLeaderElectionOptions;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;
suspendRunIfLeaderGroup:
(Lsoftware/amazon/awssdk/services/dynamodb/DynamoDbAsyncClient;Ljava/lang/String;Lio/bluetape4k/leader/dynamodb/DynamoDbLeaderGroupElectionOptions;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
suspendRunIfLeaderGroup$default:
(Lsoftware/amazon/awssdk/services/dynamodb/DynamoDbAsyncClient;Ljava/lang/String;Lio/bluetape4k/leader/dynamodb/DynamoDbLeaderGroupElectionOptions;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;ILjava/lang/Object;)Ljava/lang/Object;
```

Kotlin 소비자를 새로 컴파일할 때는 `DeprecationLevel.HIDDEN` 때문에 shim에 직접 접근할 수 없다. 원래 공개 API 기준 소스인 `a6e17f71…`로 인자를 모두 지정한 호출과 기본 인자를 쓰는 호출을 각각 컴파일하고 candidate artifact로 실행해 보조적인 legacy bytecode 관측을 한다. 이는 Java API나 기존 ABI 지원 약속이 아니다. 기본 인자 호출은 `$default` method를 사용한다. `javap -v -p -s`에서 각 entry point의 owner/name/descriptor와 접근 flag를 baseline artifact와 method별로 비교해 이번 후보에서 실제로 유지된 정보를 기록한다. main shim method의 `@Deprecated(HIDDEN)` annotation과 level, `$default`의 synthetic/access flag와 default-argument bridge 생성 상태도 별도로 기록한다. `$default`에 `FINAL`이 있다고 미리 가정하지 않는다. Kotlin class-level `@Metadata` payload 전체 일치도 요구하지 않는다. 대상 36개에서 inline function flag와 callback parameter의 `crossinline` flag가 제거되는 것은 의도된 metadata 변화다. 두 hidden shim main method의 generic JVM `Signature`도 사라져 Java reflection의 generic parameter/type 정보가 축소된다. 사용자는 이를 허용했다. JVM owner/name/descriptor, flags, annotations와 bridge의 다른 차이가 발견되어도 Kotlin source/runtime 계약이 유지되면 ABI 지원 약속 위반으로 취급하지 않고 정확한 변경으로 보고한다. `6f6697…`은 inline 전환 전 API tree 비교 자료이며 baseline artifact는 기준 commit에서 직접 빌드해 고정한다.

### 2. etcd `etcdClientOf`는 inline으로 유지한다

`etcdClientOf`의 builder lambda는 `crossinline`이 아니다. 현재 선언은 lambda 안의 non-local `return`을 허용한다. `inline` 제거는 기존 Kotlin 소스 소비자가 사용할 수 있던 제어 흐름을 막을 수 있으므로 이 설계에서는 그 소스 계약을 유지한다. 별도 benchmark는 아직 없어 성능 이점을 근거로 유지하는 것은 아니다.

향후 endpoint 검증, option 설정 또는 client 생성 방식을 바꿔도 이미 컴파일된 소비자에서는 기존 inline 코드가 계속 실행될 수 있다. 이는 source compatibility가 아니라 이미 컴파일된 소비자의 본문이 고정되는 제약이다. 유지하는 Kotlin 소스 계약은 builder lambda의 non-local return 허용이다. 이 제어 흐름을 그대로 지원하려면 호출 지점에서 inline해야 한다. callback 자체를 non-inline helper에 넘기는 얇은 wrapper는 이 소스 계약을 보존하지 못하며, `@PublishedApi` helper를 추가하면 별도 consumer-visible ABI 수명이 생기므로 이번 범위에서 선택하지 않는다. Kotlin의 [`@PublishedApi`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-published-api/-init-.html) 계약상 해당 internal helper도 consumer-visible ABI로 취급해야 한다. body를 바꾸는 릴리스에서는 이미 컴파일된 소비자에게 변경이 전달되지 않을 수 있다는 점과 재컴파일 요구를 알린다.

Kotlin external consumer fixture는 `etcdClientOf`의 builder lambda에서 enclosing caller를 sentinel `String` 반환으로 종료하고, factory 호출 뒤의 marker에 도달하지 않는 것을 컴파일·실행으로 확인한다. 이 조기 반환은 callback 이후 factory의 client 생성 경로(`build()`)도 건너뛴다. 기존 `EtcdBuilderSupportTest.kt`의 builder 실행/우선순위 검사는 이 non-local return 계약을 직접 증명하지 않으므로 이 fixture로 보강한다. 이 source 계약을 제거하려면 별도 결정이 필요하다.

### 3. Kotlin 동작 계약을 유지하고 callback을 직접 위임한다

36개 함수의 Kotlin 호출 형태, 기본 인자, callback 실행 조건·횟수, 반환값/null 경합 결과, async/suspend/virtual 완료 형태, group 위임, 예외 값과 전파 방향을 보존한다. Java API와 JVM ABI 호환성은 계약하지 않는다. `inline`/`crossinline`을 제거하고, PR #992에서 callback을 non-inline elector에 넘기기 위해 생긴 ` { action() } ` 어댑터를 원래의 직접 위임(`..., action`)으로 되돌린다. 직접 전달이 동등하지 않은 경로가 발견되면 필요한 어댑터와 이유를 기록한다. 이는 PR #992 이전 위임 경로를 복원하고 불필요한 캡처 lambda 생성을 피하기 위한 범위다. ABI 검사는 현재 생성된 owner/name/descriptor와 DynamoDB hidden shim의 `@JvmName` 정보를 관측해 보고하는 데 사용한다.

## 검증 설계

1. 여섯 모듈의 외부 Kotlin consumer fixture에서 source-callable하게 전환되는 34개 함수 호출을 모두 컴파일하며, default 인자를 쓰는 호출과 명시적 인자를 쓰는 호출도 포함한다. DynamoDB hidden shim 두 개는 Kotlin source call fixture 대신 과거 source-visible API consumer를 컴파일·실행해 추가 관측한다. 이는 기존 JVM ABI 지원 약속이 아니다.
2. 34개 consumer call site를 candidate에 대해 컴파일하고 `javap -c -p -s`로 대조한다. 각 call site는 candidate가 만든 대응 public facade entry의 owner/name/descriptor를 가리키는 `INVOKESTATIC`을 사용해야 한다. 이 비교는 고정 baseline descriptor가 아니라 현재 compiled library entry와 consumer invocation을 대조한다. PR #992 artifact의 inlined body 비교는 historical debugging 자료이며 Kotlin source/runtime 검증을 대신하지 않는다.
3. 정확히 37개 대상 이름을 고정한 source inventory 검사로 누락·추가를 찾고 선언별 기대 상태를 확인한다: 36개는 `inline`/`crossinline` 없음, `etcdClientOf`만 inline 유지. `DynamoDbClientExtensionsKt` hidden shim 둘의 owner, method flags, `@Deprecated(HIDDEN)`, `@JvmName`, generic `Signature`, descriptor와 `$default` bridge는 기준 artifact와 비교해 차이를 기록한다. 이 JVM metadata 비교는 정보용이며 차이 자체는 실패 조건이 아니다. Kotlin metadata에서 해당 36개 inline function flag와 callback `crossinline` flag 제거는 의도된 변화다. class-level `@Metadata` payload 전체 일치는 요구하지 않는다.
4. 기존 direct-test coverage와 남은 간극은 아래 표로 구분한다. elector 내부 구현 테스트는 wrapper를 직접 호출하지 않으면 facade coverage로 계산하지 않는다.
5. 34개 source-callable facade를 직접 호출하는 runtime test matrix를 작성한다. 각 facade family와 backend에서 success return, contention 시 `null`/callback 미실행, async/suspend 예외 전파, virtual completion, group slot 위임 중 각 함수에 적용되는 핵심 계약을 직접 검사한다.
6. `checkBinaryCompatibility`와 consumer/API 검사를 보조 증거로 사용해 현재 source/API 및 JVM metadata 결과를 기록한다. Java/JVM ABI 보증으로 해석하지 않는다. 구현 계획에서 각 모듈의 실제 task 경로, 34개 call-site와 direct runtime test 매핑, Testcontainers 순서를 고정한다.
7. etcd `etcdClientOf`의 non-local return source/behavior fixture는 hard acceptance test다. DynamoDB hidden shim의 JVM name/descriptor/linkage와 historical consumer 실행은 추가 관측으로 기록한다.
8. benchmark를 새로 추가하려면 실제 facade 호출 비용만 분리한 controlled backend를 사용한다. 네트워크, database, Kubernetes API 등 backend I/O 중심 benchmark는 inline modifier의 효과를 증명하지 못한다.

### 현재 facade 직접 호출 테스트 범위

| 모듈 | 기존 facade 직접 호출 근거 | 설계에 필요한 보완 |
|---|---|---|
| DynamoDB | 변경 facade 직접 호출 테스트를 찾지 못함. elector 직접 테스트는 wrapper 호출을 증명하지 않음. | 새 외부 consumer fixture와 facade 직접 runtime test |
| etcd | `EtcdLeaderElectorFactoryTest.kt`가 `runVirtualIfLeader`를 직접 호출해 잘못된 lock 이름을 검증한다. 성공/경합/callback 실행은 커버하지 않는다. `EtcdBuilderSupportTest.kt`는 builder 실행·설정 순서를 검증한다. | `etcdClientOf` source/behavior fixture, 나머지 5개 facade fixture/test, `runVirtualIfLeader` 성공 계약 보강 |
| Exposed JDBC | `ExposedJdbcLeaderElectionTest.kt`의 성공 경로와 group test가 facade를 직접 호출함. 경합/예외 범위는 별도 확인 필요. | 기존 테스트를 유지하고 빠진 결과·경합 계약을 직접 추가 |
| Kubernetes | 변경 facade 직접 호출 테스트를 찾지 못함. elector 직접 테스트는 wrapper 호출을 증명하지 않음. | 외부 consumer fixture와 facade 직접 runtime test |
| MongoDB | 변경 facade 직접 호출 테스트를 찾지 못함. elector 직접 테스트는 wrapper 호출을 증명하지 않음. | 외부 consumer fixture와 facade 직접 runtime test |
| ZooKeeper | `ZooKeeperApiCoverageTest.kt`가 4개 변경 overload를 직접 부르지만 단일 mega-test다. | overload별 실패를 구분할 수 있도록 consumer·runtime assertions 보강 |

이 명세와 구현 계획은 승인되었고, facade 직접 테스트와 Kotlin consumer 검증을 추가했다. 아래 검증 항목은 candidate의 acceptance evidence와 별도 ABI 관측으로 사용한다.

## 대안과 이유

- **37개 모두 일반 함수로 전환:** 36개 callback의 동작 경계는 유지하지만, `etcdClientOf`의 non-local return Kotlin 소스 계약을 바꾼다. 사용자는 이 소스 계약을 변경하지 않았으므로 선택하지 않는다.
- **37개 모두 inline 유지:** 현재 benchmark는 facade의 inline 이득을 측정하지 않으며 consumer bytecode 고정 문제를 해결하지 않는다. 선택하지 않는다.
- **36개 일반 함수 + `etcdClientOf` 유지:** 측정 근거가 없는 facade inline을 줄이고 `etcdClientOf`의 Kotlin non-local return 계약을 유지한다. Java API/JVM ABI 호환성을 지원하지 않는다는 승인 범위에 따라 선택했다.

## 완료 기준

- source inventory가 대상 37개를 빠짐없이 식별하고, 36개 facade/shim에서 `inline`/`crossinline`을 제거하며 `etcdClientOf`만 inline으로 남긴다.
- 36개 facade의 callback을 원래의 직접 위임으로 전달하고, 기본 인자·반환값·경합 결과·callback 횟수·비동기 완료·예외 전파 계약을 유지한다.
- 기존 Kotlin source-visible DynamoDB API에서 컴파일한 explicit/default 호출을 candidate에서 실행해 결과를 보조적으로 기록한다. 이 검증은 기존 ABI 지원 약속을 만들지 않는다.
- 34개 새 consumer call site가 candidate의 대응 facade method를 호출하고, `etcdClientOf` consumer는 non-local return 계약을 유지한다.
- 여섯 backend 모듈의 facade 직접 테스트와 Kotlin consumer 검증이 통과한다. ABI 검증 결과와 Java generic reflection metadata 변경은 별도 관측 결과로 보고한다.

## 제약과 알려진 한계

- Kotlin 공식 문서는 public inline body가 소비자에 인라인되며, 라이브러리 변경 뒤에도 이미 컴파일된 consumer 동작이 고정될 수 있음을 설명한다. 이 동작은 consumer bytecode 또는 두 버전 consumer fixture로 검증해야 하며 japicmp descriptor 확인만으로는 증명할 수 없다.
- 현재 backend benchmark에서 inline/non-inline 비교를 찾지 못했다. 이 설계는 성능상 이득이나 손실의 크기를 단정하지 않는다.
- 변경을 배포해도 이미 컴파일된 inline consumer bytecode는 재컴파일 전까지 유지된다. 새 consumer는 일반 facade method를 호출하며, direct callback forwarding으로 PR #992가 추가한 adapter lambda 비용을 되돌리는 것이 목표다.
- 범위는 신규 37개 선언에 한정한다. 기존 `etcdClient` inline factory, 다른 private helper, unrelated modules, 새로운 dependency 및 API 이름 변경은 다루지 않는다.

## 참고 자료

- [Kotlin inline functions](https://kotlinlang.org/docs/inline-functions.html) — 인라인 호출, callback parameter, public API compatibility 주의사항
- [Kotlin language specification: inlining](https://kotlinlang.org/spec/kotlin-spec.html#inlining) — `crossinline`, captured lambda 및 non-local return 규칙

## 승인 상태

- 2026-10-31 사용자가 Kotlin 사용자만 지원하고 ABI 충돌을 허용하며 DynamoDB hidden shim을 포함한 facade/shim 36개를 모두 일반 함수로 전환하도록 승인했다.
- `etcdClientOf`는 기존 Kotlin builder callback의 non-local return 계약을 위해 inline으로 유지한다.
- 성능 개선은 주장하지 않는다. Kotlin 호출/runtime 계약과 callback lifecycle 테스트가 핵심 완료 기준이다.
