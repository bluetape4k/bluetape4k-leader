# Issue #671 공통 leader contract fixture 7-tier inline review

## 범위와 판정

- 대상: etcd·Consul·DynamoDB·Kubernetes Lease의 test-only contract fixture,
  `leader-core` testFixtures 입력 정규화, capability matrix/validator,
  `ci-contract` fan-out, 설계·실행 계획 문서
- 제외: production leader 알고리즘, public API, dependency, publication
- 실행 방식: 사용자의 독립 review lane 재시도 후 inline 지시에 따라 현재
  작업 세션에서 fresh diff와 실행 결과를 직접 검토
- 최종 판정: `CLEAR` — HIGH/CRITICAL 미해결 finding 없음

## 7-tier 결과

| Tier | 검토 내용 | 결과 |
|---|---|---|
| 1. 요구사항·수용 기준 | 네 backend의 지원 execution model, explicit N/A, virtual/executor direct, CI 분리 실행 | 통과 |
| 2. API·contract 정확성 | 모든 supported subclass의 abstract base와 public constructor, etcd 실제 N/A overload 근거 | 통과 |
| 3. Kotlin 패턴 | `@TestInstance(PER_CLASS)`, Kluent/bluetape assertions, 금지된 JUnit assertion·`!!`·blocking coroutine 우회 없음 | 통과 |
| 4. backend lifecycle·동시성 | DynamoDB shared Local table/client, K3s class별 client close, executor/virtual 재획득 확인 | 통과 |
| 5. CI·matrix drift | 파일·base·N/A reason·backend module·`settings.gradle.kts`·Gradle task를 static/self-test로 확인 | 통과 |
| 6. 실패·취소·lease 경로 | 기존 sync/suspend LockExtender contract와 direct overload의 release/reacquire, K8s DNS-1123 입력 | 통과 |
| 7. 유지보수·문서 경계 | protocol test와 contract test 분리, README/#672와 production 변경 없음, 한국어 spec/plan/review | 통과 |

## Review findings

### 수정 완료

1. matrix validator가 required contract의 파일 안 토큰만 확인하던 drift
   경로를 보완해 행의 `base`가 기대 abstract class와 정확히 일치하도록
   했다. 잘못된 base 회귀 테스트를 추가했다.
2. `N/A` 행에 실수로 `test` 경로가 남는 경우를 validator가 거부하도록
   했다. empty reason과 함께 회귀 테스트에 포함했다.
3. K8s Lease DNS-1123 제약으로 공통 fixture의 Base58 대문자 lock 이름이
   실패하던 문제를 확인했다. `leader-core` testFixtures의 랜덤 lock
   suffix만 소문자로 정규화했고 production 이름 정책은 유지했다.
4. 새 etcd executor direct 테스트에 동일 lock 재획득 assertion을 추가해
   release 의미를 명시했다.

### 비차단 관찰

- etcd 전체 suite에서 기존 `EtcdLeaderElectionEventPublisherIntegrationTest`
  queued contender 테스트가 한 번 10초 timeout을 냈다. 새 contract 테스트는
  모두 통과했고, 해당 테스트 단독 재실행(4건) 및 etcd 전체 재실행(134건)은
  성공했다. 재현되지 않은 기존 suite timing 현상으로 이번 변경의 blocker로
  분류하지 않았다.

## Fresh verification evidence

```text
leader-core:test                         SUCCESS: Executed 715 tests
leader-etcd:test                          SUCCESS: Executed 134 tests
leader-consul:test                        SUCCESS: Executed 146 tests
leader-dynamodb:test                      SUCCESS: Executed 110 tests
leader-k8s:test                           SUCCESS: Executed 13 tests
leader-k8s:k8sTest                        SUCCESS: Executed 101 tests
matrix unittest                           6 tests OK
matrix --self-test / --static             OK
CI fan-out --self-test / --static         OK
Python compile and git diff --check       OK
```

모든 변경은 다섯 개 Lore commit으로 분리했고 worktree는 clean이다. PR 생성,
push, merge, develop 동기화는 별도 delivery gate로 남긴다.
