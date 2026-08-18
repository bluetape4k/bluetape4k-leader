# Issue #559 OBS-02 PR3 Micrometer/Spring 통합 리뷰

## 범위와 기준

- Issue: #559 `feat(leader-core): lease-extension observation hook 추가`
- Epic/train: #699 / `OBS-02`
- PR: PR3 예정, `feat/obs-02-micrometer-spring`
- base: `develop` exact `22058ddefd16c058444b01142315fe3d63f274a1`
- review head (runtime source): `913b72d503cb555d51910181ca61896804f2c21b` (`Spring 등록 수명주기 callback 증거를 명시한다`); 이후 review artifact commit은 문서만 추가한다.
- 선행: PR #722, #723은 `develop`에 병합됨
- 검토 기준: 승인 spec/plan과 2026-08-18 lifecycle amendment, Type-A 7-tier 관점

## 통합 결론

P0 0건, P1 0건, P2 1건, P3 0건. 최종 verdict는 `WATCH`이며 PR 생성은 가능하다. 남은 P2는 구현 결함이 아니라 증거 범위의 한계다. manager identity-map 제거, acquire/last-close 경합의 실제 callback exactly-once, 최종 close 이후 callback 부재, context handle close는 검증했지만, accepted callback의 close 이후 실행과 weak-reference 회귀를 별도 계측하지 않았다. core dispatcher의 accepted-task-after-close 정책은 기존 계약상 허용되며, 다음 lifecycle 확장 시 deterministic seam을 추가한다.

## 7-tier 관점

| 관점 | 검토 근거 | 결과 |
|---|---|---|
| 정확성·계약 | `MicrometerObservationLeaderLeaseExtensionObserver`의 네 outcome mapping, NOOP fast path, `source/execution/outcome/result` bounded tags, Spring NOOP/normal/Boot post-processor 경계 테스트 | PASS |
| 동시성·수명주기 | `IdentityHashMap` + 단일 `ReentrantLock`, idempotent handle, parent/child ref-count, primary registry, 32회 acquire-close 교차의 callback exactly-once/최종 close 이후 no-callback, 병렬 acquire/last-close 및 executor termination 검증 | PASS |
| API·ABI | exact 2-argument source constructor, synthetic default constructor 분리, public tag constant 부재를 reflection/`javap`로 확인 | PASS |
| 성능·안정성 | registry NOOP 조기 반환, context 초기화 시점의 등록, callback 경로에 observer 중복 없음, Micrometer 82/82·Spring 457/457 PASS | PASS |
| 보안·개인정보 | lock name/leader ID 기본 비노출, opt-in high-cardinality redaction, exception detail opt-in, raw value low-cardinality tag 금지 | PASS |
| 운영·통합 | `@Primary` 선택, parent registry provider, tracing/observability property, Boot ObservationRegistryPostProcessor, AOT 6/6, module `check` | PASS |
| 유지보수·문서 | Kotlin 금지 패턴(`!!`, `runCatching`, monitor), spec/plan lifecycle amendment, lesson, Lore commit, `git diff --check` | PASS |

## 독립 리뷰 lane

- implementation/API lane: `a1fdf4ba` exact head에서 P0/P1 없음 확인. 후속 `913b72d5`는 테스트/spec/lesson만 변경하며 inline delta review에서 production ABI·동시성·예외 경계를 변경하지 않음을 확인했다. 이전 compile/synchronized 및 lifecycle contract 지적은 amendment와 구현 보강으로 해소했다.
- architecture/stability lane: `a1fdf4ba`에서 조기 registry 생성 P1은 해소됐다. 후속 `913b72d5`의 acquire-close callback exactly-once 및 stale acceptance 보정은 inline으로 exact diff를 확인했다. P0/P1 없음, P2는 accepted callback/weak-reference 증거 공백으로 한정했다.
- Kotlin/ABI lane: `ReentrantLock`, SmartInitializingSingleton descriptor, exact constructor/private tag surface 및 anti-pattern scan을 확인했다.
- 추가 7-tier 관점(성능, 보안, 운영, 사용자 영향): 본 리뷰에서 변경 파일과 fresh Gradle 결과를 기준으로 inline 확인했다. 후속 commit은 테스트 assertion과 문서 계약만 변경해 runtime surface·성능·보안·운영 ownership 결과를 바꾸지 않는다.
- human review: N/A (1인 개발자 지시)
- LSP diagnostics: N/A (실행 파일 미제공, compile/test/detekt로 대체)

## 검증 증거

- RED: observer 구현 전 unresolved reference 실패를 확인한 뒤 GREEN으로 전환했다.
- `:bluetape4k-leader-micrometer:test --rerun-tasks`: 82/82 PASS.
- `:bluetape4k-leader-spring-boot:test --rerun-tasks`: 457/457 PASS.
- Spring targeted: auto-configuration 14/14, registration manager 7/7 PASS; registration manager 교차 테스트는 32회 callback exactly-once와 최종 close 이후 callback 0건을 검증한다.
- Spring AOT: 6/6 PASS.
- `:bluetape4k-leader-micrometer:detekt`, `:bluetape4k-leader-spring-boot:detekt`: PASS.
- 두 모듈 `check`: PASS.
- `javap`: public observer constructor/options/onExtension만 노출되고 tag 상수는 public field가 아니다.
- changed-scope forbidden scan: `!!` 0, `runCatching` 0, `synchronized` 0, broad `Throwable`/`Exception` catch 0.
- `git diff --check`: PASS.

## 변경과 후속

- Micrometer public observer와 5개 계약 테스트를 추가했다.
- Spring shared-registration manager, NOOP candidate condition, SmartInitializingSingleton coordinator, 21개 integration/lifecycle 테스트를 추가했다.
- Spring test compile을 위해 `spring-boot-micrometer-observation`은 test-only dependency로만 추가했다.
- README/manual 및 hosted CI는 PR4/PR 게시 후 검증한다.
- 남은 P2: accepted callback close-after 실행과 weak-reference 직접 회귀 계측. 현재 manager state/ref-count/last-close 및 callback 경계는 PASS이며, core의 accepted-task semantics를 바꾸지 않는다.

## 최종 판단

`PENDING` — PR3 runtime source는 exact head `913b72d503cb555d51910181ca61896804f2c21b`에서 검토됐고, 이후 branch tip에는 이 review artifact 문서만 추가됐다. remote push·PR metadata/CI read-back·fresh exact-head merge approval은 아직 남아 있다. merge, develop sync, worktree cleanup은 fresh approval 이후 별도 단계로 수행한다.
