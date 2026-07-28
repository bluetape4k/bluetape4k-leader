# 중앙 종속성 거버넌스 동기화

## 맥락

다운스트림 Dependabot PR은 한 번에 하나의 리포지토리 공유 종속성 버전을 업데이트하여 bluetape4k 조직 전체에 버전 드리프트를 생성했습니다.

## 결정

공유 종속성 버전은 먼저 `bluetape4k-dependencies`에서 변경된 다음 `sync-shared-versions.py`를 사용하여 이 저장소로 구체화되어야 합니다. 또한 이 저장소는 Dependabot에서 중앙에서 관리되는 종속성 이름을 무시하므로 향후 PR은 중앙 정보 소스를 통해 라우팅됩니다.

## 결과

로컬 버전 카탈로그와 `.github/dependabot.yml`는 이제 중앙 종속성 거버넌스 정책을 따릅니다.

## 검증

- 이 저장소의 `sync-shared-versions.py --write --check --summary`
- 이 저장소의 `sync-dependabot-ignores.py --write --check --summary`
- `git diff --check`

## 퓨쳐 가드

중앙에서 관리되는 종속성을 위해 repo-local Dependabot PR을 병합하지 마세요. `bluetape4k-dependencies`를 업데이트한 후 이 저장소를 동기화하세요.
