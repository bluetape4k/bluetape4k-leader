# 문제 74 Flux / Flow AOP

## 맥락

`@LeaderElection`는 이미 동기화, 일시 중지 및 `Mono` 방법을 지원했지만 `Flux` 및 Kotlin `Flow`는 스트림이 초기 리스 시간보다 오래 지속될 수 있기 때문에 거부되었습니다.

## 결정

단일 리더 `@LeaderElection`에 대해서만 스트림 반환을 지원하며 두 가지 명시적인 안전 신호 중 하나가 필요합니다.

- 장기 실행 또는 무제한 스트림을 위한 `autoExtend = true`.
- 리스 시간 내에 완료되는 것으로 알려진 스트림의 경우 `streamBounded = true`입니다.

그룹 리스 연장 의미 체계가 정의될 때까지 `@LeaderGroupElection` 스트림 반환이 지원되지 않는 상태로 유지됩니다.

## 결과

이제 애스펙트는 `Flux` 및 `Flow`를 느리게 래핑하므로 구독 또는 수집 시 잠금이 획득되고 완료, 오류 또는 취소 시 해제됩니다. `Flow`는 Flow 컨텍스트 보존 위반을 방지하기 위해 랑데뷰 버퍼링과 함께 `channelFlow`를 사용합니다.

유효성 검사기는 안전하지 않은 스트림 서명을 조기에 거부하는 반면, 시작 유효성 검사가 비활성화된 경우 런타임 래퍼는 구독 또는 수집 시간에 여전히 실패합니다.

측면 테스트에서는 `autoExtend=true`가 스트림 서명을 활성화하고 일반 `LeaderElectionOptions` 경로를 통해 옵션을 전달하는지 검증합니다. 실제 감시 리스 개선은 Spring AOP 단위 테스트 내에서 실제 선출기를 구성하는 대신 핵심 선출기 자동 확장 테스트를 통해 계속해서 다루어집니다.

## 검증

- `./gradlew :leader-spring-boot:compileTestKotlin --no-configuration-cache --console=plain`
- `./gradlew :leader-spring-boot:test --tests '*LeaderElectionAspectStreamTest*' --tests '*LeaderGroupElectionAspectSuspendMonoTest*' --tests '*LeaderAnnotationValidatorBeanPostProcessorTest*' --no-configuration-cache --console=plain`

## 퓨쳐 가드

`leader-spring-boot` 테스트에서 `LocalSuspendLeaderElector`를 직접 구성하여 AOP 스트림 `autoExtend`를 테스트하지 마세요. 모듈의 테스트 런타임은 측면 계약과 관련 없는 코루틴 바이너리 드리프트를 노출할 수 있습니다. 지연 획득, 취소 릴리스, 런타임 검증 및 유효성 검사기 정책에 AOP 테스트를 집중시키고 핵심 선출기 계약 테스트에서 실제 리스 연장을 다룹니다.
