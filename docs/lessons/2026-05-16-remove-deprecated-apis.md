# 교훈: 0.1.0 이전의 @Deprecated API를 모두 제거하세요.

**날짜**: 2026-05-16 **문제**: #264 **PR**: TBD

## 근본 원인

10개의 API는 문서화된 마이그레이션 경로를 통해 개발 중에 더 이상 사용되지 않습니다. 0.1.0에 게시하면 복잡한 공개 API 표면이 생성되고 첫날부터 이전 버전과의 호환성을 유지해야 합니다.

## 제거된 항목

| Item | Type | Action |
|------|------|--------|
| `LeaderLease.leaderId` | deprecated field | Removed; callers use `auditLeaderId` |
| `LeaderLeaseAutoExtender.start(Boolean lambda)` | deprecated overload | Removed; callers use `ExtendDelegate` form |
| `HistoryStatus.kt` typealias | deprecated file | Removed; callers use `LeaderHistoryStatus` |
| `RetryStrategy.kt` typealias | deprecated file | Removed (zero callers) |
| `ExposedJdbcGroupLock.extend()` | deprecated method | Removed; no production callers |
| `ExposedJdbcLock.extend()` | deprecated method | Removed; no production callers |
| `MongoLock.extend()` | deprecated method | Removed; no callers |
| `MongoSuspendLock.extend()` | deprecated method | Removed; no callers |
| `LettuceSemaphore` class | deprecated entire class | Removed + test file deleted |
| `LettuceSuspendSemaphore` class | deprecated entire class | Removed + test file deleted |

## 마이그레이션 노트

- `LettuceSemaphore` → `LettuceLeaderGroupElector` 사용(슬롯 토큰 TTL 모델)
- `LettuceSuspendSemaphore` → `LettuceSuspendLeaderGroupElector` 사용
- `LeaderLease.leaderId` → `LeaderLease.auditLeaderId` 사용
- `HistoryStatus` → `LeaderHistoryStatus` 사용

## 주요 결정

- 더 이상 사용되지 않는 코드만 테스트한 테스트가 삭제되었습니다(비활성화뿐만 아니라).
- 더 이상 사용되지 않는 API를 사용한 테스트가 새 API로 마이그레이션되었습니다.
- 테스트 파일 3개 업데이트, 테스트 파일 1개(LettuceSemaphore 테스트) 삭제됨

## 검증

- `./gradlew assemble` → BUILD SUCCESSFUL(76개 작업)
- `./gradlew :leader-core:test :leader-exposed-core:test` → 빌드 success

## 향후 지침

공개 API에 `@Deprecated`를 추가하기 전에:
1. 교체품이 생산 준비가 되었는지 검증
2. 지원 중단 메시지에 명시적인 제거 마일스톤 설정
3. 마일스톤에서 제거 - 더 이상 사용되지 않는 API가 릴리스 전체에 누적되지 않도록 하세요.
