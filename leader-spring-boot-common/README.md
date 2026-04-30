# leader-spring-boot-common

Spring Boot 3/4 통합 모듈이 공유하는 Boot 버전 독립 공통 모듈입니다.

## 역할

- `leader-spring-boot3`, `leader-spring-boot4` 양쪽에서 재사용
- Spring Boot 버전에 의존하지 않는 순수 Kotlin 코드만 포함
- `@ConfigurationProperties` 등 Boot 특화 어노테이션은 각 Boot 버전 모듈에서 선언

## 제공 클래스

### Properties

| 클래스 | 설명 |
|--------|------|
| `LeaderElectionProperties` | 리더 선출 설정 (`wait-time`, `lease-time`, `group.*`) |
| `LeaderGroupProperties` | 복수 리더 그룹 설정 (`max-leaders`, `wait-time`, `lease-time`) |

```kotlin
// Boot 3/4 AutoConfiguration에서 사용 예
@ConfigurationProperties(prefix = "leader")
class BootLeaderElectionProperties : LeaderElectionProperties()

// options 변환
val options: LeaderElectionOptions = properties.toOptions()
val groupOptions: LeaderGroupElectionOptions = properties.group.toOptions()
```

### Config Support

| 클래스 | 설명 |
|--------|------|
| `LeaderElectionConfigSupport` | AutoConfiguration 공통 추상 기반 클래스 |

## YAML 설정

```yaml
leader:
  wait-time: 5s
  lease-time: 60s
  group:
    max-leaders: 3
    wait-time: 5s
    lease-time: 60s
```

## 의존 관계

```
leader-spring-boot-common
  └── leader-core
```
