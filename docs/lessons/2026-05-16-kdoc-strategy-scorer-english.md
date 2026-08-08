# 강의: 전략/득점자 공개 API를 위한 영어 KDoc

**날짜**: 2026-05-16 **문제**: #267 **홍보**: #277

## 근본 원인

`leader-core` 전략/득점자 하위 시스템의 9개 공개 클래스 및 인터페이스에는 한국어 KDoc가 있었습니다. 작업 공간 CLAUDE.md에는 새롭거나 의미 있게 변경된 공개 API를 위한 영어 KDoc가 필요합니다. 한국어 텍스트는 영어 우선 규칙이 채택되기 전에 작성되었습니다.

## 업데이트된 파일

| 파일 | 변경 |
|------|--------|
| `strategy/CandidateScorer.kt` | 한국어 → 영어; `## Behavior / Contract` 및 `## Example` 추가 |
| `strategy/ElectionStrategy.kt` | 한국어 → 영어; `## Behavior / Contract`, `## Built-in strategies` 추가 및 사용자 정의 전략 예제 갱신 |
| `strategy/scorers/IdleTimeScorer.kt` | 한국어 → 영어; `## Behavior / Contract` 및 `## Example` 추가 |
| `strategy/scorers/RecentSuccessScorer.kt` | 한국어 → 영어; 섹션 제목 변경 |
| `strategy/scorers/SuccessRateScorer.kt` | 한국어 → 영어 |
| `strategy/scorers/WeightedScorer.kt` | 한국어 → 영어; `## Behavior / Contract` 추가 |
| `strategy/strategies/FifoElectionStrategy.kt` | 한국어 → 영어; 탈락 사유 문자열도 변환 |
| `strategy/strategies/RandomElectionStrategy.kt` | 한국어 → 영어; 탈락 사유 문자열 변환 |
| `strategy/strategies/ScoredElectionStrategy.kt` | 한국어 → 영어; 탈락 사유 문자열 변환 |

참고: `ListeningLeaderElectors.kt` 및 `TenantScopedLeaderElectors.kt`에는 이미 완전한 영어 KDoc가 있으므로 변경할 필요가 없습니다.

## 제거 사유 문자열

`ElectionResult` 내부의 제거 이유 문자열은 호출자에게 노출되는 공개 감사 추적의 일부입니다. 또한 공개 `ElectionResult` 표면이 일관되도록 영어로 변환되었습니다.

- `"등록 시각 늦음"` → `"registered later"`
- `"nodeId 사전순 뒤"` → `"nodeId lexicographically after winner"`
- `"랜덤 선출 탈락"` → `"not selected by random election"`
- `"점수 미달"` → `"score below winner"`
- `"점수 동점"` → `"tied score — ranked lower by registeredAt/nodeId"`

## KDoc 형식 적용

공개 수업의 경우 CLAUDE.md 기준:
1. 한 줄 요약 문장입니다.
2. 불변 및 엣지 케이스를 나열하는 `## Behavior / Contract` 섹션.
3. `## Example` 또는 `## Example / Built-in strategies` Kotlin 코드 블록.
4. 매개변수의 의미가 명확하지 않은 `@property`/`@param`/`@return` 태그.

## 향후 지침

새로운 `ElectionStrategy` 또는 `CandidateScorer`를 추가하는 경우:
1. 처음부터 영어 KDoc를 작성하세요.
2. `## Behavior / Contract` 포함 — `ElectionStrategy`에는 결정론 불변이 필수입니다.
3. 일반적인 사용법을 보여주는 `## Example`를 포함합니다.
4. 사용자에게 표시되는 모든 문자열 리터럴(제거 이유, 로그 메시지)을 영어로 번역합니다.
