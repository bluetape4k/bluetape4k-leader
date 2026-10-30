# Kotlin companion factory 경로를 테스트에서 고정한다

## 맥락

PR #992에서 `LeaderElectionEventExportSubscription`의 companion `operator fun invoke`에 전달한 `attributes`를 구독 생성 시 복사하도록 보강했다. 이 클래스에는 같은 형태의 인자로 호출할 수 있는 공개 secondary constructor도 있다.

## 발견

테스트에서 `LeaderElectionEventExportSubscription(publisher, scope, exporter, attributes, sanitizer)`처럼 클래스 이름으로 호출하면 companion factory가 아니라 공개 secondary constructor가 선택될 수 있다. 두 경로가 모두 유효한 subscription을 만들기 때문에, constructor 경로만 통과하는 테스트는 companion factory의 방어적 복사를 검증하지 못했다.

테스트가 `LeaderElectionEventExportSubscription.Companion.invoke(...)`를 직접 호출하도록 바꾸자 factory 경로에서 입력 map이 복사되는지 확인할 수 있었다. map을 변경한 뒤 publisher event를 발행하고 원래 값이 export되는지 검사한다.

## 결정과 결과

호출 경로 자체가 계약인 Kotlin companion factory는 테스트에서 `Companion.invoke`를 명시한다. 비슷한 클래스 호출식이나 다른 overload가 같은 결과를 반환하는지에 기대지 않고, 검증 대상 factory를 직접 선택한다.

이 사례에서는 명시적 companion 호출로 기존 동작의 실패를 재현하고, factory에서 `attributes.toMap()`을 적용한 뒤 통과를 확인했다. 현재 dispatch가 시작 시점의 listener 목록을 유지하는지도 별도 동시성 회귀 테스트로 RED/GREEN 검증했다.

## 검증

- `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/LeaderElectionEventExportSubscriptionTest.kt`: explicit `Companion.invoke` 경로에서 mutable map의 변경이 export 결과에 반영되지 않는지 확인.
- listener regression RED: callback 중 listener를 추가·제거했을 때 현재 통지 결과가 기대한 시작 시점 목록과 달랐다.
- listener regression GREEN: `CopyOnWriteArrayList` 복원 뒤 `LeaderElectionListenerTest` 31/31 통과.
- 전체 후보의 대상 테스트, Detekt, ABI gate와 hosted CI는 PR 리뷰 보고서 및 PR의 `## DoD Status`에 각각 기록한다.

## 향후 지침

Kotlin 테스트가 companion factory, 생성자, 정적 bridge 중 특정 진입점을 검증해야 하면 그 경로를 호출 코드에 명시한다. 호출 표기법이 비슷한 overload가 있으면 대상 메서드가 실제 호출되는지 소스 또는 bytecode로 확인하고, 다른 경로로는 통과하지 않는 관찰 가능한 assertion을 둔다. 동시성 컬렉션을 교체할 때는 자료구조 이름만 비교하지 말고 순회 중 등록·해제의 목록 복사 의미를 기존 동작과 회귀 테스트로 고정한다.

## 작성·검토 DoD

| 항목 | 상태 | 근거 |
|---|---|---|
| SPW-01 | PASS | 맥락, 대상 factory, 잘못된 가정, RED/GREEN 근거를 현재 소스와 테스트에서 확인했다. |
| SPW-02 | PASS | 맥락·발견·결정·결과·검증·향후 지침을 포함했다. |
| SPW-03 | PASS | 한국어 기술 문장으로 작성하고 클래스·메서드·명령 이름을 보존했다. |
| SPW-04 | PASS | 호출 경로와 map 복사 동작을 현재 구현 및 테스트와 대조했다. |
| SPW-05 | PASS | 최종 Markdown을 다시 읽고 heading·목록·인라인 코드를 확인했다. |
| KO-01–KO-06 | PASS | 기술 사실·호출 이름·검증 범위·미검증 항목을 보존하고 문장을 점검했다. |
| KO-07 | PASS | `audit-korean-terms.mjs`에서 두 문서 모두 오류와 경고가 없었다. |
