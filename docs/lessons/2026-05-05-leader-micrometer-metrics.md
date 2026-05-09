# Lessons Learned — leader-micrometer metrics 통합 (2026-05-05)

**관련 PR**: feat/leader-micrometer-metrics (Issue #75)
**영향 모듈**: `leader-micrometer`, `leader-spring-boot3`, `leader-spring-boot4`, `leader-spring-boot-common`

---

## L1: `@ConfigurationProperties` 어노테이션 누락이 전체 AutoConfig를 중단시킨다

### 문제
`LeaderAopProperties`에 `@ConfigurationProperties(prefix = "bluetape4k.leader.aop")` 어노테이션이 없었다.
`@EnableConfigurationProperties(LeaderAopProperties::class)`는 이 어노테이션이 없으면 바인딩을 거부하고 애플리케이션 컨텍스트 시작 자체가 실패한다.

이 버그는 `ApplicationContextRunner` 기반 통합 테스트 이전에는 발견되지 않았다. 기존 통합 테스트들이 `@SpringBootTest`를 사용하지 않거나 해당 AutoConfig를 로드하지 않았기 때문이다.

### 교훈
- 새 AutoConfiguration 클래스를 작성할 때 `@EnableConfigurationProperties` 대상 클래스에 `@ConfigurationProperties`가 있는지 반드시 확인한다.
- `ApplicationContextRunner`를 사용한 AutoConfig 슬라이스 테스트가 이런 종류의 버그를 가장 빠르게 잡아낸다.

---

## L2: `@ConditionalOnBean` same-class ordering 문제 → 별도 AutoConfig로 분리

### 문제
`@AutoConfiguration` 클래스 내부에 nested `@Configuration` + `@ConditionalOnBean(SomeBean::class)`을 쓰면, nested 클래스의 조건이 외부 클래스의 `@Bean` 메서드가 등록되기 **전에** 평가된다.

```kotlin
// 이 패턴은 동작하지 않는다
@AutoConfiguration
class FooAutoConfiguration {
    @Bean
    fun recorder(): Recorder = ...  // ← 아직 미등록 상태에서

    @Configuration
    @ConditionalOnBean(Recorder::class)  // ← false로 평가됨
    class HealthConfig {
        @Bean fun health(): HealthIndicator = ...  // 등록 안 됨
    }
}
```

### 교훈
- `@ConditionalOnBean`이 외부 클래스의 `@Bean`에 의존하는 경우 반드시 별도 AutoConfiguration 클래스로 분리하고 `@AutoConfiguration(after = [FooAutoConfiguration::class])`로 순서를 명시한다.
- `AutoConfiguration.imports`에 두 클래스를 모두 등록해야 Spring Boot가 순서 힌트를 올바르게 반영한다.

---

## L3: `@ConditionalOnClass`에 `name=` 방식으로 classpath 안전 가드

### 문제
`@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])`만 가드하면, `leader-micrometer` 모듈이 `compileOnly`로 선언된 경우 `MeterRegistry`는 있지만 `MicrometerLeaderAopMetricsRecorder`가 없는 환경에서 `NoClassDefFoundError`가 발생할 수 있다.

Spring의 `@ConditionalOnClass`는 ASM 바이트코드 파싱으로 class body를 읽기 때문에, `@Bean` 반환 타입에 사용된 클래스가 classpath에 없으면 조건 평가 자체가 실패한다.

### 교훈
- `compileOnly` 의존성으로 선언된 클래스가 `@Bean` 반환 타입이나 필드 타입으로 사용될 경우, `@ConditionalOnClass(name = [...])` 배열에 해당 클래스도 포함한다.
- class literal(`value = [Foo::class]`)은 컴파일 타임에 이미 classpath에 있는 클래스만 사용 가능 — 선택적 의존성에는 항상 `name=` 방식을 사용한다.

---

## L4: lock name prefix가 테스트 환경에서 `:` prefix를 추가한다

### 문제
`LeaderAopProperties.DEFAULT_LOCK_NAME_PREFIX = "\${spring.application.name:}:"` 는 `spring.application.name`이 설정되지 않으면 빈 문자열로 해석되어 prefix가 `":"` 가 된다.

따라서 `@LeaderElection(name = "test-lock")` 은 실제 lock name을 `:test-lock`으로 사용하고, 테스트에서 `registry.get("leader.aop.attempts").tag("lock.name", "test-lock")` 조회가 `MeterNotFoundException`으로 실패한다.

### 교훈
- `ApplicationContextRunner` 기반 AOP 통합 테스트에서는 `.withPropertyValues("bluetape4k.leader.aop.lock-name-prefix=")` 로 prefix를 빈 문자열로 설정해야 태그가 예측 가능하다.

---

## L5: `(x >= 0.0).shouldBeTrue()` 대신 `x shouldBeGreaterOrEqualTo 0.0`

### 문제
CLAUDE.md에 명시된 bluetape4k-assertions 스타일 규칙 위반. `(x >= y).shouldBeTrue()`는 실패 시 assertion 메시지가 `expected true but was false`로만 나와 디버깅이 어렵다.

### 교훈
- bluetape4k-assertions 비교 매처(`shouldBeGreaterOrEqualTo`, `shouldBeLessOrEqualTo`, `shouldBeGreaterThan` 등)를 사용한다.
- `(expression).shouldBeTrue()` / `.shouldBeFalse()` 패턴은 boolean 속성 확인 외에는 사용하지 않는다.
