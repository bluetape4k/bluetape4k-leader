# Issue 289 테넌트 네임스페이스 문서

## 맥락

`leader-core`는 이미 `TenantLockNamespace` 및 `forTenant()` 래퍼를 노출하지만 모듈 README 쌍에서는 이를 설명하지 않았습니다. 최상위 README 파일에는 테넌트 네임스페이스 지정 지침이 있으므로 모듈 전용 독자는 해당 기능을 놓칠 수 있습니다.

## 결정

주 검사 계약 근처의 `leader-core/README.md` 및 `leader-core/README.ko.md`에 집중된 `Tenant Namespacing` 섹션을 추가합니다. 실제 구현 계약을 문서화합니다. 생성된 백엔드 이름은 `prefix:tenantId:lockName`를 사용하고, `:`는 모든 네임스페이스 부분과 테넌트-로컬 잠금 이름에 예약되어 있으며, 최종 이름은 255자 공유 잠금 이름 제한을 충족해야 합니다.

## 결과

이제 모듈 README 쌍에서는 `TenantLockNamespace`, `forTenant()`, 사용자 정의 접두사, 구분 기호 제한, 잠금 이름 길이 예산 및 차단/그룹 사용 예를 다룹니다.

## 검증

- `rg -n "Tenant Namespacing|TenantLockNamespace|forTenant|255|batch:daily|tenant-a|테넌트 네임스페이스|255자" leader-core/README.md leader-core/README.ko.md`
- `git diff --check`

## 미래의 에이전트

문제 텍스트가 소스 코드와 일치하지 않는 경우 소스 코드 계약을 문서화하세요. 이 문제에 대해 본문에서는 `::`를 언급했지만 `TenantLockNamespace`는 단일 `:` 구분 기호를 예약합니다.
