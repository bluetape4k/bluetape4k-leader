# 2026-06-04 474호 야간 구성 캐시 및 카탈로그

## 맥락

Nightly 워크플로는 스냅샷 및 BOM 관리 종속성을 사용하므로 오래된 Gradle/구성 상태는 버전 없는 종속성 좌표를 표면화할 수 있습니다.

## 결정

`--no-configuration-cache`에서 Nightly Gradle 명령을 유지하고 BOM 참조를 통해 로컬 bluetape4k 별칭 버전을 유지합니다.

## 결과

Nightly 명령은 종속성을 새로 고치는 동안 더 이상 구성 캐시에 의존하지 않으며 repo-local 카탈로그 별칭은 `group:artifact:.` 좌표를 피합니다.

## 검증

- 계획됨: `actionlint`, `git diff --check`, 명령 감사, 카탈로그 별칭 감사.

## 미래의 규칙

스냅샷을 새로 고치는 야간 작업의 경우 저장소별 증명에서 달리 명시하지 않는 한 Gradle 작업 캐시와 구성 캐시를 모두 비활성화하세요.
