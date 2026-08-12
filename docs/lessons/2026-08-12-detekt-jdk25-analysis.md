# JDK 25 Detekt 분석 경로 정렬

## 증상

JDK 25와 Kotlin JVM target 25로 전환한 뒤 기존 Detekt 플러그인이 분석을
시작하지 못했다. `detekt` 실행은 다음 오류로 중단됐다.

```text
Invalid value (25) passed to --jvm-target, must be one of
[1.6, 1.8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22]
```

기존 `io.gitlab.arturbosch.detekt` 1.23.8 경로에서 분석 compiler를
Kotlin 2.0.21로 고정해도 JDK 25 런타임의 Kotlin parser가 `25.0.4`를
처리하지 못했다.

## 결정

- Detekt 2.0.0-alpha.5의 `dev.detekt` 플러그인으로 전환한다.
- production 대상은 계속 `ignoreFailures = false`로 유지한다.
- 모든 모듈의 baseline을 Detekt 2 형식으로 재생성한다.
- Java/Kotlin compile 및 `.java-version`의 JDK target 25는 낮추지 않는다.

## 검증

```bash
./gradlew detekt --no-configuration-cache --no-daemon --console=plain
```

전체 production 모듈과 examples의 Detekt 작업이 JDK 25에서 성공하고,
기존 baseline 위반은 새 형식으로 계속 억제되는지 확인한다.
