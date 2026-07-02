# Lessons Learned — Issue 519 Spring Boot Testcontainer Isolation (2026-07-02)

**Related issue**: #519
**Affected module**: `bluetape4k-leader-spring-boot`

## L1: Fault-injection tests must not hold shared backend clients

### Problem

`FailOpenRunIntegrationTest` verified `FAIL_OPEN_RUN` behavior with a Redis/ToxiProxy fault-injection path, while the same class also held a shared `RedisServer.Launcher.redis` client and connection through companion lazy properties. Later Spring Boot auto-configuration tests also cached Lettuce and Mongo clients in static lazy properties. A full module run could then observe stale backend connections or a not-started shared container state after the fault-injection scenario.

### Lesson

Keep singleton Testcontainers launchers as backend providers only. Do not cache mutable backend clients or connections in static test properties when Spring contexts or network fault injection are involved. Let the Spring test context own clients and connections through beans with explicit destroy methods, and use non-reusable, test-owned containers for fault-injection scenarios that mutate connectivity.

### Verification

- `./gradlew :bluetape4k-leader-spring-boot:compileTestKotlin --no-daemon --console=plain --warning-mode all`
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.FailOpenRunIntegrationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.LettuceAopFactoryAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.MongoAopFactoryAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.RedissonAopFactoryAutoConfigurationTest' --no-build-cache --rerun-tasks --no-parallel --no-daemon --console=plain --warning-mode all`
- `./gradlew :bluetape4k-leader-spring-boot:test --no-build-cache --rerun-tasks --no-parallel --no-daemon --console=plain --warning-mode all`

### Future Guard

If a test changes container connectivity, proxy state, or backend process lifecycle, it should not reuse shared launcher clients. `MultithreadingTester` is not the right proof for this class of failure because the risk is external Testcontainers/Spring context lifecycle ordering, not an in-process thread-safety race; verify it with real Gradle Testcontainers runs instead.
