# Issue 416 Spring AOP Guidance Sync

## Context

README 문서는 `@LeaderElection` 이 `Flux` 와 Kotlin `Flow` 를 지원한다고
설명하지만, repo-local agent guidance 는 예전 `Flux` / `Flow` 미지원 문구를
유지하고 있었다.

## Decision or Finding

runtime 변경 없이 agent-facing guidance 만 현재 Spring AOP 계약과 맞췄다.
단일 리더 stream 은 지원하지만, `@LeaderGroupElection` 의 `Flux` / `Flow`
stream 은 slot 별 lease-extension 의미가 정의되지 않아 거부된다는 경계를
명시했다.

## Outcome

`AGENTS.md`, `CLAUDE.md`, `README.md`, `README.ko.md` 의 return-type 설명이
같은 방향을 가리키게 됐다.

## Verification

- `rg -n "Flux|Flow|unsupported|미지원" AGENTS.md CLAUDE.md README.md README.ko.md`
- `git diff --check`

## Future Guidance

Spring AOP guidance 를 수정할 때는 단일 리더 stream 지원과 group stream 거부를
같이 확인한다. `CompletableFuture` 계열 차단 규칙은 stream 지원과 별개의
split-brain 방어 규칙이다.
