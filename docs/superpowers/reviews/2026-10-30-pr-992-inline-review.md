# PR #992 인라인 fallback 코드 리뷰

## 범위와 기준

- PR: [#992](https://github.com/bluetape4k/bluetape4k-leader/pull/992)
- 기준 브랜치: `develop` (`6f6697b8a10290778ab8e16a5a6441b424f3e6e3`)
- 검토 시작 head: `feature/code-polishing-2026-10-17` (`f36a4b478e870a06f767896dbbb441f82e6e927e`)
- 후보 범위: PR diff 633개 파일(+5,537/-4,315), 공백 변경을 제외하면 602개 파일(+4,089/-2,867). 아래 수정 8개 파일도 함께 확인했다.
- 목적: 코드 서식 정리와 연관된 API·동시성·호출 경로·로그 노출 회귀를 찾아 수정하고, P0/P1 여부를 판단한다.

## 검토 방법과 제한

공백 변경을 제외한 전체 diff와 변경 파일 목록을 기준으로 audit export의 공개 API, listener 통지, lease 연장, backend deadline, 로그, Spring scheduling registry, 테스트 호출 경로를 살폈다. Core·Spring·Consul·etcd·DynamoDB·Kubernetes 등에서 위험도가 높은 변경을 표본 검토했으며, 602개 의미 변경 파일을 모두 한 줄씩 대조한 검토는 아니다.

독립 `code-reviewer` 검토는 사용량 제한으로 결과를 받지 못했다. 별도로 완료된 제한적 architecture review는 원래 head에서 WATCH를 내고 세 지점을 지적했다. 이 문서는 그 지적과 추가 로그·테스트 발견을 반영한 **inline fallback review**이며 독립 검토 결과로 간주하지 않는다. 원래 지적은 현재 후보에서 수정했고 해당 diff와 테스트를 다시 확인했다.

## 발견 사항과 조치

| 등급 | 위치 | 위험과 조치 |
|---|---|---|
| P2 | `leader-core/.../LeaderElectionListener.kt:205-213` | `ConcurrentLinkedQueue` 순회는 통지 중 listener 변경이 현재 listener 목록에 미치는 영향을 기존과 다르게 만들었다. `CopyOnWriteArrayList`와 `addIfAbsent`를 복원하고, 현재 dispatch가 시작 시점의 listener 목록을 유지하는 동시성 회귀 테스트를 추가했다. |
| P2 | `leader-core/.../utils/LongSupport.kt:3`, `leader-etcd/.../EtcdAcquisitionDeadline.kt:34-36` | PR에서 추가한 `Long.safePlus`가 의도하지 않은 public Kotlin API가 됐다. Core 함수는 `internal`로 좁히고, 유일한 교차 모듈 사용처인 etcd에는 동일 계산을 private helper로 두었다. |
| P2 | `leader-dynamodb/.../DynamoDbLockExtendDelegate.kt:37-45,74-84` | `lock=$lock`가 `AcquiredLock`의 기본 `toString()`을 호출해 owner/node 식별 정보를 로그에 포함할 수 있었다. 기존 key-only 진단 로그로 되돌렸다. |
| P2 | `leader-k8s/.../KubernetesLeaseLock.kt:60-62` | 획득 로그가 Lease holder 식별에 쓰이는 `ownerToken`을 출력했다. 로그에서 토큰을 제거했다. |
| P2 | `leader-spring-boot/.../LeaderScheduledPolicyRegistry.kt:39-56` | 임의 사용자 bean의 `target.toString()`과 policy 전체를 debug 로그에 출력했다. 진단에 필요하지 않은 출력을 제거했다. |
| P2 | `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderElectionEventExportSubscriptionTest.kt:45-60` | 클래스 호출식은 companion factory 대신 같은 인자를 받는 공개 secondary constructor를 호출할 수 있어 factory 동작을 검증하지 못했다. 테스트에서 `Companion.invoke`를 명시해 mutable attributes 복사 동작을 해당 factory 경로에서 확인한다. |
| P3 | `leader-core/.../LeaderLeaseAutoExtender.kt:519-522` | lease 연장 성공 tick마다 INFO 로그를 남기면 주기적 작업에서 로그량이 커진다. 성공 로그를 제거하고 실패·재시도 로그는 유지했다. |

모든 발견을 후보에서 수정했다. 수정된 이 범위에서 CRITICAL/HIGH은 0건이며 남은 차단 발견은 없다.

## 검증 근거

- RED: listener 동시성 회귀 테스트가 `ConcurrentLinkedQueue` 구현에서 기대한 `first, stable, removed` 대신 `first, stable, added`를 관찰했다.
- GREEN: `LeaderElectionListenerTest` 31/31 통과.
- `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-etcd:test detekt checkBinaryCompatibility`: 성공. Core 1,093건, etcd 186건 통과; ABI inventory `artifacts=16 ignored=40 unknown=0` 및 `no unclassified public incompatibilities`.
- `./gradlew :bluetape4k-leader-dynamodb:test --tests 'io.bluetape4k.leader.dynamodb.internal.DynamoDbLockClientOwnerIdTest' :bluetape4k-leader-k8s:test --tests 'io.bluetape4k.leader.k8s.internal.KubernetesLeaseLockTest' :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistryTest' detekt checkBinaryCompatibility`: `BUILD SUCCESSFUL`; 대상 테스트 10건 통과.
- `git diff --check`: 통과.
- 기존 PR head `f36a4b4`의 hosted CI는 46/46 성공했지만, 이 결과는 현재 후보를 포함하지 않는다. 최종 hosted CI는 새 head push 뒤 별도로 확인해야 한다.

## 판정

**Inline verdict: PASS; P0=0, P1=0.**

이 PASS는 위 범위의 fallback 리뷰 판정이다. 전체 파일 전수 검토나 독립 `code-reviewer` attestation을 뜻하지 않는다. 최종 delivery gate에는 새 head의 hosted CI와 live review/thread 재확인이 필요하다.

## 작성·검토 DoD

| 항목 | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | 독자·목적·기준 ref·PR·범위·검토 제한을 이 문서에 명시했다. |
| SPW-02 | PASS | 범위, 방법, 파일·라인별 발견과 조치, 검증, 제한, 판정을 포함했다. |
| SPW-03 | PASS | 한국어 기술 문장으로 작성하고 코드 토큰·SHA·명령은 그대로 보존했다. |
| SPW-04 | PASS | 각 발견을 현재 코드와 테스트 결과에 대조했다. |
| SPW-05 | PASS | 최종 Markdown을 다시 읽고 표와 링크를 확인했다. |
| KO-01–KO-06 | PASS | 사실·수치·불확실성을 보존하고 과장·번역투·용어 불일치·근거 없는 주장을 점검했다. |
| KO-07 | PASS | `audit-korean-terms.mjs`에서 두 문서 모두 오류와 경고가 없었다. |
