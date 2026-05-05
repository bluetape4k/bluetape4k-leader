# Spring 6 vs 7 API Surface Verify (T2.21)

날짜: 2026-05-04
이슈: #41 leader-aop / Q-P3 (b)
범위: `leader-spring-boot-common` 이 사용하는 Spring API 가 Boot 3 (Spring 6.2.x) 와 Boot 4 (Spring 7.x) 양쪽에서 동작하는지 검증.

## 사용 API 목록

| API | 용도 | Spring 6.2 | Spring 7.x |
|-----|------|-----------|-----------|
| `org.aspectj.lang.ProceedingJoinPoint` | Aspect advice | provided by `aspectjweaver` (Spring 무관) | 동일 |
| `org.aspectj.lang.annotation.@Aspect`, `@Around` | Aspect 선언 | aspectjweaver | 동일 |
| `org.aspectj.lang.reflect.MethodSignature` | join point method 추출 | aspectjweaver | 동일 |
| `org.springframework.aop.support.AopUtils.getTargetClass(target)` | proxy → target 클래스 | spring-aop 6.2 | spring-aop 7.x — API 동일 |
| `org.springframework.beans.factory.BeanFactory` / `ListableBeanFactory` | 빈 lookup | spring-beans 6.2 | spring-beans 7.x — API 동일 |
| `getBean(name, Class)`, `getBeansOfType(Class)`, `getBeanNamesForType(Class)` | 빈 lookup | 동일 | 동일 (deprecated 변경 없음) |
| `org.springframework.beans.factory.SmartInitializingSingleton` | startup hook | spring-beans 6.2 | 동일 |
| `org.springframework.beans.factory.config.BeanPostProcessor` | BPP | 동일 | 동일 |
| `org.springframework.expression.Expression` / `SpelExpressionParser` | SpEL | spring-expression 6.2 | spring-expression 7.x — API 동일 |
| `org.springframework.expression.spel.support.SimpleEvaluationContext` | 보안 컨텍스트 | 6.2 | 7.x — API 동일 (`forPropertyAccessors`, `withMethodResolvers`, `withRootObject`, `build` 모두 동일 시그니처) |
| `DataBindingPropertyAccessor.forReadOnlyAccess()` | property accessor | 6.2 | 7.x — 동일 |
| `DataBindingMethodResolver.forInstanceMethodInvocation()` | method resolver | 6.2 | 7.x — 동일 |
| `org.springframework.context.expression.MethodBasedEvaluationContext` | (현재 미사용, 참고만) | 6.2 | 7.x — 동일 |
| `org.springframework.core.DefaultParameterNameDiscoverer` | 메서드 파라미터 이름 추출 | spring-core 6.2 | 7.x — 동일 |
| `org.springframework.util.StringValueResolver` | `${...}` placeholder 해석기 | spring-core 6.2 | 7.x — 동일 |
| `org.springframework.core.annotation.AnnotatedElementUtils` | 어노테이션 lookup | spring-core 6.2 | 7.x — 동일 |
| `org.springframework.core.Ordered` | order 상수 | spring-core 6.2 | 7.x — 동일 |

## Health Indicator API

| API | Boot 3 | Boot 4 |
|-----|--------|--------|
| `org.springframework.boot.health.contributor.HealthIndicator` | Boot 3.4+ (재구조화 완료) | Boot 4 동일 패키지 |
| `Health.up().withDetail(...).build()` | 동일 | 동일 |

## 호환성 결론

본 PR `leader-spring-boot-common` 이 사용하는 모든 Spring/AspectJ API 는 **Spring 6.2 ↔ 7.x 간 동일 시그니처** 를 제공한다.
- 파괴적 변경 없음
- deprecated 표시된 사용 API 없음

## 검증 방법

`leader-spring-boot3-aop` 와 `leader-spring-boot4-aspectj` 모듈의 통합 테스트 (Phase 5) 에서:
- Boot 3 컨텍스트로 build + smoke test → Spring 6.2.x 호환 검증
- Boot 4 컨텍스트로 build + smoke test → Spring 7.x 호환 검증

## 후속 발견 시 갱신

본 노트는 구현 단계에 발견된 차이가 있을 때 재방문. 현재까지 차이 없음 (2026-05-04 기준).
