# Issue 580 핫패스 비용 모델

문제: #580 마일스톤: 0.5.0

## Consul 그룹 획득

이 변경 이전에는 포화된 Consul 그룹 획득이 재시도할 때마다 모든 슬롯을 시도했습니다.

```text
remote acquire calls ~= maxLeaders * retryCount
```

새로운 정책은 `CONSUL_GROUP_SLOT_PROBE_LIMIT` 무작위 슬롯 프로브에 대한 각 재시도를 제한하고 다음 재시도 전에 지터링된 백오프를
사용합니다.

```text
remote acquire calls <= 3 * retryCount
```

위임 테스트는 `maxLeaders = 64` 및 포화된 가짜 클라이언트로 실행된 다음 모든 슬롯을 스캔하는 대신 획득 호출 수가 고정 프로브 예산 미만으로
유지된다고 주장합니다.

## 웹훅 폴러 클레임 경로

이제 폴러는 폴링 루프에 들어가기 전에 필수 MongoDB 인덱스를 생성합니다.

- `idx_webhook_claim_pending_created_at`: `(status, createdAt, attempts)`
- `idx_webhook_claim_expired_created_at`: `(status, createdAt, attempts, claimExpiresAt)`
- `idx_webhook_event_id`: 고유 `(eventId)`

인덱스 생성 실패는 이제 컬렉션 스캔 대체 시 자동으로 폴러를 떠나는 대신 시작에 실패합니다.

## 검증

```bash
./gradlew :bluetape4k-leader-consul:test \
  --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectorDelegationTest' \
  --tests 'io.bluetape4k.leader.consul.ConsulSuspendLeaderElectorDelegationTest' \
  :examples:webhook-poller:test \
  --tests 'io.bluetape4k.leader.examples.webhook.WebhookPollerTest' \
  --no-build-cache --rerun-tasks
```

- 결과: Consul delegation 테스트 `27 passing`, webhook poller 테스트 `11 passing`.
