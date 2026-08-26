---
title: "Spring Actuator management action"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Spring Actuator management action

> Issue #532를 위한 unreleased 초안입니다. 이 문서는 API가 `sourceReleaseCommit`보다 새로 추가되어 pinned `0.5.0` 매뉴얼에 포함되지 않습니다. 이 API가 들어간 commit을 release manifest가 가리킨 뒤에만 versioned manual로 승격하세요.

## 범위

이 action surface는 등록된 single-leader lease를 별도 HTTP Actuator endpoint에서
해제합니다. 일반 `runIfLeader` 실행을 대체하지 않으며 force-unlock API도 아닙니다.
Group, strategic, route-runtime, scheduled 실행은 자동 등록하지 않습니다.

## 명시적 opt-in

기존 parent endpoint와 nested action property를 모두 켜야 합니다. 기본 동작은
fail-closed입니다.

```yaml
management:
  endpoint:
    leader-election:
      enabled: true
      actions:
        enabled: true
        timeout: 5s
  endpoints:
    web:
      exposure:
        include: health,leaderElection,leaderElectionActions
```

Spring relaxed binding은 `leader-election`과 `leaderElection`을 모두 인식하지만 새
파일에서는 kebab-case를 사용하세요. Write endpoint ID는 `leaderElectionActions`이고
경로는 `POST /actuator/leaderElectionActions/{lockName}`입니다. 기존 read-only
`leaderElection` endpoint와 JMX descriptor는 변하지 않습니다. Action endpoint는
JMX write operation이 아니라 `@WebEndpoint`입니다.

라이브러리는 `SecurityFilterChain`을 설치하지 않습니다. Actuator 인증, 권한,
network policy, audit 소유권은 애플리케이션에서 관리하세요.

## Registry 소유권

`LeaderManagementActionRegistry` bean이 없을 때만 auto-configuration이 5초 action
timeout의 bounded library-owned registry를 만들고 Spring context 종료 전에 drain합니다.
애플리케이션이 registry bean을 제공하면 이를 우선하며 observer, executor, scope,
registration token, lifecycle을 이 모듈이 교체하거나 닫지 않습니다.

애플리케이션이 소유한 lease 획득 경계에서 handle을 등록하세요. `LeaderManagementRegistration`
을 닫는 것은 idempotent하게 관리 대상 자격만 제거하며 lease 자체를 해제하지 않습니다.
Registry는 ownership pre-check, 조건부 release 한 번, post-check 순서로 결과를 만듭니다.

## 응답과 retry 계약

응답 body는 다음 세 field만 허용합니다.

```json
{
  "action": "RELEASE",
  "outcome": "RELEASED",
  "mutationAttempted": true
}
```

| Outcome family | HTTP | 자동 retry |
|---|---:|---|
| `RELEASED` | 200 | No |
| `INVALID_LOCK_NAME` | 400 | No |
| `NOT_REGISTERED` | 404 | No |
| `AMBIGUOUS`, `NOT_HELD`, `ACTION_IN_PROGRESS` | 409 | No |
| `ACTION_ADMISSION_REJECTED` | 429 | No |
| `OWNERSHIP_UNKNOWN`, `RELEASE_UNCONFIRMED`, `RELEASE_FAILED`, `REGISTRY_CLOSED` | 503 | No |
| `ACTION_TIMED_OUT` | 504 | No |

`ACTION_TIMED_OUT`은 release 시작 여부를 `mutationAttempted`에 반영합니다. Worker가
terminalize되기 전에는 재시도하지 마세요. `RELEASE_UNCONFIRMED`나 `RELEASE_FAILED`를
성공으로 바꾸지 말고, invalid selector는 registry mutation 전에 거부합니다.

## 안전한 rollout과 rollback

1. Code를 배포하는 동안 parent와 nested property를 모두 비활성으로 둡니다.
2. Canary에서 HTTP exposure에 `leaderElectionActions`가 없는지 확인합니다.
3. 내부 Actuator에 인증 정책을 적용한 뒤 nested property만 활성화합니다.
4. 알려진 lock 하나를 시험하고 allow-list body와 outcome metric을 확인합니다.
5. 경로나 outcome이 예상과 다르면 HTTP exposure에서 제거하고 `actions.enabled=false`로
   되돌립니다. 반복 release 요청을 복구 수단으로 사용하지 마세요.
6. Core outcome, quarantine count, backend diagnostics를 확인한 뒤 lease 소유 여부를
   판단합니다.

Log와 metric은 계속 정제된 값이어야 합니다. Endpoint 응답이나 management log에 lock
name, actor/credential 값, backend payload, token, exception 원문을 넣지 마세요.

## 승격 조건

Release manifest가 action API를 포함한 commit을 가리키고, 같은 commit의 Spring HTTP와
ABI test가 통과하며, 배포 runbook에 애플리케이션 소유 인증 정책이 명시될 때 이 초안을
versioned manual로 옮길 수 있습니다.
