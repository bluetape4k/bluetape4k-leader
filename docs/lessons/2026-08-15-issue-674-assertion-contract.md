# 테스트 assertion contract를 중앙 게이트로 고정하기

## 상황

Issue #674 TEST-01에서 일부 테스트가 `runCatching { }.isFailure`만 확인해
실패 원인의 타입과 메시지를 검증하지 않았고, JUnit assertion이 Bluetape
테스트 규칙과 혼용되고 있었다.

## 재사용할 원칙

- 예상 예외 테스트는 `io.bluetape4k.assertions.assertFailsWith`로 구체적인
  예외 타입을 고정하고, 호출자에게 중요한 메시지·cause를 함께 검증한다.
- virtual-thread `Future.await()`처럼 래퍼 예외가 생기는 경로에서는 래퍼와
  `cause`를 각각 검증해 예외 전파 계약을 가리지 않는다.
- `kotlin.test`/JUnit assertion import·호출과 예외 결과만 확인하는
  `runCatching` 패턴은 repository-wide static validator와 self-test로 신규
  유입을 차단한다.
- 정상 경로의 no-throw 검증은 별도 래퍼보다 성공 호출 자체를 실행해 결과를
  검증한다.

## 증거

- `scripts/ci/validate_test_assertion_contract.py`
- `python3 scripts/ci/validate_test_assertion_contract_test.py` — 5 tests PASS
- PR #710의 core/R2DBC/Spring Boot/Micrometer targeted test와 detekt 증거

## 후속 주의

MongoDB Testcontainers 런타임 검증은 Docker provider가 준비된 CI에서
재확인한다. 컴파일 검증은 로컬에서 통과했다.
