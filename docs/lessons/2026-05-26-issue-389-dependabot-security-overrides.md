# Issue 389 Dependabot Security Overrides

## Context

Issue #389 reported open Dependabot alerts for transitive Netty, Protobuf,
Vert.x, and Jackson artifacts on the default branch.

## Decision

Keep common external library versions in `bluetape4k-dependencies` and use
central catalog aliases from `leader` dependency management for the security
override surface.

## Outcome

`leader` now imports central Netty 4.1, Protobuf, and Vert.x 4 BOMs and
constrains the alerted modules through `bt4kVersion(...)`. Fabric8 was updated
to the central catalog's 7.7.0 line, and the local AWS SDK BOM drift was also
aligned to the central catalog.

## Verification

- Dependency sync updated `aws2` from `2.44.9` to `2.44.12`.
- Gradle dependency management now reads Netty, Protobuf, and Vert.x versions
  from the imported `bt4k` catalog.
- `./gradlew build -x test -x k8sTest` passed.
- Dependency insight confirmed K8s/Fabric8 resolves Netty `4.1.133.Final` and
  Vert.x `4.5.27`, while Redisson resolves Netty `4.2.13.Final`.
- `buildEnvironment` confirmed the Gradle plugin classpath upgrades
  `mysql-connector-j` to `9.7.0` and `protobuf-java` to `4.34.1`.

## Future Notes

Do not add repo-local security pins for common external libraries. Add the
missing alias to `bluetape4k-dependencies`, then consume it through the `bt4k`
catalog in downstream repos.
Check `buildEnvironment` after Dependabot alerts on `settings.gradle.kts`;
plugin classpath dependencies are outside normal subproject dependency
management.
