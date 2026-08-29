# Issue #753: README 릴리스 경계의 단일 출처

## 맥락

루트 영문·한글 README는 안정 버전과 현재 개발선을 함께 안내한다. 이 값이
각 문서에 직접 고정되면 릴리스 후에도 이전 안정 버전이나 과거 개발선이 남아
사용자가 배포된 API와 미배포 API의 경계를 잘못 이해할 수 있다.

## 결정

안정 버전은 `docs/manual/manifest.yaml`의 `releaseRef`, 현재 개발선은
`gradle.properties`의 `baseVersion`을 기준으로 삼는다. 기존
`ReadmeJvm25Contract`가 두 README의 안정 버전, 버전 매뉴얼 링크,
`baseVersion-SNAPSHOT`, 개발 API 표기를 함께 검증하도록 범위를 확장한다.

## 결과

영문·한글 README가 안정 버전 `0.5.0`과 개발 버전
`1.0.0-SNAPSHOT`을 같은 경계로 안내한다. 과거 `0.4.0` 안정 버전과
`0.6.0+` 개발선 표기는 제거했다.

## 검증

- 테스트 fixture에서 안정 버전, 매뉴얼 링크, 개발 버전, 개발 API 표기를
  각각 어긋나게 했을 때 영문·한글 README 오류를 모두 검출했다.
- 실제 README 수정 전 contract가 8개 drift를 보고했고, 수정 후 통과했다.
- 전체 manual contract와 한글 용어 감사를 최종 검증에 포함한다.

## 향후 지침

릴리스나 다음 개발선 준비로 `releaseRef` 또는 `baseVersion`을 바꿀 때는
README에 별도의 버전 상수를 추가하지 않는다. 두 metadata 파일을 기준으로
영문·한글 README를 함께 갱신하고 `ReadmeJvm25Contract`를 통과시킨다.
