# Spring Boot 통합 자료 조사

> 작성: 2026-04-30  
> 목적: `leader-spring-boot3` / `leader-spring-boot3-starter` (및 Boot 4 대응) 모듈 설계 검토

---

## 1. 결론 요약

Spring Boot 공식 가이드 기준으로 라이브러리는 **autoconfigure 모듈 + starter 모듈**로 분리한다.

| 모듈 | 역할 | 코드 여부 |
|------|------|-----------|
| `leader-spring-boot3` | `@AutoConfiguration`, 조건부 Bean, `@ConfigurationProperties` | 있음 |
| `leader-spring-boot3-starter` | 의존성 집합 — `leader-spring-boot3` + 선택 백엔드 묶음 | 없음 (pom only) |
| `leader-spring-boot4` | Boot 4 전용 autoconfigure (구조 변경 대응) | 있음 |
| `leader-spring-boot4-starter` | Boot 4 의존성 집합 | 없음 (pom only) |

Boot 3/4 간 **공통 로직은 `leader-spring-boot-common` 내부 모듈**로 추출해 양쪽에서 재사용한다.

---

## 2. autoconfigure vs starter 분리 근거

### Spring Boot 공식 naming convention

> "Do not start your module names with `spring-boot`. Use `acme-spring-boot-autoconfigure` and `acme-spring-boot-starter`."

- **autoconfigure 모듈**: 실제 `@AutoConfiguration` 코드 + `@Conditional` 로직
- **starter 모듈**: `build.gradle.kts`만 존재, autoconfigure + 의존성 묶음
- 소규모 라이브러리라면 합칠 수 있으나, **배포 단위 분리가 필요하면 반드시 분리**

### 사용 패턴

```kotlin
// 방법 1: 기본 스타터 (autoconfigure + 원하는 백엔드 포함)
implementation("io.github.bluetape4k.leader:leader-spring-boot3-starter")
implementation("io.github.bluetape4k.leader:leader-redis-redisson")

// 방법 2: autoconfigure만 (백엔드 직접 지정)
implementation("io.github.bluetape4k.leader:leader-spring-boot3")
implementation("io.github.bluetape4k.leader:leader-redis-lettuce")
```

> **설계 결정**: starter에 기본 백엔드 포함 여부.  
> ShedLock 방식 = starter는 autoconfigure만, 백엔드별 별도 artifact.  
> **권장**: ShedLock 패턴 따르기 — starter는 `leader-spring-boot3`만 포함.

---

## 3. ⚠️ Spring Boot 4 주요 변경사항

### 3.1 autoconfigure 모듈 분산

Boot 3과 Boot 4의 가장 큰 구조적 차이.

| | Boot 3 | Boot 4 |
|---|---|---|
| 패키지 | `org.springframework.boot.autoconfigure.*` | `org.springframework.boot.*.autoconfigure` |
| 아티팩트 | `spring-boot-autoconfigure` 단일 jar | 기능별 별도 artifact로 분산 |

Boot 4 패키지 예시:
```
org.springframework.boot.jackson.autoconfigure
org.springframework.boot.session.autoconfigure
org.springframework.boot.security.autoconfigure
org.springframework.boot.transaction.autoconfigure
org.springframework.boot.webmvc.autoconfigure
```

**이 프로젝트 영향**: `leader-spring-boot4`에서 `spring-boot-autoconfigure` 단일 의존 불가.  
필요한 autoconfigure artifact만 개별 선언해야 함.

```kotlin
// leader-spring-boot3/build.gradle.kts
api(Libs.spring_boot_autoconfigure)  // 단일 artifact — 변경 없음

// leader-spring-boot4/build.gradle.kts
// spring-boot-autoconfigure가 분산됨 → 필요한 것만 선언
compileOnly("org.springframework.boot:spring-boot-context")       // @ConfigurationProperties
compileOnly("org.springframework.boot:spring-boot-autoconfigure") // @AutoConfiguration (core 잔류분)
// 실제 Boot 4 GA 출시 후 artifact 명 재확인 필요
```

### 3.2 AOP → AspectJ 전환

Boot 3까지: 기본 CGLib proxy-based AOP, `spring-aop` 의존.  
Boot 4: **AspectJ가 클래스패스에 있으면 자동으로 AspectJ auto proxy 활성화** (`@EnableAspectJAutoProxy` 불필요).

```
spring.aop.proxy-target-class=false  # JDK 프록시로 변경 가능 (Boot 3/4 공통)
```

**`@Leader` AOP 어노테이션 구현 시 영향**:

