# Issue #424 ZooKeeper scheduler example

## Context

Issue #424 added a runnable ZooKeeper example for a legacy scheduled job that must execute on only one node.

## Decision

Use `ZooKeeperLeaderElector` as the only election boundary and keep Curator lifecycle caller-owned in the demo and tests. The example also uses bluetape4k helpers (`ZooKeeperServer.Launcher`, `Base58`, `requireNotBlank`, and bluetape4k assertions) so it demonstrates the ecosystem rather than raw Curator recipes.

## Outcome

The new `examples:zookeeper-scheduler` module shows execute, skip, and reacquire behavior with README entries in both locales and CI/examples workflow registration.

## Verification

- `./gradlew :examples:zookeeper-scheduler:compileKotlin --no-daemon --no-configuration-cache`
- `./gradlew :examples:zookeeper-scheduler:test --no-daemon --no-configuration-cache`
- `./gradlew :examples:zookeeper-scheduler:run --no-daemon --no-configuration-cache`
- `./gradlew projects --no-daemon --no-configuration-cache`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## Future Rule

Example modules should prove ecosystem usage explicitly and must be registered across settings, README locale files, `AGENTS.md`, CI, and examples workflows before PR creation.
