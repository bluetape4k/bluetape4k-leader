# 릴리스 워크플로우 표준화

컨텍스트: 중앙 포털 릴리스 캠페인은 `bluetape4k-projects`를 표준 릴리스 워크플로 형태로 사용합니다.

결정: 워크플로 표시 이름을 `Nightly`로 유지하면서 Nightly 워크플로 파일의 이름을 `nightly-tests.yml`로 바꾸고 상담원 지침 참조를 업데이트합니다.

결과: 릴리스 준비 스크립트는 bluetape4k 저장소 전체에서 동일한 작업 흐름 파일 이름을 사용할 수 있습니다.

검증: `actionlint .github/workflows/nightly-tests.yml .github/workflows/publish-snapshot.yml .github/workflows/release.yml`.

미래 보호: 저장소별 예외가 `AGENTS.md`에 문서화되어 있지 않은 한 릴리스 워크플로 파일 이름을 `bluetape4k-projects`와 일치하도록 유지합니다.
