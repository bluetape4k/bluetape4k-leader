# Issue #766 lesson — 공통 backend diagnostics probe 경계

## 결정

provider별 KDoc과 예외 처리를 먼저 고치지 않고 `leader-core`에
`LeaderBackendDiagnosticsProbe.check`를 공통 base로 추가했습니다. helper는
양수·유한 `provider-native budget`만 검증하고 callback과 같은 호출 스레드에서
한 번 읽은 시각을 사용합니다. 별도 I/O, lock, client, retry, executor,
thread hop 또는 wall-clock deadline을 만들지 않습니다.

내장 callback의 일반 `Exception`은 `UNKNOWN`으로 정규화하고,
`CancellationException`은 동일 인스턴스로 재전파합니다. `InterruptedException`은
interrupted flag를 복원한 뒤 동일 인스턴스를 재전파하며, `Error`도 숨기지
않습니다. `NOT_CHECKED`를 반환하는 callback은 잘못된 내장 결과로
`IllegalArgumentException`을 냅니다. clock 오류와 timeout 검증 오류 역시
전파합니다. 기존 `checkConnectivity`와 `diagnostics` override는 source-compatible
escape hatch로 남겨 custom provider의 정보·예외 정제 책임을 caller에게
돌립니다.

## 관찰 가능한 변경

- Local, MongoDB, Lettuce, Redisson, Hazelcast, ZooKeeper의 built-in probe가
  같은 예외 경계를 사용합니다.
- Ktor built-in route는 일반 callback `Exception`을 `HTTP 200 + UNKNOWN`으로
  반환하고, cancellation/interruption/`Error`/검증 실패는 application pipeline으로
  넘깁니다. custom provider와 pipeline 응답은 caller 소유입니다.
- Spring built-in health는 일반 `Exception`을 warning 없이 `UNKNOWN`으로
  정규화하고, 포착된 cancellation/interruption/검증 실패는
  `UNKNOWN + leader.spring.health backend probe failed; status=UNKNOWN` warning을
  남깁니다. `Error`는 재전파하며 built-in detail allow-list는 원시 예외·credential·
  endpoint 값을 노출하지 않습니다.
- 이 변경은 probe를 선택한 사용자에게만 위 내장 동작을 적용합니다. `UNKNOWN`은
  readiness나 ownership 증명이 아니며, 원인 신호·readiness·runbook 정책은
  [Issue #774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)의
  후속 범위입니다.

## release pin과 문서 경계

`docs/manual/manifest.yaml`과 versioned manual은 수정하지 않았습니다. 현재
manual의 `releaseRef`/`releaseCommit`이 가리키는 0.5.0에는 새 public helper가
없으므로, 1.0.0 release commit을 고정한 뒤 #774 release train에서 manual을
갱신해야 합니다. 따라서 이번 변경은 root/module EN·KO README와 public KDoc의
동일 fact matrix만 갱신했습니다. timeout을 provider-native budget으로
전달한다는 사실을 wall-clock 보장으로 표현하지 않습니다.

## 검증 증거

- 현재 `origin/develop`에 rebase한 HEAD에서 targeted diagnostics test는
  `leader-core` 25, Lettuce 7, Redisson 7, Hazelcast 7, ZooKeeper 7,
  MongoDB 4, Ktor 12, Spring Boot 16건을 모두 통과했습니다.
- stale test report를 먼저 제거한 뒤 affected 8개 모듈의 전체 테스트와
  `detekt`를 `--no-daemon --no-configuration-cache --max-workers=1`로
  실행했습니다. XML aggregate는 총 2,652 tests, failures 0, errors 0,
  skipped 0이며 Gradle은 4분 1초 만에 `BUILD SUCCESSFUL`을 반환했습니다.
- affected 8개 모듈의 `build -x test`도 같은 rebase HEAD에서
  `BUILD SUCCESSFUL`이었습니다.
- Kotlin consumer smoke는 `kotlinc`, core jar의 helper class 검색, `javap`의
  singleton `INSTANCE`와 `check-…(long, java.time.Clock, Function1)` ABI 검사를
  통과했습니다. legacy `checkConnectivity`/`diagnostics` override 두 형태도
  외부 consumer source로 컴파일했습니다.
- `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 ./gradlew --no-daemon
  --no-configuration-cache --max-workers=1 --console=plain
  checkBinaryCompatibility`도 현재 rebase HEAD에서 `BUILD SUCCESSFUL`입니다.
- 과거 pre-rebase/stale report의 ABI 및 Redisson baseline 실패 관찰은 현재
  검증 결과로 대체되었습니다. 현재 fresh affected suite에서는 해당 실패가
  재현되지 않았으므로 과거 결과를 이번 변경의 blocker로 사용하지 않습니다.

## 다음 변경자에게 적용할 규칙

1. 새 provider는 callback을 helper에 위임하고 status mapping만 소유합니다.
   helper 안에 client 호출, 재시도, deadline, thread hop을 넣지 않습니다.
2. custom override의 descriptor/detail/HTTP payload는 caller가 정제합니다.
   built-in allow-list를 custom 값에 자동 적용한다고 가정하지 않습니다.
3. #774에서 cause cardinality, readiness/HTTP/Actuator mapping, provider timeout
   runbook, pinned release manual을 별도 계약으로 정하고 #766 base를 다시
   확장하지 않습니다.
4. release 후 corrective rollback이 필요하면 public helper ABI와 core tests는
   보존하고, provider 구현·Ktor/Spring 기대치·EN/KO README·한국어 release note를
   함께 되돌립니다. provider-native timeout을 wall-clock deadline으로 되돌려
   문서화하지 않습니다.
