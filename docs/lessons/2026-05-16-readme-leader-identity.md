# 단원: leader-core README에 LeaderId ID 섹션 추가

**날짜**: 2026-05-16 **문제**: #211 **홍보**: #279

## 근본 원인

PR #209는 최대 7,600줄의 리더 ID API(`LeaderIdProvider`, `LeaderSlot`, Redis 감사 전파)를 추가했지만 `leader-core/README.md` 및 `leader-core/README.ko.md`는 업데이트되지 않았습니다. `grep -i 'identity|LeaderIdProvider|LeaderSlot'`는 두 파일 모두에서 0개의 히트를 반환했습니다.

## 변경사항

다음을 다루는 두 README 파일에 `## Leader Identity` / `## 리더 Identity` 섹션을 추가했습니다.

1. **`LeaderIdProvider` 인터페이스** — 계약(발생하지 않음, 차단하지 않음, 스레드로부터 안전함, 비어 있지 않음)
2. **내장 제공자** — `RandomLeaderIdProvider`, `HostnamePidLeaderIdProvider`, `CompositeLeaderIdProvider`가 포함된 테이블; `HostnamePid`에 대한 PII 경고 포함
3. **`LeaderIdSource` 열거형** — 출처 태그 테이블(`LITERAL`, `SPEL`, `PROPERTY`, `AUTO`)
4. **`LeaderSlot`** — LeaderId가 `runIfLeader`에 결합되는 방식, 이벤트 전파, 결과 액세스
5. **사용자 지정 공급자 예** — 세 가지 변형(무작위, 호스트 이름+PID, 테넌트 접두사)
6. **Redis 백엔드 감사 테이블** — Lettuce `lg:{lockName}:meta` 해시 대 Redisson `lg:{lockName}:audit` RMap; 외부 리퍼 없이 TTL 기반 자동 회수

## 향후 지침

PR이 새로운 공개 API 표면(>500줄 삽입)을 추가하는 경우 PR 체크리스트에는 다음이 포함되어야 합니다.

> [ ] 모든 새로운 공개 유형에 대해 README.md + README.ko.md가 업데이트되었습니다.

이는 PR 템플릿과 CLAUDE.md 코드 변경 체크리스트에 추가되어야 합니다.
