# Lesson: issue #425 Redisson 워치독 예시

## 맥락

Milestone 0.3.0에는 임대 자동 연장으로 보호되는 장기 실행 리더 전용 작업을 위해 실행 가능한 Redisson 예제가 필요했습니다.

## 결정

Testcontainers Redis 예로 `examples/redisson-watchdog`를 추가합니다. 예제에서는 현재 구현을 정확하게 문서화합니다. Redisson 잠금은 명시적 임대를 통해 획득되고 bluetape4k `LeaderLeaseAutoExtender`는 해당 임대를 갱신합니다.

## 결과

이 예시에서는 초기 임대 이후에 실행되는 리더 작업을 시작하고, 임대가 갱신되는 동안 경쟁자가 건너뛰는지 검증하고, 해제 후 재획득을 검증합니다.

## 검증

PR을 열기 전에 `:examples:redisson-watchdog:test`, `:examples:redisson-watchdog:run`, `./gradlew projects`, `actionlint` 및 `git diff --check`를 실행하십시오.

## 미래의 규칙

현재 Redisson 선택기를 Redisson의 암시적 기본 잠금 감시 장치를 사용하는 것으로 설명하지 마세요. 해당 경로는 명시적인 `leaseTime`를 전달하여 의도적으로 비활성화됩니다. 예제와 문서에서는 이를 bluetape4k 임대 자동 연장이라고 불러야 합니다.