| | Boot 3 | Boot 4 |
|---|---|---|
| Aspect 구현 방식 | `spring-aop` (CGLib) 기반 | `spring-aspects` (AspectJ) 기반 권장 |
| `@Aspect` 어노테이션 | `spring-aop`에 포함 | AspectJ 런타임 필요 |
| 의존성 | `spring-aop` | `spring-aspects` + AspectJ weaver |
| 구현 복잡도 | 낮음 | 높음 |

→ `@Leader` AOP는 **Boot별 분기 구현 필요**. 공통 모듈(`leader-spring-boot-common`)에서 추상화.

---

## 4. AutoConfiguration 등록 방식

Boot 2.x의 `META-INF/spring.factories` → Boot 3/4 공통:

```
# 파일 위치 (Boot 3 및 Boot 4 동일)
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

# 내용
io.bluetape4k.leader.spring.autoconfigure.LeaderElectionAutoConfiguration
```

`@AutoConfiguration` 어노테이션 사용 (Boot 3/4 공통).

---

## 5. 제공 기능 목록

### 5.1 `leader-spring-boot-common` (내부 공유 모듈)

Boot 3/4 양쪽에서 재사용하는 버전 독립적 공통 로직:

```
leader-spring-boot-common/
  src/main/kotlin/io/bluetape4k/leader/spring/
    properties/
      LeaderElectionProperties.kt   ← @ConfigurationProperties 없이 plain data class
      LeaderGroupProperties.kt
    support/
      LeaderElectionConfigSupport.kt  ← 공통 Bean 생성 헬퍼 (abstract)
```

`leader-spring-boot3` / `leader-spring-boot4`에서 `leader-spring-boot-common`을 `implementation`으로 의존.

### 5.2 `leader-spring-boot3` (autoconfigure)

#### Bean 자동 등록 (백엔드별 조건부)

```kotlin
@AutoConfiguration
@EnableConfigurationProperties(LeaderElectionProperties::class)
class LeaderElectionAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.redisson.api.RedissonClient"])
    @ConditionalOnMissingBean(SuspendLeaderElection::class)
    class RedissonConfig {
        @Bean
        fun redissonSuspendLeaderElection(
            client: RedissonClient,
            props: LeaderElectionProperties,
        ): SuspendLeaderElection = RedissonSuspendLeaderElection(client, props.toOptions())
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["io.lettuce.core.RedisClient"])
    @ConditionalOnMissingBean(LeaderElection::class)
    class LettuceConfig {
        @Bean
        fun lettuceLeaderElection(
            client: RedisClient,
            props: LeaderElectionProperties,
        ): LeaderElection = LettuceLeaderElection(client, props.toOptions())
    }

    // Exposed-JDBC, Exposed-R2DBC, MongoDB, Hazelcast 동일 패턴
}
```

> `@ConditionalOnClass(RedissonClient::class)` 대신 `@ConditionalOnClass(name = ["..."])` 사용.  
> 클래스 리터럴은 컴파일 타임에 클래스가 없으면 NoClassDefFoundError 발생.

#### ConfigurationProperties

```yaml
# application.yml
leader:
  wait-time: 5s
  lease-time: 60s
  group:
    max-leaders: 3
    wait-time: 5s
    lease-time: 60s
```

```kotlin
@ConfigurationProperties(prefix = "leader")
data class LeaderElectionProperties(
    val waitTime: Duration = 5.seconds,
    val leaseTime: Duration = 60.seconds,
    val group: LeaderGroupProperties = LeaderGroupProperties(),
) {
    fun toOptions() = LeaderElectionOptions(waitTime = waitTime, leaseTime = leaseTime)
}

data class LeaderGroupProperties(
    val maxLeaders: Int = 1,
    val waitTime: Duration = 5.seconds,
    val leaseTime: Duration = 60.seconds,
) {
    fun toOptions() = LeaderGroupElectionOptions(
        maxLeaders = maxLeaders,
        waitTime = waitTime,
        leaseTime = leaseTime,
    )
}
```

#### @Scheduled 통합

**방안 A — AOP `@Leader` 어노테이션** (후순위, Boot 3/4 구현 분기 필요):
```kotlin
@Scheduled(cron = "0 0 2 * * *")
@Leader("daily-settlement")          // AOP intercept → runIfLeader 래핑
suspend fun dailySettlement() { ... }
```

**방안 B — 직접 `runIfLeader` 호출** (초기 구현 권장):
```kotlin
@Scheduled(fixedDelay = 60_000)
suspend fun run() {
    leaderElection.runIfLeader("daily-job") { doWork() }
}
```

방안 B = `leader-core` API만으로 구현 가능. 방안 A = Boot 3/4 각각 AOP 구현 필요. **초기에는 방안 B**.

