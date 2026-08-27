# VirtualThread 2.0.0 snapshot train 정합성

## 맥락

PR #809의 exact-head hosted CI에서 Lettuce와 Redisson을 포함한 여러 모듈이
`io/bluetape4k/concurrent/virtualthread/api/VirtualThreads`를 찾지 못하고
초기화에 실패했습니다. Leader의 `gradle.properties`는 이전 `1.13.0`
snapshot pin을 사용했지만, upstream package-boundary 변경(PR
`bluetape4k-projects#1523`)은 public API를
`io.bluetape4k.concurrent.virtualthread.api`로 이동했습니다. 따라서 테스트
실패는 Leader 코드의 취소 동작이 아니라 서로 다른 ABI train을 섞은
dependency resolution 문제였습니다.

## 결정

- `bluetape4k-virtualthread-api`, `bluetape4k-virtualthread-jdk25`를 포함해
  core, coroutine, idgenerator, junit5, testcontainers helper가 같은
  `2.0.0-20260826.200524-12` immutable snapshot train을 사용하도록
  정렬합니다.
- 기본 fallback도 `2.0.0-SNAPSHOT`으로 맞춰 명시적 pin이 없는 환경에서
  이전 `1.13.0` ABI가 다시 선택되지 않게 합니다.
- moving snapshot 검증에는 `--refresh-dependencies`를 포함합니다. 캐시된
  helper jar만 남아 있으면 API jar가 새 package를 제공해도 동일한
  `NoClassDefFoundError`가 재현될 수 있습니다.
- Leader에 호환 bridge나 shading을 추가하지 않습니다. package ownership과
  ServiceLoader 경계를 upstream train에서 관리하고, 소비자는 동일 train을
  선택해야 합니다.

## 결과

의존성 정렬은 PR #810에서 PR #809 위에 별도 stacked slot으로 전달합니다.
변경 범위는 root Gradle fallback/pin과 이 lesson으로 제한했습니다. Kotlin
production/test source는 수정하지 않았습니다.

## 검증

- RED hosted evidence: exact-head run
  `33039344078`에서 Lettuce job `98414547953`는 309개 중 18개,
  Redisson job `98415899069`는 42개 중 29개가 동일한 missing-class ABI
  오류로 실패했습니다.
- Central snapshot metadata와 jar scan에서 관련 artifact가 모두
  `2.0.0-20260826.200524-12`이고 API class가 `.api.VirtualThreads`에
  존재함을 확인했습니다.
- `--refresh-dependencies` 기준 targeted ToxiProxy 취소 테스트는
  Lettuce 2개와 Redisson 2개 모두 통과했습니다.
- 같은 조건의 전체 테스트는 Lettuce 309개, Redisson 291개가 모두
  통과했습니다.
- 두 모듈 detekt와 root `./gradlew build -x test`가 통과했습니다. Maven
  snapshot 전송 중 발생한 일시적 `bad_record_mac`은 재시도 후 성공했으며
  코드 오류로 분류하지 않았습니다.
- `git diff --check`와 dependency graph를 통과시켜 변경 파일과 선택된
  runtime artifact를 재확인했습니다.

## 향후 지침

1. Bluetape snapshot ABI 오류가 보이면 먼저 Central metadata의 timestamp와
   `dependencyInsight`를 확인하고, API/provider/core/test-helper가 같은
   train인지 비교합니다.
2. 선택된 jar를 직접 scan해 package ownership과 ServiceLoader descriptor를
   확인합니다. 새 package가 필요한 소비자에 옛 pin을 유지한 채 bridge를
   넣어 문제를 숨기지 않습니다.
3. moving snapshot의 로컬 검증은 반드시 `--refresh-dependencies`로 수행하고,
   새 commit의 exact-head hosted CI를 별도로 확인합니다.
4. TLS 또는 Maven transport 오류는 재시도 가능한 외부 전송 문제인지 먼저
   분류합니다. 같은 missing-class가 여러 모듈에서 반복되면 테스트별 결함이
   아니라 공통 ABI train 불일치로 조사합니다.
