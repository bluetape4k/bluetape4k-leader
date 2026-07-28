# 6계층 코드 검토 - Issue #345 Consul 슬라이스 1

범위: `leader-consul` 계약 뼈대, 저장소 연결, README 항목, CI 및 야간 작업 흐름 연결.

기본 제약 조건: 이 슬라이스는 PR #351에 스택되어 있으며 상위 분기의 `bluetape4k-bom` 1.9.0을 사용합니다.

## 계층 1. 사실적 정확성

- PASS: 모듈이 `settings.gradle.kts` 및 BOM 제약 조건에 등록됩니다.
- 통과: 공개 계약 개체는 런타임 코드가 잘못된 세션을 생성하기 전에 Consul TTL 최소값과 최대값을 검증합니다.
- PASS: KV 잠금 이름은 공유 `validateLockName` 계약을 재사용하고 Consul HTTP 경로에 대한 최종 잠금 이름 세그먼트를 인코딩합니다.

조사 결과: 없음 P0/P1.

## Tier 2. 수석 엔지니어링/유지보수성

- 통과: 공용 API는 타사 Consul 클라이언트를 노출하는 대신 `ConsulEndpoint` 및 `ConsulLeaderElectionOptions`를 소유합니다.
- 통과: 내부 `ConsulLockClient` 경계는 향후 Java HTTP 런타임 구현을 교체 가능하게 유지합니다.
- 통과: 슬라이스는 좁은 상태로 유지됩니다. 단일 리더 런타임 슬라이스 전에는 그룹 선택, Spring, Ktor 또는 런타임 선택기 계약이 도입되지 않습니다.

조사 결과: 없음 P0/P1.

## 계층 3. 보안

- 수정됨: 이제 `ConsulEndpoint`가 URI 사용자 정보 자격 증명을 거부합니다. ACL 자료는 `aclToken`를 사용해야 하며 `toString()`는 해당 토큰을 마스크합니다.
- PASS: 토큰이 실수로 끝점 URL을 통해 전달되지 않도록 쿼리 문자열과 조각이 거부됩니다.
- 통과: 워크플로 또는 테스트 출력에 부정 검증 장치 이상의 토큰 리터럴이 포함되어 있지 않습니다.

수정 후 발견 사항: 없음 P0/P1.

## 계층 4. 일관성/저장소 적합성

- 통과: CI 및 야간 작업은 기존 모듈 작업 형태를 따르며 두 수집기 `needs:` 목록에 모두 포함됩니다.
- 통과: 테스트 리소스는 JUnit 수명 주기 및 로깅에 대한 프로젝트 기본값을 미러링합니다.
- PASS: README와 README.ko 항목이 쌍을 이룹니다.

조사 결과: 없음 P0/P1.

## 계층 5. 성능/동시성

- PASS: 이 슬라이스는 아직 차단 런타임 선택기를 추가하지 않습니다. 생산 루프, 스케줄러 또는 갱신 스레드가 도입되지 않습니다.
- 통과: `renewDelay()`는 TTL 만료 전에 갱신하고 Consul TTL 범위를 보호합니다.
- 통과: 런타임 클라이언트 동작에 대한 새로운 종속성이 도입되지 않았습니다. 향후 런타임 슬라이스는 내부 경계 뒤에서 Java 21 HttpClient를 사용할 수 있습니다.

조사 결과: 없음 P0/P1.

## 계층 6. API 표면/게시

- 통과: 게시 가능한 모듈이 루트 설정 및 BOM에 포함됩니다.
- 통과: 공개 API 표면은 DTO/옵션으로 제한됩니다. 세션 ID, 갱신 및 잠금 클라이언트는 내부에 유지됩니다.
- 통과: 문서에서는 백엔드에 미리보기라는 라벨을 지정하고 런타임 선택기가 아직 진행 중이라고 명시합니다.

조사 결과: 없음 P0/P1.

## 게이트

- P0: 0
- P1: 0
- Gate : 표준 검증 후 PR 작성을 위한 PASS입니다.

검증 증거:

- `./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain`는 보안 수정 후 통과되었습니다.
- Claude Code Advisor 아티팩트 `.omx/artifacts/claude-consul-slice1-code-review-final-20260522222921.md`는 로컬 보안 강화 이전에 P0=0, P1=0, Gate PASS를 보고했습니다.
