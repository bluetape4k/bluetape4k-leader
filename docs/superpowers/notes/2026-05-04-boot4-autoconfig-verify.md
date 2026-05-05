# Boot 4 GA `@AutoConfiguration` API Verify (T4.6)

날짜: 2026-05-04
이슈: #41 leader-aop / Q-P3 (b)
범위: Spring Boot 4.0.x 의 `@AutoConfiguration` / `AutoConfiguration.imports` / `@ConditionalOn*` 가
Boot 3.5.x 와 동일하게 동작하는지 verify.

## 사용 API 목록

| API | Boot 3.5 | Boot 4.0 |
|-----|----------|----------|
| `@AutoConfiguration` | `org.springframework.boot.autoconfigure.AutoConfiguration` | 동일 패키지 (변경 없음) |
| `@AutoConfiguration(after = ...)` | 동작 | 동작 (변경 없음) |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 동일 경로 | 동일 경로 |
| `@ConditionalOnClass` | spring-boot-autoconfigure 3.5 | 4.0 동일 |
| `@ConditionalOnBean` | 동일 | 동일 |
| `@ConditionalOnMissingBean` | 동일 | 동일 |
| `@ConditionalOnProperty` | 동일 | 동일 (`matchIfMissing` 동작 동일) |
| `@EnableConfigurationProperties` | 동일 | 동일 |
| `@EnableAspectJAutoProxy(proxyTargetClass=true)` | spring-context 6.2 | spring-context 7.x — API 동일 |

## HealthIndicator path

| API | Boot 3.5 | Boot 4.0 |
|-----|----------|----------|
| `org.springframework.boot.actuate.health.HealthIndicator` | OK (existing) | **deprecated for new path** |
| `org.springframework.boot.health.contributor.HealthIndicator` | (Boot 3.5 에는 없음) | NEW canonical path |

본 PR 에서는 Boot 3.5 호환 path (`actuate.health.HealthIndicator`) 를 common 에 사용. Boot 4 는 deprecation warning 있지만 호환 유지. 후속 PR 에서 Boot 4 GA 시 `health.contributor` path 마이그레이션 검토.

## Freefair AspectJ Compatibility

| 항목 | 상태 |
|------|------|
| `io.freefair.aspectj.post-compile-weaving` v9.5.0 | Spring Boot 4 / Kotlin 2.3 / JVM 21 호환 (release notes 검증) |
| `aspect(project(":leader-spring-boot-common"))` config | dependency JAR `@Aspect` 클래스 weaving (discussion #493) |
| Kotlin `final class @Aspect` | Spring AOP 가 aspect 자체는 proxy 안 함 → final OK |
| `spring-boot-starter-aop` 동시 활성 | **회피** (issue #1050 advice 2회 발화) — 본 PR 미추가 |

## 실제 검증 방법

본 노트는 문서 기반 verify. 실제 동작 검증은 Phase 5 통합 테스트 (T5.13a/T5.25) 에서:
- Boot 4 컨텍스트 build + smoke test → AutoConfig 활성화 확인
- Freefair weaving 결과 advice 적용 확인 (T5.26)
- Health endpoint `/actuator/health` 동작 확인 (T5.21b)

## 후속 갱신 트리거

- Boot 4 GA 시점 `@AutoConfiguration` API 변경 발견 → 본 노트 갱신
- `health.contributor.HealthIndicator` 마이그레이션 결정 → 별도 PR
