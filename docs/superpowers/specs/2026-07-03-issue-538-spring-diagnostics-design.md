# Issue #538 Spring Boot diagnostics design

## 한국어 해설

이 문서는 `Issue #538 Spring Boot diagnostics design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

Issue #538 adds configuration metadata and startup diagnostics to
`leader-spring-boot`. The module already provides Spring Boot 4 auto-configuration,
backend-specific property objects, AOP validation, Micrometer tag controls, and an
opt-in `leaderElection` Actuator endpoint. The missing surface is an operator-facing
startup report that explains which leader backend beans are active and warns about
configuration combinations that are easy to miss in production.

## Source evidence

- GitHub issue #538 requires backend-specific metadata, observability/management
  metadata, startup diagnostics, non-fatal default behavior, and strict opt-in.
- `LeaderProperties` currently owns the `bluetape4k.leader.*` namespace and nested
  backend, group, and observability options.
- `LeaderAopProperties` owns the `bluetape4k.leader.aop.*` namespace, including
  `strict` for annotation footgun validation. Startup diagnostics need their own
  strict flag so warning-only operational checks do not change AOP validation policy.
- `additional-spring-configuration-metadata.json` already documents AOP metrics,
  tag cardinality controls, observability, tracing, and
  `management.endpoint.leaderElection.enabled`; backend root properties and startup
  diagnostics metadata are incomplete.
- `LeaderElectionActuatorAutoConfiguration` requires
  `management.endpoint.leaderElection.enabled=true`; HTTP exposure still depends on
  `management.endpoints.web.exposure.include`.
- Multiple backend beans can exist together. That is supported, but annotation AOP
  callers should use `bean = "..."` or `@LeaderElectionBackend` when more than one
  factory candidate is available.
- High-cardinality meter tags are controlled through
  `bluetape4k.leader.aop.metrics.tags.*`; `RAW` lock names and opt-in raw leader IDs
  should be visible as warnings.

## Scope

### In scope

- Add a nested `bluetape4k.leader.diagnostics.*` property object.
- Register a startup diagnostics auto-configuration after backend, AOP,
  Micrometer/Observation, and Actuator auto-configurations have been evaluated.
- Emit a structured startup summary through the existing logging stack.
- Detect warning conditions without failing by default.
- Fail startup only when `bluetape4k.leader.diagnostics.strict=true`.
- Expand configuration metadata for backend root properties, backend-specific
  property groups, observability/management exposure, and diagnostics properties.
- Add `ApplicationContextRunner` tests for safe, warning, disabled, and strict modes.
- Update `README.md` and `README.ko.md` with configuration examples and operator
  interpretation.
- Update the `leader-spring-boot` architecture diagram if the diagnostics path is not
  otherwise visible to readers.

### Out of scope

- Changing backend selection semantics.
- Adding a new Actuator diagnostics endpoint.
- Dispatching external alerts or publishing diagnostics as metrics.
- Making existing supported multi-backend applications fail by default.
- Replacing the existing AOP annotation validator.

## Property model

Add `LeaderDiagnosticsProperties` under `LeaderProperties`:

| Property | Default | Meaning |
|---|---:|---|
| `bluetape4k.leader.diagnostics.enabled` | `true` | Enables the startup diagnostics bean. |
| `bluetape4k.leader.diagnostics.strict` | `false` | Converts diagnostics warnings into startup failure. |
| `bluetape4k.leader.diagnostics.include-bean-names` | `true` | Includes active leader bean names in the startup summary. |

The diagnostics strict flag is intentionally separate from
`bluetape4k.leader.aop.strict`. AOP strict mode validates annotation footguns;
diagnostics strict mode validates operational configuration warnings.

## Diagnostic checks

The diagnostics bean runs after singleton creation and evaluates the Spring
`BeanFactory`, `Environment`, `LeaderProperties`, and `LeaderAopProperties`.

| Check | Severity | Strict result | Rationale |
|---|---|---|---|
| No `LeaderElector` bean exists | Warn | Fail | The application cannot use blocking leader APIs. |
| More than one non-local `LeaderElector` bean exists | Warn | Fail | Annotation users must choose a backend explicitly. |
| Only local backend is active | Info | Pass | This is valid for tests/dev, but should be visible. |
| `management.endpoint.leaderElection.enabled=true` and HTTP exposure does not include `leaderElection` or `*` | Warn | Fail | The endpoint bean may exist but the web endpoint is hidden. |
| `bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW` with no allow-list | Warn | Fail | Raw dynamic lock names can create high-cardinality metrics. |
| `bluetape4k.leader.observability.tracing.include-leader-id=true` and `leader-id.mode=RAW` with no allow-list | Warn | Fail | Raw leader IDs can leak high-cardinality or sensitive values. |

The implementation should keep checks deterministic and independent of external
systems. It must not connect to Redis, MongoDB, Consul, etcd, DynamoDB, or any
database.

## Logging contract

Startup diagnostics should log one concise summary line:

```text
leader.spring.diagnostics activeBackends=[redisson] leaderElectors=1 actuatorEndpoint=enabled webExposure=hidden warnings=1
```

When warnings exist, log each warning with a stable code:

```text
leader.spring.diagnostics.warn code=LEADER_DIAG_MANAGEMENT_EXPOSURE message="..."
```

Strict mode throws `LeaderStartupDiagnosticsException` with the warning codes in
the message. This keeps tests deterministic and gives operators searchable failure
evidence.

## Metadata design

Extend `META-INF/spring/additional-spring-configuration-metadata.json` with:

- groups for `bluetape4k.leader`, `group`, `diagnostics`, `mongo`, `etcd`,
  `consul`, and `dynamodb`;
- properties for common lease/watchdog options;
- properties for backend-specific options already present in code;
- diagnostics properties;
- management exposure metadata for
  `management.endpoints.web.exposure.include`.

The module currently uses manual additional metadata rather than kapt-generated
metadata. Keep that approach to avoid changing the build pipeline.

## Documentation and diagram design

README updates must:

- keep English/Korean parity;
- show `diagnostics` in the configuration sample;
- explain non-fatal default warnings and strict mode;
- explain how to expose `/actuator/leaderElection` over HTTP;
- explain high-cardinality warnings and the difference between diagnostics strict
  mode and AOP strict mode.

The existing `leader-spring-boot-architecture-01` diagram should show diagnostics
as a startup-time operator visibility path only if the README explanation would
otherwise be text-heavy. It must not imply diagnostics acquire locks or participate
in leader execution.

## Acceptance criteria

- Diagnostics are registered by auto-configuration when enabled.
- Safe local/default context starts and exposes a diagnostics report bean.
- Warning mode logs/records warnings but does not fail.
- Strict mode fails startup when warnings are present.
- Disabling diagnostics removes the diagnostics bean and does not run checks.
- Metadata JSON contains the new diagnostics and backend property names.
- README and README.ko document the same user-facing contract.
- Diagram assets are updated and validated if the README embeds a changed visual.
