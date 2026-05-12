# Lessons Learned — CI workflow expression escaping (2026-05-12)

## Context

`ci.yml` failed before creating any jobs after the `leader-exposed-r2dbc`
workflow jobs were added. GitHub reported:

```text
Unexpected symbol: '\'leader-exposed\''
```

The broken lines used shell/Python-style escaped quotes inside GitHub Actions
expressions:

```yaml
if: ${{ needs.changes.outputs[\'leader-exposed\'] == \'true\' }}
```

## Decision

Use normal GitHub Actions expression quoting:

```yaml
if: ${{ needs.changes.outputs['leader-exposed'] == 'true' }}
```

Do not escape single quotes in `${{ ... }}` expressions or YAML scalar values
unless the chosen YAML quoting style requires it.

## Outcome

The malformed expression was corrected in the three `leader-exposed-r2dbc`
CI jobs, along with the same unnecessary escaping in artifact paths.

## Verification

- `actionlint .github/workflows/ci.yml`
- `actionlint .github/workflows/nightly.yml`
- `git diff --check`
- `rg -n "\\\\'" .github/workflows` returned no matches

## Future Rule

Any CI workflow edit must run `actionlint` before merge. A zero-second GitHub
Actions failure with no jobs usually means workflow parsing or validation failed,
not a Gradle/test failure.
