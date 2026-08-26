# Issue #786 교훈 — Redis 전략 결과 카운터 원자 갱신

**영향 모듈**: `leader-redis-lettuce`, `leader-redis-redisson`

## 맥락

전략적 후보의 `SUCCESS`·`FAILURE` 결과를 기록할 때 후보 문자열을 읽고,
클라이언트에서 카운터를 증가시킨 뒤 다시 쓰는 read-modify-write가 사용되고
있었습니다. 서로 다른 연결에서 결과 갱신이 동시에 실행되면 한쪽 증가분이 다른
쪽 쓰기에 덮어써져 `successRate`와 선출 순서가 실제 실행 결과와 달라졌습니다.

## 결정

- Lettuce blocking·suspend 경로는 `RedisScriptRunner`와 Redis Lua 스크립트를
  사용해 조회, 카운터 증가, `SET XX KEEPTTL`을 Redis 서버의 한 원자 실행으로
  묶었습니다.
- Redisson blocking·suspend 경로는 기존 `RMapCache.computeIfPresent`를 사용해
  per-entry lock과 server-side `fastPutIfExists`를 재사용했습니다. 이 경로는
  잔여 TTL을 별도로 읽어 다시 쓰지 않으므로 만료 경쟁과 zombie key 생성을
  피합니다.
- 손상된 후보 직렬화는 기존 codec 예외를 유지하고, 없는 키는 갱신하지 않으며,
  suspend async 명령 객체는 `lazy`로 초기화해 조회 전용 mock·호출자의 연결
  초기화 계약을 바꾸지 않았습니다.

## 결과

single/group 및 blocking/suspend 네 조합 모두에서 동시 결과 증가가 보존되고,
`successRate`와 `SuccessRateScorer` 기반 winner가 일관되게 계산됩니다. 후보 TTL,
만료 후 zombie 방지, key namespace, `CancellationException` 전파 계약은
변경하지 않았습니다.

## 검증

- RED: 독립 Lettuce 연결 8개와 Redisson elector 8개, 16 workers × 20 rounds에서
  기대 카운터 160 대신 Lettuce 11–16, Redisson 11–14 수준으로 유실되는 회귀를
  재현했습니다.
- GREEN: Lettuce strategic single/group blocking/suspend 44·11건과 Redisson
  34·11건 focused suite가 모두 통과했습니다.
- 전체 모듈: Lettuce `294/294`, Redisson `280/280`, failures=0, skipped=0.
- 정적 분석: 두 변경 모듈 `detekt` 통과.
- `git diff --check`와 exact-head hosted CI는 PR 단계에서 다시 확인합니다.

## 향후 지침

후보 결과처럼 문자열 또는 map entry 안의 카운터를 갱신하는 Redis 코드는
클라이언트 read-modify-write를 새로 만들지 말고 backend-native 원자 연산을
사용하세요. 동시성 회귀는 공유 connection에 의존하지 말고 독립 연결과 실제
winner·TTL 관찰까지 포함해 고정합니다. suspend 경로에서 새 backend command
객체를 추가할 때는 생성 시점의 mock·호출자 초기화 부작용이 없는지 확인하고
필요하면 지연 초기화를 사용합니다.
