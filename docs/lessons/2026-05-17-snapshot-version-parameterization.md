# 스냅샷 버전 매개변수화

컨텍스트: 중앙 포털 릴리스에서는 `-SNAPSHOT`를 제거하기 위해서만 `gradle.properties`를 편집할 필요가 없습니다.

결정: 기본적으로 `snapshotVersion=`를 비워 두고 `publish-snapshot.yml`가 `-PsnapshotVersion=-SNAPSHOT`를 통과하도록 합니다.

결과: `develop`는 릴리스 준비 상태를 유지하고 스냅샷 게시는 워크플로 명령에서 명시적으로 유지됩니다.

검증: `actionlint .github/workflows/publish-snapshot.yml`.

미래 보호: `snapshotVersion=-SNAPSHOT`를 `gradle.properties`의 기본값으로 다시 도입하지 마세요.
