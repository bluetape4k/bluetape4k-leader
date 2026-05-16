# Spring AOT 라이브러리 모듈 적용 — 버전 다운그레이드로 인한 NoSuchMethodError

**날짜**: 2026-05-16  
**관련 이슈**: #256

---

## 배경

`leader-spring-boot` 라이브러리 모듈에서 Spring AOT 호환성 검증 테스트(`LocalLeaderAotTest`)를 구성했다.
5개 테스트 중 suspend 관련 테스트 1개가 `NoSuchMethodError: Mutex.lock$default`로 실패했다.

---

## 근본 원인

### kotlinx-coroutines 버전 분기

| 모듈 | coroutines 버전 |
|------|----------------|
| `leader-core` (컴파일 시) | **1.11.0** |
| `leader-spring-boot:testRuntimeClasspath` | **1.10.2** (Spring Boot BOM 강제) |

coroutines **1.11.0**에서는 `Mutex.lock$default`가 **인터페이스 자체**에 static 메서드로 존재한다.

```java
// coroutines 1.11.0 — Mutex interface
public static java.lang.Object lock$default(Mutex, Object, Continuation, int, Object);
```

coroutines **1.10.2**에서는 `lock$default`가 `Mutex$DefaultImpls`에만 존재한다.

```java
// coroutines 1.10.2 — Mutex$DefaultImpls (interface에는 없음)
public static java.lang.Object lock$default(Mutex, Object, Continuation, int, Object);
```

`leader-core`가 1.11.0으로 컴파일될 때 `mutex.lock()` (default owner = null) 호출은
`Mutex.lock$default(...)` (interface static)을 참조하는 바이트코드를 생성한다.
런타임에 Spring Boot BOM이 coroutines를 1.10.2로 다운그레이드하면 해당 메서드가 없어서 `NoSuchMethodError`가 발생한다.

### AOT 테스트에서만 나타나는 이유

일반 `leader-spring-boot:test`에는 `LocalSuspendLeaderElector.tryWithLock()`을 직접 호출하는 경로가 없다.
AOT 테스트에서 처음으로 suspend API를 end-to-end로 호출하면서 문제가 드러났다.

---

## 해결책

`leader-spring-boot/build.gradle.kts`의 `dependencyManagement`에 `kotlinx-coroutines-bom`을 명시적으로 추가.
Spring Boot BOM 이후에 import하여 coroutines 버전을 프로젝트 기준(1.11.0)으로 고정.

```kotlin
dependencyManagement {
    imports {
        mavenBom(libs.spring.boot4.dependencies.get().toString())
        mavenBom(libs.kotlin.bom.get().toString())
        // spring-boot-dependencies pins coroutines to 1.10.2, but leader-core
        // is compiled against 1.11.0. Override to prevent NoSuchMethodError.
        mavenBom(libs.kotlinx.coroutines.bom.get().toString())
    }
}
```

---

## Spring AOT 라이브러리 모듈 적용 방법

라이브러리 모듈에서 AOT 호환성을 검증하려면:

1. **Spring Boot plugin 적용** (`apply false` 아닌 실제 적용): `processAot` / `processTestAot` task가 등록됨
2. **bootJar 비활성화**: `tasks.bootJar { enabled = false }` + `tasks.jar { enabled = true }`
3. **커스텀 `aotTest` task 등록**:
   ```kotlin
   val aotTest by tasks.registering(Test::class) {
       dependsOn(tasks.named("aotTestClasses"))
       useJUnitPlatform()
       testClassesDirs = sourceSets.test.get().output.classesDirs
       // aotTest sourceset의 AOT 생성 클래스 + 일반 test runtime classpath 조합
       classpath = sourceSets["aotTest"].output.classesDirs +
                   sourceSets.test.get().runtimeClasspath
       jvmArgs("-Dspring.aot.enabled=true")
       filter { includeTestsMatching("io.bluetape4k.leader.spring.aot.*") }
   }
   ```
4. **BOM 버전 동기화**: 라이브러리 의존성이 Spring Boot BOM에 의해 다운그레이드되지 않도록 `kotlinx-coroutines-bom` 등 명시적 override 추가

---

## 검증 결과

```
aotTest: 5/5 passing (3.3s) — suspend runIfLeader 포함
test   : 321/321 passing (1m 27s)
```

---

## 핵심 교훈

**Spring Boot 모듈에서 `dependencyManagement`에 BOM을 추가할 때**, Spring Boot BOM이 다운그레이드할 수 있는 의존성이 있는지 확인해야 한다. 특히 라이브러리 모듈은 Spring Boot App과 달리 의존성이 바깥에서 공급되므로, 버전 불일치가 런타임에만 드러날 수 있다.

`kotlinx-coroutines-bom`처럼 다른 모듈이 더 높은 버전으로 컴파일한 경우 Spring Boot BOM의 다운그레이드는 바이너리 비호환성(ABI break)을 야기할 수 있다.
