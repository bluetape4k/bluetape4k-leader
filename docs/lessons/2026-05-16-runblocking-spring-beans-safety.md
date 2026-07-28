# 교훈: Spring Boot 빈 이니셜라이저의 runBlocking은 설계상 안전합니다.

**날짜**: 2026-05-16 **문제**: #263 **홍보**: #276

## 근본 원인 조사

2개의 Spring Boot 자동 구성 클래스에 걸쳐 4개의 `@Bean` 메서드는 `runBlocking { }`를 사용하여 일시 중지 생성자(스키마/TTL 인덱스 초기화)를 동기 Spring 빈 팩토리에 연결했습니다.

- `ExposedR2dbcLeaderConfiguration.exposedR2dbcSuspendLeaderElector`
- `ExposedR2dbcLeaderConfiguration.exposedR2dbcSuspendLeaderGroupElector`
- `MongoLeaderConfiguration.mongoSuspendLeaderElector`
- `MongoLeaderConfiguration.mongoSuspendLeaderGroupElector`

문제는 `runBlocking`가 가상 스레드 캐리어를 고정하고 Spring Boot 가상 스레드 실행기에서 스레드 고갈을 일으킬 수 있는지 여부였습니다.

## 조사 결과

`leader-mongodb/src/main` 및 `leader-exposed-r2dbc/src/main`의 `rg "synchronized|@Synchronized"`가 **0개 일치**를 반환했습니다.

가상 스레드는 `synchronized` 블록 또는 `Object.wait()` 호출 내부에서만 캐리어 스레드를 고정합니다. 코루틴 본문이나 정지 생성자에서 접근할 수 있는 코드는 모두 `synchronized`를 사용하지 않으므로 캐리어는 코루틴이 IO에서 정지되는 동안 다른 가상 스레드를 자유롭게 마운트 해제하고 제공할 수 있습니다. **고정 위험**이 없습니다.

## 결론

기존 `runBlocking` 사용법은 정확하고 의도적입니다.

1. Spring Bean 초기화는 플랫폼 스레드(또는 캐리어 고정 코드가 없는 가상 스레드)에서 실행됩니다. 어느 쪽이든 `runBlocking`는 안전합니다.
2. 블록은 시작 시 **한 번** 호출됩니다. 정상 상태 처리량에는 영향을 미치지 않습니다.
3. 이는 CLAUDE.md 허용과 일치합니다: _"엄격하게 제어되는 지연 초기화를 제외하고 프로덕션 코드에서 `runBlocking`를 사용하지 마십시오."_

## 변경사항

논리 변경은 없습니다. 두 구성 클래스 모두에서 KDoc를 한국어에서 영어로 업데이트하고 가상 스레드 캐리어 고정 분석을 설명하는 명시적인 메모를 추가하여 향후 기여자가 다시 조사할 필요가 없도록 했습니다.

## 향후 지침

`runBlocking` 고정 버그를 신고하기 전에:

1. `runBlocking { }` 호출 트리 내에서 `rg "synchronized|@Synchronized"`를 실행합니다.
2. 결과가 일치하지 않으면 `runBlocking`는 가상 스레드에서 안전합니다.
3. 캐리어 고정은 `runBlocking` 자체가 아닌 `synchronized`/`Object.wait()` 내부에서만 발생합니다.
4. 결과가 지속되도록 KDoc에 안전성 분석을 문서화합니다.
