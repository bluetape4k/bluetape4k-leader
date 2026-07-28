# 문제 447 공유 Ktor 모듈 채택

## 맥락

Milestone 0.3.0 Issue #447에서는 `bluetape4k-projects` 1.10.0이 공유 Ktor 모듈을 게시한 후 `leader-ktor` 및 `examples/ktor-app` 감사를 요청했습니다.

## 결정

`leader-ktor`는 플러그인, 스케줄러 확장 및 관리 경로가 리더 선택 API이기 때문에 도메인별로 유지되었습니다. `examples/ktor-app`는 상태/준비 경로에 `bluetape4k-ktor-core`를 채택하고 응답 어설션에 `bluetape4k-ktor-testing`를 채택했습니다.

`/stats`가 `Instant`를 노출하기 때문에 이 예에서는 Jackson 콘텐츠 협상이 유지되었습니다. 이를 공유 kotlinx JSON 설치 프로그램으로 바꾸면 이 문제에서 요구하는 것보다 직렬화 계약이 더 많이 변경됩니다.

## 결과

이제 `GET /health`는 공유 Ktor 코어에서 나오며, 이 예에서는 `GET /readyz`의 공유 준비 경로도 공개합니다.

## 검증

- `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`는 17개의 `leader-ktor` 테스트와 7개의 `examples:ktor-app` 테스트를 통과했습니다.
- `git diff --check`가 통과되었습니다.
