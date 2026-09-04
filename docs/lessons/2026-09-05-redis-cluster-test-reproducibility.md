# Redis Cluster fixture 고정과 migration 회귀 테스트 정리

## 범위와 결정

[PR #875](https://github.com/bluetape4k/bluetape4k-leader/pull/875)의 후속 개선 중
[#876](https://github.com/bluetape4k/bluetape4k-leader/issues/876)과
[#878](https://github.com/bluetape4k/bluetape4k-leader/issues/878)을 구현했다.
기준은 `develop`의 `7c99875c7d5ec2bdec99e635dae0f88eceb3edcc`이며,
프로덕션 소스와 공개 API는 변경하지 않았다.

- `redis-cluster-image.txt`를 이미지 참조의 단일 원본으로 사용한다.
  Gradle은 mutable tag를 거부하고, 테스트는 기존 `RedisClusterServer` factory에 digest를 전달한다.
- 테스트가 서버 시작과 종료를 소유한다. 시작 실패 시에도 정리를 시도하고,
  정리 실패는 원래 예외의 suppressed exception으로 보존한다.
- 실행 컨테이너의 image ID를 조회한 뒤 요청 이미지의 ID 및 실제 `RepoDigests`와 대조한다.
  요청 문자열만 기록하는 것으로 실제 실행 이미지 검증을 대신하지 않는다.
- migration 회귀 테스트는 private constructor와 cleanup 메서드에 접근하지 않는다.
  실제 `listCandidates()` 호출 중 첫 양수 `PTTL` 조회 직후 `PERSIST`를 완료하고,
  cleanup이 관측하는 `-1`이 만료로 처리되지 않는지 검증한다.

## 검증 결과

다음 명령으로 일반 테스트 352개와 Cluster 테스트 17개가 모두 통과했다.
같은 실행의 detekt도 성공했다. Cluster 실행 기록은 고정 digest 일치,
`cluster_state=ok`, endpoint 6개를 확인했다.

```bash
./gradlew :bluetape4k-leader-redis-lettuce:test \
  :bluetape4k-leader-redis-lettuce:clusterTest \
  :bluetape4k-leader-redis-lettuce:detekt \
  --no-daemon --no-configuration-cache --console=plain
```

- 이미지 참조를 임시로 `tommy351/redis-cluster:6.2`로 변경하자 Gradle이
  `Redis Cluster fixture requires an immutable SHA-256 image reference`로 거부했다.
  검증 후 고정 digest로 복원했다.
- 양쪽 registry의 만료 판정을 임시로 `sourceTtlAfter == 0L`에서 `<= 0L`로 변경하자
  blocking·suspend 회귀 테스트 두 개가 실패했다. 프로덕션 코드를 복원한 후 위 전체 검증을 통과했다.

## 놓치기 쉬운 점과 재발 방지

첫 테스트 연결은 async 명령을 감쌌지만 suspend 경계가 실행되지 않아 테스트가 실패했다.
사용 중인 Lettuce `7.6.0.RELEASE` 소스에서 `coroutines()`가 `reactive()`를 감싼다는 것을 확인하고,
reactive `PTTL`과 `PERSIST`를 순서대로 연결했다. 테스트 대역은 API 이름이 아니라
실제 호출 경로에 설치하고, source TTL이 정말 `-1`로 변경됐는지도 단언한다.

향후 이미지 갱신 때는 resource의 digest와 실제 실행 image ID를 함께 확인한다.
회귀 테스트 리팩터링 때는 기존 결함을 재현하는 임시 변이로 검출력을 확인한다.

## PR 직전 재검증

캐시를 비활성화한 fresh 실행에서 기존 `LettuceStrategicGroupToxiproxyCancellationTest`가
한 번 실패했다. 테스트 본문 진입 전 `ghcr.io/shopify/toxiproxy:2.9.0`의
`http://localhost:57287/version` 준비 확인이 60초를 초과했다.
컨테이너 로그에는 `0.0.0.0:8474` 서버 시작이 기록됐고, 같은 실행의 다음 컨테이너는
0.326초에 시작했다. 해당 테스트와 fixture는 이번 변경에 포함되지 않으며,
Cluster fixture가 시작되기 전에 발생한 실패다.

코드나 VM 설정을 변경하지 않고 대상 테스트 2개를 재실행해 통과한 뒤,
일반 352개·Cluster 17개와 detekt를 다시 검증했다. 모두 통과했지만
호스트 포트 접근이 지연된 근본 원인은 확정하지 않았다. 재발 시 `/version`의
호스트·컨테이너 양쪽 응답과 Docker 포트 매핑을 수집하며, 재시도 성공만으로 해결됐다고 기록하지 않는다.

## 남은 범위

[#877](https://github.com/bluetape4k/bluetape4k-leader/issues/877)의 추가 migration 경합·TTL 행렬과
[#879](https://github.com/bluetape4k/bluetape4k-leader/issues/879)의 failover·MOVED/ASK 수렴 측정은
별도 후속 작업이다. 이번 로컬 검증은 hosted CI나 해당 장애 시나리오의 통과 근거가 아니다.
