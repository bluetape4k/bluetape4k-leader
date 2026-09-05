# Redis Cluster 장애 복구 검증의 증거 경계

## 범위와 결정

[#879](https://github.com/bluetape4k/bluetape4k-leader/issues/879)은 정상 lifecycle과 migration 경합 이후의 장애 검증이다.
기준은 `develop@38f7940184d3f1524b8f452dcfce4451a02ff4a1`이다.
생산 코드, 공개 API, 의존성은 변경하지 않는다. 각 시나리오가 digest로 고정한 전용 `RedisClusterServer`를 시작하고 종료한다.
공유 Launcher에는 slot 변경이나 프로세스 정지를 적용하지 않는다.

## 증거를 구분하는 방법

- slot 이동: 목적지 `IMPORTING`, 원본 `MIGRATING`, `MIGRATE` 순서로 실제 키를 옮긴다. redirect를 자동 처리하지 않는 `redis-cli`로 `ASK`를 확인한다. 소유권 확정 뒤 기존 노드에서 `MOVED`를 확인한다.
- 자동 failover: 쓰기와 같은 연결에서 `WAIT`로 복제를 확인한 뒤 primary Redis 프로세스에 `SIGSTOP`을 보낸다. replica의 `role:master`, slot 소유자 변경, caller의 값 조회를 별도로 확인한다. `CLUSTER FAILOVER`나 `TAKEOVER`로 장애 감지를 대신하지 않는다.
- 복구: caller가 유지한 같은 Cluster connection에서 blocking/suspend strategic registry를 검증한다. 모든 확인 뒤에도 연결의 `PING`이 성공해야 한다.
- 정리: `SIGCONT`로 정지한 프로세스를 재개한 뒤 컨테이너를 종료한다. 오류가 발생해도 전용 fixture의 종료를 시도한다.

`ASK` 처리 성공은 topology 변경을 뜻하지 않는다. ASK는 해당 요청만 목적지로 보낸다.
slot 이동 중 다중 키가 양쪽에 나뉘면 `TRYAGAIN`이 가능하므로, 모든 Lua 호출이 무중단으로 성공한다고 보장하지 않는다.
strategic registry 검증은 slot 이동이나 failover가 수렴한 뒤에 수행한다.

## caller 설정과 측정

테스트 caller는 periodic refresh 1초, adaptive refresh trigger 전체 활성화, trigger 간격 100ms,
명령 timeout 1초를 사용한다. 이는 테스트 설정이며 라이브러리의 기본 정책이나 운영 권장값이 아니다.
서버 `cluster-node-timeout` 1초도 격리된 자동 failover 시나리오에만 적용한다.

baseline과 복구 후 각각 10개의 순차 조회를 실행한다. ASK 구간도 10개를 별도로 기록한다.
`convergence`의 attempts/success는 복합 수렴 조건의 polling 횟수이지 Redis 개별 명령 성공률이 아니다.
수렴 시간은 상태 변경 시작부터 클라이언트 topology와 값 검증까지의 단조 시간이다.
짧은 로컬 표본으로 운영 SLA, failover 무손실, retry의 exactly-once 실행을 주장하지 않는다.

## 실행과 결과 확인

```bash
./gradlew :bluetape4k-leader-redis-lettuce:cleanTest \
  :bluetape4k-leader-redis-lettuce:cleanClusterTest \
  :bluetape4k-leader-redis-lettuce:test \
  :bluetape4k-leader-redis-lettuce:clusterTest \
  :bluetape4k-leader-redis-lettuce:detekt \
  --no-build-cache --no-daemon --no-configuration-cache --console=plain
```

로컬 및 hosted runner 모두 Docker 접근이 필요하다. 동일 호스트의 다른 container-backed 테스트와 직렬 실행한다.
`leader-redis-lettuce/build/redis-cluster-diagnostics/`의 `slot-migration.txt`, `automatic-failover.txt`,
기존 runtime provenance와 JUnit XML을 함께 확인한다. 필수 matrix는 신규 두 시나리오를 포함한다.
hosted 실행 성공은 별도 run의 exact head와 실제 artifact를 확인한 뒤에만 기록한다.

## 로컬 실행에서 확인한 결과와 미확인 항목

2026-09-05 첫 신규 matrix 실행은 21개 모두 통과했다. 자동 failover 수렴은 4,964ms,
slot 이동 후 수렴은 411ms였다. 각 baseline/복구 후 조회는 10/10, ASK 구간도 10/10이었다.
이 값은 단일 로컬 실행의 관측값이다.

전체 재실행에서 일반 테스트 416개는 실패·오류·건너뜀 없이 통과했다. Cluster는 20개가 통과하고
slot 이동 시나리오가 `RedisClusterServer.awaitClusterReady`의 30초 준비 대기에서 실패했다.
장애를 주입하기 전의 실패이며, 앞서 확인한 호스트 포트 충돌과 원인이 같다는 증거는 없다.
실패 시 컨테이너 로그 마지막 100줄을 진단 파일에 남기도록 보강한 다음 Cluster 21개가 다시 통과했다.
재실행 성공만으로 준비 단계의 간헐적 실패가 해결됐다고 판단하지 않는다.

검출력 확인에서는 `MIGRATE`와 `SIGSTOP`을 각각 임시로 제거했다. 전자는 실제 값 `preserved`가
반환되어 `ASK` 응답 검증에서 실패했고, 후자는 replica의 `role:master` 검증에서 실패했다.
두 테스트 모두 준비 단계가 아닌 의도한 assertion에서 실패했으며, 임시 변경은 원복했다.

원복 직후의 전체 실행에서도 일반 416개와 신규 장애 테스트 2개는 통과했다.
하지만 기존 `LettuceStrategicRedisClusterTest.startCluster`가 초기화 단계의
`RedisClusterServer.awaitClusterReady`에서 `Connection closed prematurely`로 실패해
기존 19개 본문을 실행하지 못했다. 해당 실행의 matrix 결과는 실패로 보존한다.
fixture 준비 실패의 안정적인 재현과 원인 규명이 남아 있다.

독립 코드 검토는 응답이 없어 중단됐고, architecture 역할의 설치 모델은 요청 모델과 불일치했다.
사용자가 `fallback 은 항상 inline 으로 해`라고 지시했으므로, 이후 실패하거나 모델 조건이 맞지 않는
lane은 인라인으로 대체한다. 이를 독립 검토 완료로 기록하지 않으며 테스트·CI·머지 기준은 유지한다.

인라인 검토에서는 서버 `WAIT` 5초와 caller 명령 timeout 1초의 불일치를 확인했다.
같은 연결에서 서버 대기를 500ms로 제한하고 최대 10초 동안 복제 완료를 확인하도록 변경했다.
앞으로 서버의 blocking command timeout과 caller timeout을 함께 검토한다.
또한 `SIGCONT`와 client resource 종료를 `use`로 감싸 원래 실패가 있으면 정리 실패를 suppressed 예외로 보존한다.
컨테이너 준비 실패의 내부 상태·호스트 포트 진단도 종료 전에 수집한다.

WAIT와 정리 경로 수정 후 직렬로 실행한 최신 XML은 일반 416개(`2026-09-05T11:59:35.459Z`),
Cluster 21개(`2026-09-05T12:03:07.973Z`) 모두 실패·오류·건너뜀 0개다. detekt 오류도 0개다.
하지만 준비 단계의 간헐적 실패가 해결됐다는 증거는 아니다. hosted CI는 아직 미완료다.
이 문서는 merge-ready 판정이 아니다.

## baseline 실패로 확인한 환경 경계

처음에는 Cluster 자체가 잘못된 RESP를 반환했다고 의심했다. 그러나 trace에서
mapped endpoint `localhost:58944` 연결이 `Invalid first byte: 72 (H)`로 실패했고,
컨테이너 종료 후에도 동일 포트의 raw RESP PING이 `HTTP/1.1 400 Bad Request`를 받았다.
`lsof`는 해당 listener의 소유자가 실행 중인 `codex` 프로세스임을 보였다.
인접 `58945`도 같은 프로세스가 소유했고 connection reset이 발생했다.

Docker의 VM 내부 포트 배정만으로 macOS 호스트 포트가 비어 있다고 가정하면 안 된다.
재현 때는 container port mapping, 실제 호스트 listener, raw protocol 응답을 함께 수집한다.
건강한 VM이나 사용자 프로세스를 재시작하지 않는다. 진단 변경을 제거하고 새 mapped port를 사용한 baseline은
19개 테스트, 실패·오류·건너뜀 0개로 통과했다. 단순 재시도 성공이 아니라 충돌한 endpoint를 확인한 결과다.

## 참고 자료

2026-09-05 공식 문서와 사용 중인 Lettuce `7.6.0.RELEASE` source JAR를 확인했다.
Redis 문서는 현재 문서이므로 Redis 3.0부터 존재하는 명령 범위만 사용하고, 고정 이미지의 실제 동작으로 검증한다.

- [CLUSTER SETSLOT](https://redis.io/docs/latest/commands/cluster-setslot/): IMPORTING/MIGRATING, ASK와 다중 키 TRYAGAIN, 소유권 확정 순서.
- [CLUSTER FAILOVER](https://redis.io/docs/latest/commands/cluster-failover/): 수동 failover와 자동 장애 감지의 구분. OK는 승격 완료 증거가 아니다.
