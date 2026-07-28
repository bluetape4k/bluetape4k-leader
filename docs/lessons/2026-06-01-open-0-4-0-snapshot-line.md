# 2026-06-01 0.4.0 스냅샷 라인 오픈

## 맥락

`bluetape4k-leader` `0.3.0`는 이전 종속성 릴리스 트레인에 포함되었습니다. 다음 카탈로그-열 스냅샷은 새 프로젝트와 Exposed 스냅샷 라인을 사용해야 합니다.

## 결정

커밋된 `baseVersion=0.4.0` 및 `snapshotVersion=`를 비워두고 직접 BOM 참조를 `bluetape4k-bom:1.11.0-SNAPSHOT` 및 `bluetape4k-exposed-bom:1.11.0-SNAPSHOT`에 맞춥니다.

## 결과

저장소는 다음 내부 스냅샷 트레인을 기준으로 검증합니다.

## 검증

- `./gradlew help --no-daemon --console=plain`는 업데이트된 카탈로그를 검증합니다.
