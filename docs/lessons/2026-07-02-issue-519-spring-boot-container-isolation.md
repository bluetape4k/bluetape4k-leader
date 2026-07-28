# 배운 교훈 - 문제 519 Spring Boot 테스트 컨테이너 격리(2026-07-02)

**관련 문제**: #519 **영향을 받는 모듈**: `bluetape4k-leader-spring-boot`

## L1: 오류 주입 테스트에는 공유 백엔드 클라이언트가 포함되어서는 안 됩니다.

### 문제

`FailOpenRunIntegrationTest`는 Redis/ToxiProxy 오류 주입 경로를 사용하여 `FAIL_OPEN_RUN` 동작을 검증했으며, 동일한 클래스는 동반 지연 속성을 통해 공유 `RedisServer.Launcher.redis` 클라이언트 및 연결도 유지했습니다. 나중에 Spring Boot 자동 구성 테스트에서는 정적 지연 속성에 Lettuce 및 Mongo 클라이언트도 캐시되었습니다. 전체 모듈 실행은 결함 주입 시나리오 이후 오래된 백엔드 연결 또는 시작되지 않은 공유 컨테이너 상태를 관찰할 수 있습니다.

### 레슨

싱글톤 Testcontainers 실행 프로그램을 백엔드 공급자로만 유지하세요. Spring 컨텍스트 또는 네트워크 오류 주입이 관련된 경우 정적 테스트 속성에서 변경 가능한 백엔드 클라이언트 또는 연결을 캐시하지 마세요. Spring 테스트 컨텍스트가 명시적인 삭제 메서드를 사용하여 Bean을 통해 클라이언트와 연결을 소유하도록 하고, 연결을 변경하는 오류 주입 시나리오에 대해 재사용이 불가능한 테스트 소유 컨테이너를 사용하도록 합니다.

### 검증

- `./gradlew :bluetape4k-leader-spring-boot:compileTestKotlin --no-daemon --console=plain --warning-mode all`
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.FailOpenRunIntegrationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.LettuceAopFactoryAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.MongoAopFactoryAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.RedissonAopFactoryAutoConfigurationTest' --no-build-cache --rerun-tasks --no-parallel --no-daemon --console=plain --warning-mode all`
- `./gradlew :bluetape4k-leader-spring-boot:test --no-build-cache --rerun-tasks --no-parallel --no-daemon --console=plain --warning-mode all`

### 퓨쳐 가드

테스트가 컨테이너 연결, 프록시 상태 또는 백엔드 프로세스 수명 주기를 변경하는 경우 공유 실행기 클라이언트를 재사용해서는 안 됩니다. 위험은 프로세스 내 스레드 안전성 경쟁이 아니라 외부 Testcontainers/Spring 컨텍스트 수명 주기 순서에 있기 때문에 `MultithreadingTester`는 이러한 오류 클래스에 대한 올바른 증거가 아닙니다. 대신 실제 Gradle Testcontainers가 실행되는지 검증하세요.
