# #994 설계·계획 inline 재검토

## 범위와 근거

이 보고서는 기존 spec lane이 probe 기한 뒤에도 응답하지 않아 main session이 직접 수행한 bounded fallback이다. 검토 범위는 [설계 명세](../specs/2026-10-30-issue-994-inline-backend-facades-design.md), [구현 계획](../plans/2026-10-30-issue-994-inline-backend-facades-plan.md), 그리고 계획에 고정한 기준 SHA `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f`, ABI 기준 `6f6697b8a10290778ab8e16a5a6441b424f3e6e3`, 역사적 DynamoDB consumer 기준 `a6e17f71cdfde18b6d7e8575c7db26385902e9b4`다. source 수정이나 테스트 실행은 포함하지 않는다.

## 검토 결과

### ABI 및 Kotlin source 계약

계획은 기준 source에서 37개 target 선언을 고정하고, facade 36개만 일반 함수로 바꾸며 `etcdClientOf`를 inline으로 남긴다. 같은 `ClientSupport.kt`에 있는 비대상 `etcdClient`도 inventory와 최종 diff에서 불변으로 확인한다. DynamoDB hidden shim은 `@JvmName`, `@Deprecated(HIDDEN)`, 기본 인자와 기존 위임을 보존하고, method descriptor·access·`Signature`·관련 annotation을 비교하도록 검증 경계를 분리했다. Kotlin source 재컴파일 계약과 기존 JVM ABI 증거를 별도 acceptance 기준으로 다룬다.

### 경계와 실행 의미

계획의 source 변경 범위는 일곱 Kotlin 파일이며, 새 dependency·module·workflow·사용자 호출 문법 변경을 포함하지 않는다. 함수 본문은 기존 callback 횟수, 반환·예외, async/suspend dispatch 순서를 보존하고 중복 adapter 제거만 허용한다. `etcdClientOf`의 non-local return은 수정하지 않는 factory를 호출하는 block-body consumer fixture로 보호한다.

### 검증 계획

구현 전에 34개 API의 explicit/default 인자 consumer 68개를 추가하고, 과거 DynamoDB hidden shim consumer 네 개를 기준 commit에서 컴파일한다. 변경 뒤 `INVOKESTATIC` owner/name/descriptor, main/`$default`, source inventory, JVM method attributes, backend 여섯 모듈의 기능·취소·자원 정리 테스트를 확인한다. Testcontainers는 모듈별로 순차 실행하며 환경 오류와 제품 assertion을 분리하고 skip을 성공으로 세지 않는다.

### 한계

이 검토는 명세와 실행 계획의 구현 전 검증이다. facade 수정, 테스트 결과, 최종 ABI 비교, hosted CI는 아직 증거가 없으며 구현·검증 gate에 남는다. 별도 benchmark 기준이 없으므로 성능 개선 수치를 주장하지 않는다.

## 판정

**Inline verdict: PASS; P0=0, P1=0.** 승인된 범위와 수용 기준은 내부적으로 일치한다. 미응답 lane의 결과를 승계하거나 독립 검토로 표시하지 않았으며, 해당 lane은 이 exact-head 보고서로 inline fallback 처리한다.
