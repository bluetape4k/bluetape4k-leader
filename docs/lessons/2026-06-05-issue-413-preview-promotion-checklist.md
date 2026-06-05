# Issue 413 Preview Promotion Checklist

## Context

Consul, DynamoDB, etcd, Kubernetes Lease 는 README 에서 Preview 로 남아 있지만
full Nightly release gate 와 benchmark/example 증거는 여러 이슈에 흩어져
있었다.

## Decision or Finding

Nightly 성공은 release preflight 증거로 유지하고, Stable 전환 기준은 별도
`docs/release/preview-backend-stable-promotion.md` 문서로 분리했다. 전환은
backend 별로 판단하며, green Nightly 만으로 README status 를 바꾸지 않는다.

## Outcome

각 preview backend 의 runtime, API, docs, examples, benchmark, release
governance 기준과 0.4.0 현재 blocker 를 한 곳에서 볼 수 있게 됐다.

## Verification

- `git diff --check`
- targeted `rg` for the new promotion document, release gate link, issue #423,
  issue #480, and latest successful full Nightly URL.

## Future Guidance

Preview backend 를 Stable 로 바꾸는 PR 은 backend 별로 만들고, README locale
set 변경과 함께 full Nightly summary, API/KDoc review, example/docs evidence 를
링크한다. drive-by docs PR 에서 status row 만 바꾸지 않는다.
