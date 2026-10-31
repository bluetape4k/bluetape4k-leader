# 공개 inline facade의 Kotlin consumer bytecode와 hidden shim JVM metadata 관측

## 배경

PR #992에서 여섯 backend의 facade 37개가 `inline`/`crossinline`으로 바뀌었다. 이 중 Kotlin source에서 호출할 수 있는 facade는 34개이고, 나머지 두 선언은 DynamoDB의 `@Deprecated(HIDDEN)` JVM shim이다. 사용자는 Java API와 JVM ABI 호환성을 지원 범위에서 제외하고, ABI 충돌을 허용하면서 facade/shim 36개 전체를 일반 함수로 전환하도록 승인했다. `etcdClientOf`는 기존 Kotlin non-local return 계약 때문에 inline으로 유지했다.

## 결정

- `inline` 제거의 핵심 검증은 새 Kotlin consumer bytecode가 library facade의 `INVOKESTATIC` entry로 이어지는지 확인하는 것이다. JVM descriptor만 비교하면 consumer에 복사된 본문을 발견하지 못한다.
- DynamoDB hidden shim의 `@Deprecated(HIDDEN)`, `@JvmName`, owner/name/descriptor, access/synthetic flags, annotation metadata와 `$default` entry는 현재 후보를 기록하기 위한 보조 비교다. 두 main shim의 generic JVM `Signature`는 inline 제거 뒤 사라지고 `@Deprecated` message도 갱신되어 Java reflection metadata가 달라진다. 사용자는 Java reflection 지원이나 이 metadata 유지를 요구하지 않았다.
- hidden shim의 explicit/default 네 호출은 과거 source commit `a6e17f71cdfde18b6d7e8575c7db26385902e9b4`에서 컴파일하고 candidate runtime에서 실행한다. 이는 추가적인 Kotlin bytecode 동작 관측이지 기존 JVM ABI 지원 약속이 아니다.
- `etcdClientOf`는 builder callback의 non-local `return` source 계약을 유지하기 위해 inline으로 남긴다. 처리량 개선 주장은 하지 않는다.

## 예상 밖의 문제와 해결

1. 첫 historical consumer harness는 `--rerun-tasks`가 복사해 둔 compiled consumer class를 지워 테스트를 찾지 못했다. candidate 실행에서 `compileTestKotlin`과 `testClasses`를 제외하고, `try/finally`에서 복사 class와 임시 worktree를 정리하도록 수정했다.
2. 첫 JVM metadata 검증기는 owner/name/descriptor와 `public static`만 확인했다. `javap -v -p -s` 기준 artifact를 만들자 두 hidden shim main method의 generic `Signature`가 candidate에서 사라진 사실이 드러났다. verifier가 baseline manifest와 method flags, generic `Signature`, `@Deprecated(HIDDEN)`, `@JvmName` 등 method attributes를 비교하도록 확장했다. 사용자가 Java/JVM ABI 호환성을 지원 범위에서 제외했으므로 비교 결과는 hard gate가 아니라 정보성 차이 목록으로 남겼다. hidden shim의 deprecation message도 “ABI 호환성 보존”을 뜻하지 않도록 갱신했다.
3. Historical fixture에는 default helper가 있었지만 실제 테스트가 explicit 경로만 실행했다. DynamoDB 기본 테이블을 준비하고 single/group의 default 인자 경로도 실행해 `$default` bridge를 검증했다.

## 결과와 검증

- source verifier는 승인된 facade/shim 36개 전환을 확인하고 `etcdClientOf`와 비대상 선언이 유지되는지 검사한다.
- current consumer의 explicit/default 68 callsite는 candidate library가 제공하는 public facade entry를 `INVOKESTATIC`으로 호출한다. descriptor는 고정 baseline 대신 현재 compiled library entry와 대조한다.
- historical DynamoDB consumer 네 테스트(single/group × explicit/default)는 candidate runtime에서 모두 성공했다. 실패·오류·skip은 0이다.
- pinned inline baseline `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`에서 facade consumer와 `EtcdClientOfInlineContractTest` 45건을 실행했다. 모두 실패·오류·skip 없이 통과했고, 같은 baseline의 bytecode verifier는 inline 본문이 consumer에 복사된 68개 manifest callsite를 모두 거부했다.
- 여섯 candidate module 전체 테스트는 DynamoDB 142, etcd 195, Exposed JDBC 403, Kubernetes 41, MongoDB 177, ZooKeeper 129건으로 합계 1,087건이며 모두 실패·오류·skip 없이 통과했다. 여섯 신규 facade consumer test class는 합계 43건, 별도의 `EtcdClientOfInlineContractTest`는 2건을 실행했다.
- `checkBinaryCompatibility`와 `detekt`가 성공했다. verifier self-test는 source modifier, overload, manifest owner, access/synthetic flag, generic `Signature`, annotation, baseline checksum 변조를 탐지한다. JVM ABI 비교 결과는 정보성 관측이다.
- 독립 리뷰는 P0/P1 문제를 찾지 않았다. 호출자 관점 공통 P2는 기존에 컴파일된 Kotlin consumer가 inline 본문을 계속 실행하므로 새 facade 동작을 받으려면 재컴파일해야 한다는 점이다.

## 리뷰에서 놓친 부분

Descriptor 검증만으로 JVM metadata 전체를 설명할 수는 없다. generic `Signature`, annotation, synthetic flag를 각각 확인해야 현재 후보의 차이를 정확히 보고할 수 있다. test helper가 bytecode를 만들었다는 사실도 해당 경로가 실제 실행됐다는 증거는 아니다. 결과 보고에서는 Kotlin consumer callsite와 runtime 동작을 지원 계약으로 확인하고, Java reflection metadata 및 JVM linkage는 보조 관측 결과로 구분한다.

## 다음 변경을 위한 guard

- 공개 inline API 변경을 검토할 때 source/API 선언뿐 아니라 consumer bytecode callsite도 검사한다.
- hidden shim 또는 generated `$default` bridge는 baseline artifact의 `javap -v -p -s` method metadata와 실제 consumer 실행으로 확인한다. 의도된 metadata 차이는 method 단위 allowlist와 사용자-facing 설명에 함께 기록한다.
- fixture에 explicit/default helper가 모두 있으면 각 helper가 테스트에서 실제 호출되는지 확인한다.
- 이미 컴파일된 Kotlin consumer의 inline 본문은 라이브러리 교체로 바뀌지 않는다. release와 PR 설명에 재컴파일 필요를 알리고, 성능 이득은 benchmark 증거가 있을 때만 주장한다.
