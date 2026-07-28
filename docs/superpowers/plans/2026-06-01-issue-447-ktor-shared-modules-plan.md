# Issue 447 Ktor Shared Module Adoption Plan

## 한국어 해설

이 문서는 `Issue 447 Ktor Shared Module Adoption Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

`bluetape4k-projects` 1.10.0 publishes shared Ktor modules. `bluetape4k-leader`
already depends on the 1.10.0 BOM and has two Ktor surfaces:

- `leader-ktor`: leader-election plugin, scheduler integration, and management route.
- `examples/ktor-app`: runnable Ktor example with generic health and JSON test assertions.

## Decision

Keep `leader-ktor` leader-specific. Adopt shared Ktor modules only where they
own generic behavior:

- Use `bluetape4k-ktor-core` health/readiness routes in `examples/ktor-app`.
- Keep Jackson `ContentNegotiation` in the example because its public `/stats`
  response contains `java.time.Instant`.
- Use `bluetape4k-ktor-testing` response status and JSON decode assertions for
  the shared health/readiness response.

## Verification

- Run `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`.
- Run `git diff --check`.
