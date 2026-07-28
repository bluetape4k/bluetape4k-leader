## 맥락

공유 종속성 카탈로그에 중앙 플러그인 별칭이 추가된 후 리더 마이그레이션 게이트 예시에 JetBrains Exposed Gradle 플러그인을 채택했습니다.

## 결정

라이브러리 저장소는 관리되는 `bt4k` 카탈로그의 플러그인 별칭을 사용하고 기본 카탈로그 참조를 `catalog/2026-05-26-00`에 고정해야 합니다.

## 결과

이제 `examples:migration-gate`는 명시적 테이블 패키지 및 H2 마이그레이션 데이터베이스 설정을 사용하여 `generateMigrations` 작업을 노출합니다.

## 검증

`git diff --check`, `./gradlew -q help` 및 `:examples:migration-gate:tasks --all`를 실행했습니다.

## 퓨쳐 가드

Exposed 플러그인 DSL 속성 `databaseUser` 및 `databasePassword`를 사용합니다. `user` 및 `password`는 확장 속성이 아닙니다.
