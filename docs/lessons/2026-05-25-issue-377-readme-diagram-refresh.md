# Issue 377 README Diagram Refresh

## Context

Milestone 0.2.2 needed README-facing diagrams refreshed from the current
`bluetape4k-leader` module layout. The root overview diagram still collapsed
new preview backends behind a `+2 more` placeholder and did not show the current
DynamoDB, etcd, Consul, and Kubernetes Lease modules explicitly.

## Decision

Refresh only the root README overview asset and preserve existing README image
paths. The repository-wide Mermaid-history generator was not used for the final
asset because it mismatched old Mermaid blocks to current README image links and
rewrote the single-leader sequence asset with the group-leader sequence source.

## Outcome

`root-readme-overview-01.svg` now enumerates the current module map:

- BOM and core APIs.
- Stable backend modules.
- Preview backends: DynamoDB, etcd, Consul, and Kubernetes Lease.
- Ktor, Micrometer, and Spring Boot integration modules.
- Current examples from the `examples/` directory.

The matching PNG was regenerated from the SVG with `rsvg-convert`.

## Verification

- `xmllint --noout docs/images/readme-diagrams/*.svg`
- `git diff --check`
- `rg -n 'docs/images/readme-diagrams/.*\.png' README.md README.ko.md`
- `sips -g pixelWidth -g pixelHeight docs/images/readme-diagrams/root-readme-overview-01.png`
- Visual inspection of `root-readme-overview-01.png`: no clipped labels or stale `+ more` placeholders.
- Worktree audit artifacts:
  - `.omx/artifacts/issue-377-audit-readme-diagrams-worktree.log`
  - `.omx/artifacts/issue-377-audit-readme-diagram-quality-worktree.log`

The global audit scripts still exit with existing findings in unrelated
non-root diagrams, such as class-diagram `interface` labels and pre-existing
K8s/Spring text issues. The changed root overview asset has no audit findings
in those worktree artifacts.

## Future Guidance

When regenerating README diagrams from historical Mermaid blocks, first verify
that each Mermaid source maps to the same current image link. If the count or
order does not match, stop and manually regenerate the affected asset instead
of accepting generator output.
