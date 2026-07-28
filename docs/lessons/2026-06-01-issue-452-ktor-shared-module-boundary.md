# 문제 452 - Ktor 공유 모듈 경계

## 맥락

`bluetape4k-leader`에는 게시 가능한 `leader-ktor` 통합 모듈과 실행 가능한 `examples:ktor-app`라는 두 개의 Ktor 방향 표면이 있습니다. 이제 `bluetape4k-projects`는 공유 `bluetape4k-ktor-core` 및 `bluetape4k-ktor-testing` 아티팩트를 제공하므로 리더 저장소에는 일관성 전달이 필요했습니다.

## 결정

- `examples:ktor-app`는 `java.time.Instant`를 노출하기 때문에 `/stats` 응답을 위해 Jackson `ContentNegotiation`를 유지하면서 공유 상태/준비 경로에 `installBluetape4kKtorCore`를 사용합니다.
- `leader-ktor`는 응답 상태 어설션 테스트에서 `bluetape4k-ktor-testing`를 사용합니다.
- `leader-ktor`는 `bluetape4k-ktor-core`에 런타임 종속성을 추가하지 않습니다. 공개 표면은 리더 플러그인/스케줄러 DSL이며, 관리 경로는 콘텐츠 협상 없이 의도적으로 JSON 텍스트를 내보냅니다.

## 결과

Ktor 소비자는 이제 순환적이거나 오해의 소지가 있는 런타임 종속성 경계를 도입하지 않고 공유 모듈 패턴을 명시적으로 문서화합니다.

## 검증

- `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`
- `git diff --check`

## 향후 지침

Ktor 예제를 추가할 때 앱 수준 JSON, 오류, 상태 및 준비 도우미에는 `bluetape4k-ktor-core`를 사용하고 응답 어설션에는 `bluetape4k-ktor-testing`를 사용하세요. 프로덕션 코드에 도우미 동작이 직접 필요하지 않는 한 통합 모듈에 공유 런타임 종속성이 없도록 유지하세요.
