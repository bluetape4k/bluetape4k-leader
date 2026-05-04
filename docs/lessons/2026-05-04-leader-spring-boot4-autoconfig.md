# Lessons Learned — leader-spring-boot4 자동 구성 (2026-05-04)

**관련 PR**: TBD
**관련 이슈**: #12 (선행 #11)
**영향 모듈**: `leader-spring-boot4`, `.github/workflows/nightly.yml`

## L1: Spring Boot 4 BOM의 `BindResult.orElse()` 시그니처 변경

### 문제
SB3 (Boot 3.5)에서 동작하던 `Binder.bind(name, T::class.java).orElse(default)` 가 SB4 (Boot 4.0.6) 에서 `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'T?'` 컴파일 에러 발생. `BindResult<T>.orElse(other: T): T` 의 반환 타입이 nullable로 변경된 것으로 보임.

### 교훈
Boot 4 `Binder` 사용 시 default 값을 받는 패턴은 `bindOrCreate(name, T::class.java)` 사용. 이 메서드는 항상 non-null `T` 반환을 보장하며 default는 빈 생성자 기반 인스턴스로 처리됨. Reified 확장으로 idiom 유지:

```kotlin
private inline fun <reified T : Any> Binder.bindOrCreate(name: String): T =
    bindOrCreate(name, T::class.java)

// usage
val props = Binder(source).bindOrCreate<Boot4LeaderProperties>("bluetape4k.leader")
```

---

## L2: Spring Boot 4 BOM이 h2를 2.4.x로 강제 → r2dbc-h2 1.0.x ABI 충돌

### 문제
Boot 4 BOM은 h2를 2.4.x (정확히 SB4 환경에서는 2.4.240)로 강제 upgrade. 그러나 `io.r2dbc:r2dbc-h2:1.0.x`는 h2 2.1.x의 `Session.prepareCommand(String, int)` 시그니처에 의존. h2 2.4.x에서 이 메서드 시그니처가 변경되어 r2dbc-h2 호출 시 `NoSuchMethodError: 'org.h2.command.CommandInterface org.h2.engine.Session.prepareCommand(java.lang.String, int)'`.

SB3 (Boot 3.5)에서는 BOM이 h2를 강제 upgrade하지 않아 r2dbc-h2가 transitively 가져오는 h2 2.1.214가 그대로 사용되어 문제 없었음.

### 교훈
- Boot 4 모듈에서 r2dbc-h2 사용 테스트가 있다면 `dependencyManagement.dependencies` 블록에서 `dependency("com.h2database:h2:2.1.214")`로 다운그레이드 핀 필수
- 영향 범위는 해당 모듈의 `testRuntime`에 한정 (main classpath 무영향)
- r2dbc-h2 1.1.0이 maven central에 존재하지만 h2 2.4.x 호환 여부 미확인이며 사실상 유지보수 정체. 1.0.x 유지가 안전
- 본 트레이드오프는 build.gradle.kts 주석에 영향 범위 + 사유 + SB3 차이 명시 필수

---

## L3: SB3 → SB4 포팅 패턴

### 문제
`leader-spring-boot3` 코드 1,011 lines를 `leader-spring-boot4`로 포팅. 패키지/클래스명 차이만 있는 거의 1:1 mirror.

### 교훈
포팅 절차 표준화 (Boot 3 → Boot 4):
```bash
# 1. 소스 트리 복사
mkdir -p leader-spring-boot4/src/{main,test}/kotlin/io/bluetape4k/leader/spring
cp -r leader-spring-boot3/src/main/kotlin/io/bluetape4k/leader/spring/boot3 \
      leader-spring-boot4/src/main/kotlin/io/bluetape4k/leader/spring/boot4
cp -r leader-spring-boot3/src/main/resources leader-spring-boot4/src/main/
cp -r leader-spring-boot3/src/test/kotlin/io/bluetape4k/leader/spring/boot3 \
      leader-spring-boot4/src/test/kotlin/io/bluetape4k/leader/spring/boot4
cp -r leader-spring-boot3/src/test/resources leader-spring-boot4/src/test/

# 2. 패키지/클래스명 일괄 변경
find leader-spring-boot4/src -name "*.kt" -exec sed -i '' \
  's|io\.bluetape4k\.leader\.spring\.boot3|io.bluetape4k.leader.spring.boot4|g; \
   s|Boot3LeaderProperties|Boot4LeaderProperties|g' {} \;
mv .../boot4/Boot3LeaderProperties.kt .../boot4/Boot4LeaderProperties.kt
mv .../boot4/Boot3LeaderPropertiesBindingTest.kt .../boot4/Boot4LeaderPropertiesBindingTest.kt
sed -i '' 's|spring\.boot3|spring.boot4|g' AutoConfiguration.imports

# 3. README는 sed로 바로 생성
sed 's/Spring Boot 3/Spring Boot 4/g; s/leader-spring-boot3/leader-spring-boot4/g; \
     s/spring\.boot3/spring.boot4/g; s/Boot3LeaderProperties/Boot4LeaderProperties/g; \
     s/Boot 3/Boot 4/g' leader-spring-boot3/README.md > leader-spring-boot4/README.md
```

**주의**: KDoc 본문의 "Spring Boot 3" 같은 자연어 문구는 sed가 잡지 못해 수동 검토 필수. 코드 식별자(`boot3`, `Boot3...`) 만 sed가 안전하게 처리. Tier 4 review에서 KDoc 잔재 검출됨 → Tier 5 simplifier에서 수정.

---

## L4: 기존 워크트리에서 외부 도구로 다운로드한 임시 파일 정리

### 문제
디버깅 중 `curl`로 다운로드한 `r2dbc-h2-1.1.0.RELEASE.jar` (112KB) 가 워크트리 루트에 잔존. `.gitignore` 체크 누락 시 commit 위험.

### 교훈
조사/디버깅용 산출물은 즉시 정리 또는 `/tmp/`에 다운로드. PR 생성 전 `git status` 로 워크트리 untracked 파일 검토 필수. Tier 4 review 항목으로 자주 검출됨.
