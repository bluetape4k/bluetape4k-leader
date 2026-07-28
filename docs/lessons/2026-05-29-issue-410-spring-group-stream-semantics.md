# 이슈 410 스프링 그룹 스트림 의미론

## 맥락

단일 리더 AOP는 구독 또는 컬렉션 수명주기 동안 잠금을 유지하여 `Flux` 및 Kotlin `Flow`를 지원합니다. 그룹 선출은 아직 수명이 긴 스트림에 대한 슬롯 범위 자동 확장을 정의하지 않습니다.

## 결정

`@LeaderGroupElection` `Flux<T>` 및 `Flow<T>`를 0.3.0의 범위에서 제외하세요. 엄격 모드에서 시작 유효성 검사에 실패해야 하며 메서드 본문을 호출하지 않고 구독 또는 수집 시 측면에서 다시 거부되어야 합니다.

## 결과

공개 KDoc 및 README 로케일 세트는 이제 구현과 일치합니다. 그룹 AOP는 동기화, 일시 중지 및 `Mono`를 지원하는 반면 그룹 스트림은 슬롯별 확장 의미 체계가 설계될 때까지 지원되지 않습니다.

## 검증

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-spring-boot:compileKotlin --no-daemon`가 통과되었습니다.
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspectSuspendMonoTest' --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest' --no-daemon`는 42개의 테스트를 통과했습니다.
- `git diff --check`가 통과되었습니다.

## 퓨쳐 가드

유효성 검사기를 완화하는 것만으로 그룹 `Flux`/`Flow`를 활성화하지 마십시오. 첫 번째 디자인 슬롯 범위 리스 연장, 취소 정리, 메트릭 및 장애 시 개방 의미.