#### Actuator 통합 (선택, 낮은 우선순위)

- `/actuator/leader` — 리더 상태, 보유 락 목록
- `leader-micrometer` 모듈과 연계

### 5.3 `leader-spring-boot3-starter` / `leader-spring-boot4-starter`

코드 없음. `build.gradle.kts`만:

```kotlin
// leader-spring-boot3-starter/build.gradle.kts
dependencies {
    api(project(":leader-spring-boot3"))
    // starter는 백엔드 미포함 — ShedLock 패턴
    // 사용자가 원하는 backend 모듈 직접 선언
}
```

---

## 6. 모듈 구조 최종안

```
leader-spring-boot-common/          ← (신규) Boot 버전 독립 공통 로직
  build.gradle.kts
  src/main/kotlin/...properties/    ← plain data class (no @ConfigurationProperties)
  src/main/kotlin/...support/       ← abstract config support

leader-spring-boot3/                ← Boot 3 autoconfigure
  build.gradle.kts
  src/main/kotlin/...autoconfigure/
    LeaderElectionAutoConfiguration.kt
    LeaderElectionProperties.kt     ← @ConfigurationProperties(prefix="leader")
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports

leader-spring-boot3-starter/        ← Boot 3 starter (pom only)
  build.gradle.kts

leader-spring-boot4/                ← Boot 4 autoconfigure (autoconfigure artifact 분산 대응)
  build.gradle.kts
  src/main/kotlin/...autoconfigure/
    LeaderElectionAutoConfiguration.kt  ← Boot 4 패키지/artifact 기준으로 재작성
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports

leader-spring-boot4-starter/        ← Boot 4 starter (pom only)
  build.gradle.kts
```

### 의존성 구조

```
leader-spring-boot3         → leader-spring-boot-common
leader-spring-boot4         → leader-spring-boot-common
leader-spring-boot3-starter → leader-spring-boot3
leader-spring-boot4-starter → leader-spring-boot4
```

---

## 7. 구현 우선순위 / 선행 조건

| 순서 | 항목 | Boot | 비고 |
|------|------|------|------|
| 1 | `leader-spring-boot-common` 모듈 생성 | 공통 | properties data class + abstract support |
| 2 | `LeaderElectionAutoConfiguration` (Redisson/Lettuce) | 3 | `@ConditionalOnClass(name=...)` 패턴 |
| 3 | `AutoConfiguration.imports` 등록 | 3 | |
| 4 | `leader-spring-boot3-starter` 빌드 설정 | 3 | 코드 없음 |
| 5 | Boot 4 autoconfigure artifact 명 확인 | 4 | GA 출시 후 재확인 |
| 6 | `leader-spring-boot4` — Boot 4 패키지 기준 재작성 | 4 | artifact 분산 대응 |
| 7 | `leader-spring-boot4-starter` 빌드 설정 | 4 | 코드 없음 |
| 8 | starter 기본 백엔드 포함 여부 최종 결정 | 공통 | ShedLock 패턴 = 미포함 권장 |
| 9 | (선택) `@Leader` AOP — Boot 3: spring-aop, Boot 4: spring-aspects | 분기 | 후순위 |
| 10 | (선택) Actuator `/actuator/leader` | 3/4 | `leader-micrometer` 연계 |

---

## 8. 참고 — ShedLock 비교

| | ShedLock | bluetape4k-leader |
|---|---|---|
| Spring Boot 통합 | `shedlock-spring` (autoconfigure) | `leader-spring-boot3` + `leader-spring-boot4` |
| Starter | `shedlock-spring-starter` (백엔드별) | `leader-spring-boot3-starter` (백엔드 미포함) |
| 어노테이션 | `@SchedulerLock` | `@Leader` (후순위) |
| Coroutines | 부분 지원 | 코어 인터페이스 (`SuspendLeaderElection`) |
| Boot 4 대응 | 미확인 | `leader-spring-boot4` + autoconfigure 분산 대응 |

---

## 9. 참고 링크

- [Spring Boot: Creating Your Own Starter (3.5)](https://docs.spring.io/spring-boot/3.5/reference/features/developing-auto-configuration.html)
- [Boot 4 Snapshot API — autoconfigure packages](https://docs.spring.io/spring-boot/4.0-SNAPSHOT/api/java/org/springframework/boot/context/properties/class-use/ConfigurationProperties.html)
- [Boot 4 AOP auto-configuration](https://docs.spring.io/spring-boot/reference/features/aop.html)
- [ShedLock GitHub](https://github.com/lukas-krecan/ShedLock)
