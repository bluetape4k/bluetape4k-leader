# Database URL redaction을 fail-closed로 유지하기

## 문제

schema initializer는 초기화 성공과 실패 로그에 database URL을 기록합니다. 기존 JDBC와
R2DBC 구현은 `URI` 파싱에 실패하면 입력 문자열을 그대로 반환했습니다. malformed URL에
userinfo나 token이 들어 있으면, 오류가 발생한 바로 그 경로에서 credential 원문이 로그로
노출될 수 있었습니다.

## 적용한 계약

- JDBC와 R2DBC는 `leader-exposed-core`의 단일 redactor를 사용합니다.
- 정상 hierarchical URL은 backend, host, port, database path를 유지합니다.
- userinfo는 `***`로 바꾸고 query, semicolon property, fragment는 모두 제거합니다.
- opaque URL은 인코딩된 delimiter 우회를 차단하기 위해 backend scheme과 `<redacted>`만 남깁니다.
- 파싱 실패, 빈 입력, wrapper나 URI 구조를 해석할 수 없는 형식은
  `<invalid-database-url>`을 반환합니다.
- 초기화 실패 로그에는 예외 객체를 전달하지 않고 안전한 예외 타입만 남깁니다. 예외 message와
  cause에 raw URL이 포함돼도 로그로 전달하지 않습니다.
- redactor는 `@LeaderInternalApi`와 `@JvmSynthetic`으로 stable application API로 문서화하지 않고
  Java source에서 숨깁니다. JVM bytecode에는 synthetic internal SPI symbol이 존재하며,
  binary compatibility 검사는 이 symbol을 공개 호환성 변경으로 분류하지 않습니다.

## 결과와 검증

공유 contract를 구현 전에 실행했을 때 JDBC 모듈 374개 중 9개가 실패했습니다. 기존
userinfo 테스트 일부만 통과했고, 교차 backend, query, semicolon property, malformed URL,
특수문자 password 입력은 credential을 그대로 반환했습니다.

공통 redactor를 연결한 뒤 독립 리뷰에서 opaque URL의 인코딩된 semicolon 우회와 예외 객체
logging 경로를 추가로 발견했습니다. 보강한 URL contract 4개와 예외 로그 capture test는 기존
구현에서 각각 실패했고, opaque URL 최소화와 throwable 미전달 수정 뒤 통과했습니다.

최종 검증에서 `leader-exposed-core` 104개, JDBC 380개, R2DBC 361개가 실패와
skip 없이 통과했습니다. `detekt`와 `checkBinaryCompatibility`도 통과했고, 세 Exposed
artifact는 공개 호환성 변경으로 분류되지 않았습니다.

## 놓친 점

기존 JDBC 테스트는 malformed URL을 원본 그대로 반환하는 동작을 정상 계약으로 고정하고
있었습니다. R2DBC 테스트는 정상 userinfo만 검사해 query와 semicolon property 경로를
다루지 않았습니다. backend마다 다른 예제만 두면 fail-open fallback과 test drift를 함께
놓칠 수 있습니다.

## 재발 방지

credential key 목록만 열거해 값 일부를 마스킹하면 새 driver property나 철자 변형을 놓칠 수
있습니다. 로그 경계에서는 query와 semicolon property 전체를 제거하는 편이 안전합니다.
opaque URL은 delimiter가 percent-encoding될 수 있으므로 scheme-specific part를 해석하거나
재출력하지 않습니다. SQL Server처럼 authority 뒤에 semicolon property가 오는 URL은 property를
먼저 제거한 뒤 host를 파싱합니다.
또한 JDBC와 R2DBC가 각자 test vector를 복사하면 한쪽만 강화되는 drift가 생깁니다. 두 모듈의
test source set이 같은 contract source를 실행하도록 연결해 userinfo, query, semicolon,
malformed, 특수문자 password를 같은 표로 검증합니다.

URL 처리에서 예외가 나면 원문을 반환하는 fallback은 redaction이 아닙니다. 앞으로 로그용
sanitizer를 추가하거나 수정할 때는 parse failure 결과가 고정 placeholder인지, 반환 문자열에
입력 secret이 남지 않는지를 RED 테스트로 먼저 확인합니다. 정제된 message 옆에 throwable을
함께 전달하면 예외 message와 cause가 별도 필드로 노출될 수 있으므로, security-sensitive 로그는
formatted message와 throwable proxy를 함께 capture해 검증합니다.
