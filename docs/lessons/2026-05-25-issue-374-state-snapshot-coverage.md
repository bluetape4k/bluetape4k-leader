# 문제 374 상태 스냅샷 범위

컨텍스트: Issue #374에서는 0.2.2 릴리스 이전에 Consul, DynamoDB 및 etcd 전반의 상태 스냅샷에 대한 미리보기 백엔드 회귀 적용 범위가 필요했습니다.

결정: 스냅샷 충실도는 백엔드마다 다르기 때문에 공유 계약 고정 장치 대신 백엔드별 통합 테스트를 추가합니다. Consul 및 DynamoDB 단일/그룹 스냅샷은 감사 ID, 물리적 노드 ID 및 임대 기간을 노출합니다. etcd 그룹 스냅샷은 백엔드 소유권 토큰과 슬롯을 노출하는 반면, etcd 단일 리더 `state()`는 소유자 메타데이터가 현재 구현에 의해 노출되지 않기 때문에 기본 빈 스냅샷으로 유지됩니다.

결과: 새로운 테스트는 빈 상태, 점유 상태, 백엔드가 제공할 수 있는 ID 필드, 그룹의 슬롯 상태, 상태 읽기가 소유권을 해제하지 않거나 다른 후보가 조기에 획득하도록 허용하지 않는지 검증하는 보유자/경쟁자 검사를 다룹니다.

검증: `git diff --check`; 24개의 DynamoDB, 54개의 Consul 및 63개의 etcd 테스트를 통과한 `./gradlew :bluetape4k-leader-consul:test :bluetape4k-leader-dynamodb:test :bluetape4k-leader-etcd:test --rerun-tasks --no-daemon`; 최종 검토 정리 후 DynamoDB 및 etcd에 대한 집중 재실행. Claude 아티팩트: `.omx/artifacts/claude-issue-374-state-snapshot-20260525120713.md` 및 `.omx/artifacts/claude-issue-374-state-snapshot-final-20260525121050.md`, 둘 다 P0=0 및 P1=0입니다.

미래 보호: 백엔드 전체에서 상태 스냅샷 패리티를 가정하지 마세요. 테스트에서는 null이 아닌 검사 뒤에 숨기는 대신 백엔드별 제한 사항을 명시적으로 명명하고 주장해야 합니다.
