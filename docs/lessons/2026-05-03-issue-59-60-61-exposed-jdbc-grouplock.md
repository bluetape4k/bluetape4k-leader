# Lessons Learned — ExposedJdbcGroupLock 개선 (2026-05-03)

**관련 PR**: #63
**영향 모듈**: leader-exposed-jdbc

## L1: internal class의 반환 타입 변경은 테스트 API까지 전파된다

### 문제
`ExposedJdbcGroupLock.tryLock()` 반환 타입을 `Boolean → Boolean?`으로 변경했을 때,
테스트 파일에서 `.shouldBeTrue()` / `.shouldBeFalse()` (Kluent `Boolean` extension)가
`Boolean?`에 직접 적용되지 않아 컴파일 오류 발생.

### 교훈
`internal class`라도 테스트에서 직접 사용하는 경우 반환 타입 변경 시 테스트 파일 확인 필수.
Kluent의 `shouldBeTrue()` / `shouldBeFalse()`는 non-nullable `Boolean`에만 정의됨.
`Boolean?` 결과는 `shouldBe(true)` / `shouldBe(false)` (infix, nullable 허용)로 대체.

---

## L2: tryLock() "never-throws" 계약과 DB 오류 구별의 트레이드오프

### 문제
`ExposedJdbcLock.tryLock()`은 "DB 오류 → false 반환" never-throws 계약을 가짐.
그러나 그룹 락에서 `false`만으로는 "경합 실패"와 "DB 오류"를 구별할 수 없어
maxLeaders번 반복 후 잘못된 "모든 슬롯 채움" 결론 도출 가능.

### 교훈
그룹 순회처럼 "실패의 원인"이 다음 동작을 결정하는 경우, Boolean 대신 tri-state를 사용.
`Boolean?` (null=오류)은 기존 API 호환성을 유지하면서 오류 구별 가능한 최소 변경.

---

## L3: runCatching 내 CancellationException 재throw — 블로킹 코드도 예외 아님

### 문제
`tryAcquireOnce()` 내 INSERT `runCatching { }.onFailure`에서 `CancellationException`을
재throw하지 않음. JDBC 블로킹 코드이지만 미래 coroutine context 실행 가능성 존재.

### 교훈
JDBC 블로킹 코드라도 `runCatching` 사용 시 `CancellationException` 재throw 패턴 적용.
"never catch CancellationException without rethrowing" 규칙은 blocking/suspend 무관하게 적용.
