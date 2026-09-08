# #922 R2DBC 획득 오류와 재시도 경계

## 원인과 수정

획득 바깥쪽의 `catch (Throwable)`이 JVM Error까지 경합 또는 unavailable로
변환했고, 단일 락은 non-transient 오류도 대기 예산까지 반복했다.
`Exception`으로 경계를 좁히고 cause 체인의 Error·취소를 먼저 전파한다.
재시도 종류는 기존 `ExposedR2dbcBackendErrorClassifier`와
`CompositeBackendErrorClassifier`를 재사용한다. 그룹의 기존 DB unavailable
반환 정책과 availability callback의 보상 정리·suppressed 우선순위는 유지한다.

## 재사용 판단

`getMostSpecificCause`만 쓰면 중간 Error가 최하위 일반 예외에 가려진다.
동일 객체 기반 집합으로 cause 순환을 종료하고 backend 타입을 보존한다.
새 의존성이나 public 생성자·반환형 변경은 필요하지 않았다.

## 회귀 재현에서 배운 점

Exposed 1.5.0의 `suspendTransaction$default`는 실제 함수에 도달하기 전에
DB transaction manager에서 기본 인자를 평가한다. 등록되지 않은 mock DB는
실패 주입 전에 `No transaction manager`로 종료하므로 올바른 RED가 아니다.
등록된 H2 DB와 mock transaction 본문을 조합한 뒤 의도한 5개 실패를 확인했다.
수정 후 fatal·wrapped 취소·재시도·예산·cause 순환 등 12개 테스트가 통과했다.

## 검토와 한계

독립 reviewer 실행이 불가하여 workspace 규칙에 따른 주 세션 직접 검토를
수행했다. 설계 관점도 사용자가 승인한 직접 검토 대체이며 독립 PASS가 아니다.
소스·호출자·테스트·API/ABI·문서·CI·설계 위험을 구분해 PR에서 증거를 기록한다.
DB 호출 자체의 최대 실행 시간은 드라이버 설정의 책임이며 대기 예산만으로
강제 중단을 보장하지 않는다. 머지와 외부 CI는 별도 확인 항목이다.

SPW-01~05: 한국어, 실제 source·javap·RED/GREEN 근거, 정책과 한계 구분,
링크 없는 허위 출처 배제, 계획 및 README 두 언어와 일치 확인.
