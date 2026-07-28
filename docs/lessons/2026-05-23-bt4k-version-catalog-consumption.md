# bt4k 버전 카탈로그 소비

## 맥락

`bluetape4k-leader`는 생태계 카탈로그가 이미 해당 값을 게시하는 동안 여러 공유 종속성 버전을 로컬로 복제했습니다.

## 결정

`io.github.bluetape4k:bluetape4k-version-catalog`를 `bt4k`로 가져오고 `bt4kVersion(alias)`를 통해 공유 종속성 제약 조건을 해결합니다.

## 결과

선택한 공유 종속성 별칭은 이제 로컬 카탈로그에서 버전이 없습니다. 해당 버전은 공유 카탈로그의 종속성 관리를 통해 제공됩니다.

## 검증

- `git diff --check`
- `./gradlew help --no-daemon --no-configuration-cache`
- `./gradlew compileKotlin --no-daemon --no-configuration-cache`

## 향후 지침

`bt4k`가 이미 노출한 경우 공유 Redis, JDBC/R2DBC, 로깅 및 클러스터 종속성 버전에 대한 로컬 핀을 피하세요.
