# Issue #884 Testcontainers readiness 경계 진단 설계

## 문서 상태

- 상태: 사용자 승인 계획과 현재 source·dependency 조사 결과를 반영한 설계
- 작성일: 2026-09-06
- 대상 저장소: `bluetape4k/bluetape4k-leader`
- 대상 모듈: `leader-core` test fixtures, `leader-redis-lettuce`, `leader-redis-redisson`, `leader-etcd`
- 이슈: [#884](https://github.com/bluetape4k/bluetape4k-leader/issues/884)
- 최초 기준 커밋: `65731c0b4a0f046bae4c85a97ee4646c95d27ee1` (`develop`)
- 현재 재검증 기준: `5d3eefe4376634f25da5879ac8329764adcc34b3` (`develop`)
- 작업 branch: `fix/issue-884-testcontainers-readiness`
- 제외 범위: production Redis/Etcd/leader API, timeout 연장, 정상 Colima 재시작, dependency catalog 변경, publish/release/tag/merge

## 문제와 근거

Toxiproxy와 etcd container 내부 service가 기동한 뒤에도 Testcontainers의 host-side HTTP readiness가 mapped port에서 `Connection reset` 또는 timeout으로 끝나는 현상이 반복됐다. 같은 HEAD의 clean 재실행은 통과하므로 단순 재시도 성공은 해결 증거가 아니다.

현재 resolved dependency는 `io.github.bluetape4k:bluetape4k-testcontainers:2.1.0-SNAPSHOT:20260902.141040-1`과 Testcontainers `2.0.5`다. 실제 source에서 Toxiproxy는 `/version`, etcd는 `/health`를 `HttpWaitStrategy`로 mapped host endpoint에 요청한다. 이 wait가 실패하면 `GenericContainer.tryStart()`가 container 로그를 남기고 즉시 container를 제거한다. 따라서 테스트 바깥의 catch에서는 실패 당시의 container 내부 endpoint와 Docker port mapping을 함께 검사할 수 없다.

2026-09-06 clean baseline에서는 Lettuce Toxiproxy 2개, Redisson Toxiproxy 3개, `leader-etcd` 156개 테스트가 모두 통과했다. 결함은 현재도 간헐적이며, 진단 fixture가 정상 경로의 동작을 바꾸지 않아야 한다.

## 목표

Testcontainers wait가 container를 제거하기 전에 다음 세 경계를 한 진단 레코드로 수집한다.

1. target container의 network namespace에서 확인한 internal HTTP endpoint 응답
2. JVM에서 mapped host endpoint로 확인한 HTTP 응답 또는 정확한 실패
3. Docker inspect의 container 상태와 exposed-to-host port mapping

진단 결과는 원래 wait failure의 cause를 보존한 `ContainerLaunchException`과 test log에 포함한다. credential, environment 전체, container payload 전체는 기록하지 않는다.

## 선택한 설계

### 공통 test fixture

`leader-core/src/testFixtures`에 Testcontainers 전용 readiness 진단 fixture를 둔다. Lettuce, Redisson, etcd 모듈은 이미 `leader-core` test fixtures와 Testcontainers를 사용하므로 모듈별 중복 구현 대신 이 경계를 공유한다.

fixture는 기존 `WaitStrategy`를 delegate로 받아 정상 시 그대로 반환한다. delegate가 실패한 경우에만 bounded probe를 실행하고 다음 분류 중 하나를 만든다.

| 분류 | 조건 | 의미 |
|---|---|---|
| `HOST_FORWARDING` | internal HTTP 성공, port mapping 존재, host HTTP 실패 | container service와 Docker mapping 이후 host forwarding 경계에서 응답 유실 |
| `CONTAINER_SERVICE` | internal HTTP 실패 | target service 또는 endpoint 자체가 준비되지 않음 |
| `PORT_MAPPING` | expected exposed port의 host binding 없음 | Docker/Testcontainers mapping 설정 또는 inspect 경계 실패 |
| `UNKNOWN` | probe 자체가 완전한 결론을 만들지 못함 | 원래 wait failure와 수집된 부분 증거를 보존해 후속 조사 필요 |

### Internal endpoint probe

Toxiproxy와 etcd image에는 `/bin/sh`, `wget`, `curl`이 없으므로 `execInContainer`를 사용하지 않는다. 대신 immutable digest로 고정한 Alpine helper를 `container:<target-id>` network mode로 한 번 실행해 `http://127.0.0.1:<container-port><path>`를 조회한다.

- image: `alpine@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc`
- command: BusyBox `wget -qO- -T 2`
- startup/probe 상한: 5초
- 대상 path: Toxiproxy `/version`, etcd `/health`
- cleanup: 성공·실패와 관계없이 helper container를 즉시 중지·제거

로컬 실험에서 두 target image의 network namespace에 helper를 붙였을 때 첫 시도에 각각 `{"version": "2.9.0"}`과 `{"health":"true","reason":""}`를 받았다.

### Host와 Docker probe

host probe는 현재 wait와 같은 `target.host`, `target.getMappedPort(containerPort)`, path를 사용하되 진단 시점에 한 번만 실행한다. connect/read timeout은 각각 2초로 제한한다. Docker inspect는 container ID, running/status/exit code와 해당 container port의 host binding만 정규화한다. 전체 environment, labels, mount, secret-bearing command는 출력하지 않는다.

### 적용 지점

- `LettuceStrategicGroupToxiproxyCancellationTest`: per-test `ToxiproxyServer`의 `/version` wait
- `RedissonStrategicGroupToxiproxyCancellationTest`: per-test `ToxiproxyServer`의 `/version` wait
- `AbstractEtcdLeaderTest`: shared `EtcdServer`의 `/health` wait

각 fixture는 외부 wrapper가 설정한 60초 startup timeout을 늘리지 않는다. production code와 main artifact ABI에는 영향을 주지 않는다. 다만 기존에 공개되는 `leader-core` test-fixtures variant에는 Testcontainers API dependency와 `ReadinessEndpoint`/factory가 additive API로 추가된다.

## 테스트 설계

### 결정적 RED/GREEN

실제 Colima reset을 테스트에서 기다리지 않는다. pure diagnostic classifier와 wait wrapper에 probe dependency를 주입해 다음을 먼저 RED로 고정한다.

1. internal 성공 + host 실패 + mapping 존재가 `HOST_FORWARDING`으로 분류된다.
2. internal 실패가 `CONTAINER_SERVICE`로 분류된다.
3. mapping 없음이 `PORT_MAPPING`으로 분류된다.
4. delegate failure가 원래 cause를 보존하면서 endpoint와 분류를 포함한다.
5. delegate 성공 시 probe가 호출되지 않는다.

테스트는 JUnit 5, MockK, `bluetape4k-assertions`를 사용한다. production class에 test-only method를 추가하지 않는다.

### 실제 container 검증

Toxiproxy `/version`과 etcd `/health`를 각각 diagnostic wait로 시작해 정상 경로가 통과하는 integration proof를 둔다. 이후 다음을 순차적으로 clean 반복 실행한다.

- Lettuce Toxiproxy cancellation class: 5회
- Redisson Toxiproxy cancellation class: 5회
- `leader-etcd` integration suite: 5회

startup failure, failed test, error, skip은 모두 0이어야 한다. fail-then-pass는 성공으로 처리하지 않고 새 diagnostic을 root-cause 증거로 분석한다.

## 대안과 기각 사유

1. **startup timeout 연장:** 동일한 opaque failure를 늦출 뿐 경계를 구분하지 못하므로 기각한다.
2. **Testcontainers 또는 Colima 자동 재시작:** 정상 runtime을 변경하고 실제 forwarding 결함을 숨기므로 기각한다.
3. **모듈별 catch에서 inspect:** `GenericContainer`가 catch보다 먼저 target을 제거하므로 필요한 증거가 사라져 기각한다.
4. **target image 안에서 `execInContainer`:** 두 image 모두 shell/HTTP client가 없어 실행할 수 없으므로 기각한다.
5. **production wrapper 또는 leader API 변경:** test infrastructure 문제와 무관하고 blast radius를 넓히므로 기각한다.

## 호환성·보안·rollback

- Maven artifact의 production class와 binary API는 바뀌지 않는다.
- shared fixture는 공개 signature가 `WaitStrategy`를 사용하므로 test-fixtures variant에만 Testcontainers API dependency를 추가한다.
- diagnostic message는 endpoint, status, mapping, bounded response만 포함한다. container environment와 credential은 수집하지 않는다.
- rollback은 공통 fixture, 세 적용 지점, test-fixtures dependency를 함께 되돌리는 것이다.
- Alpine helper pull 또는 execution이 실패해도 원래 wait failure를 대체하지 않고 `UNKNOWN`의 부분 증거로 보존한다.

## Acceptance criteria

- [x] deterministic classifier/wrapper tests가 유효한 RED 후 GREEN이다.
- [x] 실패 진단에 internal response, host response/error, Docker mapping과 boundary 분류가 함께 포함된다.
- [x] Toxiproxy `/version`과 etcd `/health` 정상 container integration proof가 통과한다.
- [x] 기존 startup timeout을 늘리지 않는다.
- [x] 정상 Colima를 재시작하지 않는다.
- [x] Lettuce/Redisson Toxiproxy class와 `leader-etcd` suite가 각각 clean 5회 반복에서 startup failure 0이다.
- [ ] `detekt`, 전체 `./gradlew build`, `git diff --check`가 통과한다. (`detekt`/diff/ABI와 readiness 대상 테스트는 통과했다. #886 수정이 포함된 exact-head full build에서 기존 `RedissonStrategicHeartbeatExpirationRaceTest`의 0ms TTL 경계가 한 번 실패했고 단독 4/4는 통과했다. expiry conformance를 소유하는 #856에서 fixture를 보강한 뒤 다시 검증한다.)
- [x] production ABI/API diff가 없다.
- [x] exact-head inline review에서 P0=0, P1=0이다.
- [ ] exact-head PR CI는 PR 생성 권한이 열린 후 별도 게이트에서 확인한다.

## DoD 경계

구현 완료 보고에는 RED/GREEN, container 반복 횟수와 테스트 수, 전체 build, static analysis, binary API 무변경, review finding을 포함한다. PR 생성은 별도 권한이며 merge는 fresh exact-head 승인 전까지 `PENDING`이다.
