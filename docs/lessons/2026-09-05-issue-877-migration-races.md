# Migration 경합과 TTL 경계의 검증 범위

## 범위와 결정

[#877](https://github.com/bluetape4k/bluetape4k-leader/issues/877)의 기준은
`develop`의 `de8baf5d69253c19fe2c59242726430824151478`이다.
생산 코드나 공개 API를 변경하지 않고 기존 `listCandidates()`와 command 위임 경로로 테스트한다.

- source의 첫 `GET` 이후 `PTTL` 직전, 복사 후 source 재조회 직전에 writer 완료를 배치한다.
  호출 횟수와 실제 destination/token을 단언해 경합 지점이 실행되지 않는 테스트를 차단한다.
- blocking/suspend × v2/colon × 12개 경합을 실제 standalone Redis와 Cluster에서 각각 검증한다.
  source 변경·삭제·즉시 만료·동일 payload 재기록과 새 register/refresh/result writer를 포함한다.
- 같은 payload를 새 writer가 기록한 경우에도 이전 migration은 token 소유권을 잃는다.
  payload 비교만으로 cleanup하면 새 writer의 값을 삭제할 수 있으므로 token 검사를 함께 검증한다.
- `PTTL -1/0/-2/1` 반환값을 주입하는 16개 테스트는 실제 TTL 관측과 구분한다.
  실제 source TTL이 `-1`임을 별도로 확인하고, 주입값은 최초 TTL 조회 또는 재확인 분기에만 적용한다.
  실제 Redis의 즉시 만료는 `PEXPIRE 0` 이후 `PTTL -2`로 검증한다.
- 최초 TTL 조회의 `PTTL 1`은 실제 Lua의 `PX 1` 쓰기를 실행한다. 네트워크 재조회 시 값은 이미 만료될 수 있다.
  따라서 복사 성공 때만 생성되는 token을 필수로 검증하고, 조회 결과가 있으면 payload도 검증한다.
  이 테스트를 실제 Redis에서 정확히 1ms를 관측했다는 증거로 해석하지 않는다.
- reactive 경로의 동기 writer는 `boundedElastic`에서 실행한다. 호출자가 소유한 connection을 닫지 않고
  시나리오 후 `PING`으로 사용 가능 상태를 확인한다. 테스트 키 정리는 서로 다른 slot을 고려해 단일 키 `DEL`로 수행한다.

## 회귀 검출력과 잘못된 가정

초기 64개 테스트 중 35개가 fixture의 `Instant` 정밀도 차이로 실패했다.
codec은 `toEpochMilli()`와 `ofEpochMilli()`를 사용하므로 기대값도 고정 밀리초로 생성해야 한다.
기대값의 시간 필드를 제외해 통과시키지 않고, codec의 저장 정밀도에 맞춘 후 64개 통과를 확인했다.

Lua cleanup에서 token 조건을 임시 제거하자 동일 payload의 register/refresh 보호 테스트 8개가 실패했다.
조건을 복원했다. 이 실패는 fixture 오류와 별개의 회귀 검출 증거다.

복사 후 TTL의 `== 0L` 조건을 `<= 0L`로 바꾸면 영속 값인 `-1`도 만료로 잘못 판단한다.
blocking/suspend에서 재기록 경합 8개와 TTL 주입 4개가 실패했다. 두 registry의 조건을 복원했고
`src/main` diff가 없는 것을 확인했다. TTL의 음수 반환값을 하나의 만료 상태로 합치지 않는다.

TTL 판정 변이의 첫 실행은 테스트 전에 Kotlin Gradle plugin 로딩 실패로 중단됐다.
`Could not find implementation class 'org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper'`가 발생했지만
해당 JAR의 압축 무결성 검사는 통과했고, 캐시나 plugin 설정을 변경하지 않은 재실행도 통과했다.
원인은 확정하지 않았다. 재발하면 즉시 `--stacktrace`와 공유 Gradle 캐시 접근 상태를 수집한다.
재시도 성공만으로 원인이 해결됐다고 기록하지 않는다.

전체 모듈의 첫 실행은 416개 중 기존 Toxiproxy 테스트 1개가 실패했다.
서버 로그에는 HTTP server 시작이 기록됐지만 호스트의 `/version` 준비 확인은
`Connection reset`으로 60초 후 timeout됐다. 다음 테스트의 새 컨테이너는 준비 확인에 성공했다.
실패는 migration 단언 이전의 컨테이너 준비 단계이며, 포트 전달 실패의 근본 원인은 확정하지 않았다.
실패 XML을 보존한 뒤 단독 2개와 전체 416개 재실행이 통과했다. 정상 Colima를 재시작하거나 timeout을
늘려 숨기지 않는다. 다른 세션의 Gradle 실행도 관측했으므로 부하와의 인과관계를 단정하지 않는다.

## 검증 상태

- 신규 targeted 테스트: 64개 통과.
- 실제 Cluster: 필수 19개 통과. 추가 두 테스트 안에서 경합 48개를 실행한다.
- detekt: 오류 0개. IDE 진단 도구가 없어 Kotlin 컴파일과 detekt를 대체 검증으로 사용했다.
- 전체 모듈 및 최종 복원 후 검증: 416개 통과, 실패·오류·건너뜀 0개.
- 최종 Cluster 재검증: 19개 통과, 실패·오류·건너뜀 0개. `cluster_state=ok`, endpoint 6개.
  image digest는 `sha256:78eb164a6e3380b733cb3cfb91f7c54f50cba42292bcdc21b969450161af9e89`다.
- TTL 판정 변이: 64개 중 12개 실패, 생산 코드 복원 완료.
- hosted 실행과 exact-head 증거: PR 생성 후 기록한다.
- 독립 native 리뷰는 ACK와 결과를 반환하지 않아 종료했다. 이 문서는 main 검증이며 독립 리뷰 통과를 뜻하지 않는다.

최종 명령은 다음과 같다. `cleanTest`와 `cleanClusterTest`로 기존 결과를 제거한 후 실행했다.

```bash
./gradlew :bluetape4k-leader-redis-lettuce:cleanTest \
  :bluetape4k-leader-redis-lettuce:cleanClusterTest \
  :bluetape4k-leader-redis-lettuce:test \
  :bluetape4k-leader-redis-lettuce:clusterTest \
  :bluetape4k-leader-redis-lettuce:detekt \
  --no-build-cache --no-daemon --no-configuration-cache --console=plain
```

`gno update`는 성공했지만 `bluetape4k-docs`가 `.worktrees`를 제외하므로 이 문서는 아직 검색 대상이 아니다.
머지와 canonical checkout 동기화 후 다시 색인한다. 현재 문서 검증은 worktree 파일을 직접 읽어 수행했다.

## 다음 변경의 확인 항목

동일 payload 경합은 값 비교만으로 구분하지 말고 token 소유권을 확인한다.
TTL의 결정적 분기 테스트, 실제 Redis 관측, hosted 실행을 서로 다른 증거로 유지한다.
새 경합을 추가할 때는 실행 경계의 도달 여부와 source·destination·index·token 상태를 함께 단언한다.
