# Exposed 1.9.2 안정적인 정렬

## 맥락

`bluetape4k-exposed` 1.9.2는 Maven Central에 게시되었지만 리더는 여전히 Exposed 테스트 픽스처에 `bluetape4k-exposed = 1.9.2-SNAPSHOT`를 사용했습니다.

## 결정

기존 `bluetape4k` 코어 BOM을 `1.9.2`에 유지하면서 직접 `bluetape4k-exposed` 카탈로그 버전을 안정적인 `1.9.2` 릴리스로 이동합니다.

## 결과

Leader의 Exposed JDBC/R2DBC 모듈은 더 이상 Exposed 도우미의 임시 스냅샷 라인에 의존하지 않습니다.

## 검증

- `bluetape4k-exposed-bom:1.9.2`용 Maven Central HTTP 200

## 미래 노트

안정적인 Exposed 릴리스가 표시된 후 다운스트림 직접 참조는 다음 리더 릴리스 준비 전에 일치하는 스냅샷에서 안정적인 BOM으로 이동해야 합니다.
