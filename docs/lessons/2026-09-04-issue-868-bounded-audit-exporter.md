# Issue #868: BoundedLeaderAuditExporter flaky 경로

## 맥락

Milestone `1.1.0`의 [Issue #868](https://github.com/bluetape4k/bluetape4k-leader/issues/868)은 `leader-core` 전체 테스트에서 `BoundedLeaderAuditExporter`의 rejection counter 관측값과 observer 재등록 검증이 간헐적으로 실패하는 문제를 다룬다. 현재 `develop` exact head `e50dd883bb2e08961c1dd48c698d253605ce512e`에서 `leader-core` 전체 테스트를 새로 실행한 결과, 기존 관측값 불일치와 observer 수명주기 실패 외에 close 중 concurrent key-set 순회 예외와 worker handoff 재시작 손실도 재현했다.

## 결정

- `finishWork()`는 terminal observation counter를 `admitted` permit 해제보다 먼저 갱신한다. 따라서 `admitted == 0`을 읽은 관측값이 `EXECUTOR_REJECTED`, `SCHEDULER_REJECTED`, `CANCELLED`, `TERMINAL_FAILURE` counter를 뒤늦게 보는 창을 만들지 않는다.
- `active`는 동시 축소가 가능한 `ConcurrentHashMap` key-set이다. `active.toList()`의 크기 추정과 iterator가 경합하면 `NoSuchElementException`이 발생하므로 close에서는 weakly-consistent `forEach`를 사용하고 `terminalized` CAS로 중복 취소를 막는다.
- worker handoff가 실행 중일 때 새 dispatch를 중복 생성하지 않고, 기존 handoff가 `IDLE`로 돌아오는 시점에 큐를 다시 확인한다. 이 재확인으로 inline executor가 반환되는 순간 queued work를 놓치지 않는다.
- observer 재등록 테스트는 callback이 막힌 동안 두 번째 work가 실제로 종료됐는지 기다린 뒤 queued diagnostics 항목을 확인한다. bounded admission 경합으로 발생한 정상적인 `DROPPED_QUEUE_FULL`을 observer 수명주기 실패로 오판하지 않도록 장벽을 명시한다.

## 결과

수정 범위는 `leader-core` 내부 구현과 해당 회귀 테스트로 제한했다. 공개 API나 ABI surface는 변경하지 않았다. observer close/replacement 테스트는 callback 큐가 남아 있는 상태와 replacement callback 시작을 각각 검증한다.

## 검증

다음 검증을 수행했다.

```text
BoundedLeaderAuditExporterTest: 21 tests, targeted run PASS
serial stress: 5/5 PASS; parallel stress: 5/5 PASS
executor rejection recovery: 20/20 isolated repetitions PASS
leader-core full test: 1,018 tests, repeated runs 3/3 PASS
detekt: PASS; checkBinaryCompatibility: PASS (`No changes` for all published artifacts)
git diff --check: PASS; Korean terminology audit: PASS (0 findings)
```

정확한 CI exact-head run, 병합 후 downstream 모듈, publication은 이 로컬 수정 범위에서 실행하지 않았다. `gno update`는 실행했지만 등록 collection이 isolated `.worktrees`를 제외하므로 이 lesson은 기존 `bluetape4k-docs` index에 반영되지 않았다. 대신 임시 collection에서 index와 BM25 검색을 확인하고 embedding 257개 chunk를 생성한 뒤 collection을 제거해 격리 문서의 검색 가능성과 설정 정리를 검증했다.

## 놓친 점

기존 테스트는 `admitted` 해제와 observer callback 큐의 진행을 독립적으로 기다리지 않았다. 테스트가 정상적인 non-blocking admission drop과 실제 observer worker 결함을 같은 실패로 보고할 수 있었다. 또한 concurrent collection의 `toList()`가 원자적 순회처럼 보인다는 가정이 close 경로에 남아 있었다.

## 앞으로의 guard

- concurrent collection을 public lifecycle 경계에서 목록으로 materialize하지 말고 weakly-consistent iteration 또는 명시적인 동기화된 관측 목록을 사용한다.
- 공개 관측값에서 한 필드의 해제가 다른 counter의 갱신을 앞서지 않도록 terminal counter와 permit release 순서를 회귀 테스트로 고정한다.
- observer close/re-register 테스트는 work admission, diagnostics queue, callback running 상태를 각각 장벽으로 고정하고 단독·반복·전체 모듈 실행을 함께 기록한다.
