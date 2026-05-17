# Central release examples parent exclusion

## Context

Central validation failed after the first `0.1.0` tag because an `examples`
publication was generated. Nested Gradle includes create both `:examples` and
`:examples:*`; filtering only `:examples:*` leaves the parent project in the
publishing path.

## Decision

Exclude both `:examples` and `:examples:*` from NMCP setup, aggregation,
publication/signing setup, and coverage registration. Keep Spring
dependency-management POM customization enabled so dependency versions are
written to generated POMs.

## Outcome

Leader release metadata now contains only library modules and the BOM.

## Verification

- `./gradlew clean generatePomFileForBluetapeLeaderPublication --no-daemon --no-configuration-cache --no-build-cache`
- Generated BOM POM scan found no `examples`, `demo`, or `benchmark` entries.
