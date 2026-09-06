# Testcontainers readiness 실패의 세 경계 진단

## 배경

Testcontainers HTTP wait가 간헐적으로 실패한 뒤 재실행에서 통과하더라도 root cause가 해결된 것은 아니다. `GenericContainer`는 wait 실패 직후 target container의 로그를 남기고 제거하므로, 호출자 바깥의 catch에서 container 내부 endpoint와 port mapping을 조사하면 이미 핵심 증거가 사라져 있다.

Issue #884에서는 Toxiproxy `/version`과 etcd `/health`가 실제로 준비됐는지 다음 세 경계로 나눠 실패 직전에 수집했다.

1. target container의 network namespace에서 본 internal HTTP endpoint
2. JVM에서 본 mapped host HTTP endpoint
3. Docker inspect가 보고한 해당 exposed port의 host binding

## 적용 패턴

기존 `WaitStrategy`를 바꾸지 않고 wrapper로 감싼다. delegate가 성공하면 probe를 전혀 실행하지 않는다. 실패한 경우에만 immutable digest의 Alpine helper를 `container:<target-id>` network mode로 실행해 internal endpoint를 확인하고, host probe와 Docker mapping/state를 함께 수집한다.

분류 우선순위는 다음과 같다.

- mapping이 없으면 `PORT_MAPPING`
- internal endpoint가 실패하면 `CONTAINER_SERVICE`
- internal 성공, mapping 성공, host 실패이면 `HOST_FORWARDING`
- probe 자체가 불완전하면 `UNKNOWN`

원래 wait throwable은 새 `ContainerLaunchException`의 cause로 유지한다. collector 자체가 실패해도 원 cause를 덮지 않는다. 각 detail은 줄바꿈을 제거하고 256자로 제한하며 environment, label, mount, command 전체를 기록하지 않는다.

## distroless image 제약

Toxiproxy와 etcd target image에는 `/bin/sh`, `curl`, `wget`이 없어서 `execInContainer`로 HTTP를 확인할 수 없었다. target image에 도구를 추가하거나 production image를 바꾸지 않고 별도 helper를 같은 network namespace에 붙이는 방식이 경계를 보존했다.

helper 실행과 HTTP 요청은 one-shot 5초/2초로 제한한다. 다만 failure 시 helper image가 로컬에 없으면 registry pull 지연이 먼저 발생할 수 있고, pull 또는 helper 실행 실패는 잘못된 service 판정 대신 `UNKNOWN`으로 남긴다.

## 운영 원칙

- 정상 Colima를 재시작하지 않는다. 재시작은 forwarding 결함을 지워 원인 증거를 없앨 수 있다.
- startup timeout을 늘리지 않는다. opaque failure를 늦출 뿐 경계를 설명하지 못한다.
- fail-then-pass를 성공 증거로 처리하지 않는다. clean 반복과 failure 당시 diagnostic을 함께 본다.
- Testcontainers 기반 검증은 순차 실행한다. 같은 Docker runtime을 공유하는 Gradle job을 병렬화하지 않는다.
- 변경 범위 밖 flaky가 full build에서 발견되면 fail-then-pass로 숨기지 않고 별도 issue로 분리한 뒤 exact-head 검증을 다시 수행한다.

## 검증 결과

- classifier/wrapper unit test: 9/9
- 실제 etcd 세 경계 수집 proof: 1/1
- clean matrix 5회: Lettuce 2/2, Redisson 3/3, etcd 157/157을 매회 통과
- affected module 전체: 1,914/1,914
- repository 전체 build: 최초 working-tree 검증 4,370/4,370 통과. 이후 `leader-ktor` WebSocket cleanup timeout은 [#886](https://github.com/bluetape4k/bluetape4k-leader/issues/886)으로 분리했고, 그 수정이 포함된 최신 `develop` 기준 전체 build에서 Ktor 124/124가 통과했다. 이 재검증은 기존 Redisson expiry race의 0ms TTL 경계에서 316개 중 1개가 실패했고 단독 4/4는 통과했다. fail-then-pass를 전체 성공으로 바꾸지 않고 expiry conformance를 소유하는 #856의 검증 항목으로 넘겼다.
- `detekt`: 38 tasks 성공
- production API dump diff: 0

## 재사용 판단

이 fixture는 `leader-core` test-fixtures variant의 additive API다. main artifact와 production ABI에는 영향이 없지만, 공개 test fixture에는 Testcontainers API dependency가 추가된다. 다른 backend에 적용할 때는 health endpoint가 credential을 요구하거나 민감한 body를 반환하지 않는지 먼저 확인하고, endpoint별 정상 wait 계약을 그대로 delegate로 유지한다.
