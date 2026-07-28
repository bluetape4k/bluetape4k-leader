# 문제 247 수명 주기 AtomicFU

## 맥락

`LeaderLeaseAutoExtenderLifecycle`는 가상 스레드 인식 Spring Boot 모듈의 일부입니다. 수명 주기 잠금은 이미 현재 `develop`에서 `ReentrantLock.withLock`로 이동되었지만 클래스 수준 수명 주기 카운터는 여전히 `java.util.concurrent.atomic.*`를 사용했습니다.

## 결정

인스턴스 `registered` 플래그와 동반 `activeContextCount`에 `kotlinx.atomicfu.atomic`를 사용하여 기존 `ReentrantLock.withLock` 수명 주기 중요 섹션을 변경하지 않고 유지합니다.

## 결과

수명 주기 클래스는 더 이상 `java.util.concurrent.atomic.*`를 가져오지 않으며 `synchronized {}`를 사용하지 않습니다. 기존 멱등성 및 다중 컨텍스트 동작은 변경되지 않습니다.

## 검증

- `./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.LeaderLeaseAutoExtenderLifecycleTest' --no-configuration-cache --console=plain`
- 결과: 6개 테스트를 통과하고 빌드에 success했습니다.
- `git diff --check`
- `rg "java\\.util\\.concurrent\\.atomic|synchronized\\(" leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/boot/LeaderLeaseAutoExtenderLifecycle.kt`는 일치하는 항목을 반환하지 않았습니다.

## 미래 노트

가상 스레드 인식 Spring 코드의 클래스 수준 수명 주기 또는 공유 상태의 경우 원자푸와 명시적 잠금을 선호합니다. Java 원자는 로컬 테스트 카운터 및 가상 스레드를 인식하지 않는 표면에 허용됩니다.
