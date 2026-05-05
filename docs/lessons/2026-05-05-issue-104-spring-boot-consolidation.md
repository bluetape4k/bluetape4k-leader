# Lessons Learned — Issue #104: Spring Boot 3 drop, leader-spring-boot 통합 (2026-05-05)

**관련 PR**: feat/issue-104-spring-boot4-only
**영향 모듈**: leader-spring-boot (신규), leader-core, leader-micrometer, leader-bom

---

## L1: AspectJ CTW에서 `@EnableAspectJAutoProxy` 제거가 필수

### 문제
`leader-spring-boot-common` 코드를 그대로 복사하면 `@EnableAspectJAutoProxy(proxyTargetClass = true)`가
`LeaderAopAutoConfiguration`에 남아있어 CTW + Spring AOP 이중 어드바이스 위험이 생긴다.

### 교훈
Freefair AspectJ post-compile weaving(CTW)을 사용하는 모듈에서는 `@EnableAspectJAutoProxy`를
명시적으로 제거해야 한다. 어드바이스는 컴파일 시 바이트코드에 직접 위빙되므로 런타임 프록시가 불필요하다.
`compileTestKotlin` 경고 `adviceDidNotMatch`는 테스트 소스에 `@LeaderElection` 메서드가 없을 때의
정상적인 CTW 동작이다 — 에러로 취급하지 말 것.

---

## L2: `@Around` pointcut 문자열은 패키지 이동 시 컴파일러가 잡지 못한다

### 문제
`@Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderElection)")` 같은
pointcut 문자열은 Kotlin/Java 컴파일러가 타입 검사를 하지 않는다. 어노테이션 클래스를
`leader-core`의 `io.bluetape4k.leader.annotation` 패키지로 이동한 후 이 문자열을
업데이트하지 않으면 런타임에 어드바이스가 적용되지 않고 에러 로그도 없다.

### 교훈
패키지 이동 후 `@Around`/`@Before`/`@After` pointcut 문자열을 반드시 grep으로 검증한다:
```bash
rg "@annotation\(" --type kotlin
```

---

## L3: `Binder.bind(...).orElse(T())` 반환 타입은 Kotlin에서 nullable

### 문제
Spring `BindResult<T>.orElse(T)` 메서드는 Kotlin에서 `T?`로 추론되어
`.orElse(LeaderAopProperties())` 결과를 직접 멤버 접근하면 컴파일 에러가 발생한다.

### 교훈
빈 source에서 default 값으로 바인딩할 때는 `Binder.bindOrCreate(prefix, klass)`를 사용한다.
`orElse()`는 테스트에서 null-safety를 이유로 사용하지 말 것.

---

## L4: AutoConfiguration 모듈 통합 시 `.imports` 파일 내 FQCN 패키지 주의

### 문제
`leader-spring-boot4`의 auto-configuration 클래스 패키지가 `io.bluetape4k.leader.spring.boot4.aop.autoconfigure`였는데,
`leader-spring-boot`에서 `io.bluetape4k.leader.spring.aop.autoconfigure`로 변경되었다.
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일의
FQCN이 구 패키지로 남아 있으면 AutoConfiguration이 로드되지 않는다.

### 교훈
모듈 통합 후 `.imports` 파일의 FQCN을 반드시 새 패키지로 업데이트하고 통합 테스트로 검증한다
(`ApplicationContextRunner` 사용).
