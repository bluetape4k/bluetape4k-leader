# 강의: 소스 증거를 사용하여 더 이상 사용되지 않는 API 정리를 종료합니다.

**날짜**: 2026-05-23 **문제**: #269

## 찾기

Issue #269는 #264 및 #271의 후속 조치였습니다. 이 레인이 시작될 때까지 나머지 `@Deprecated` API 제거 및 위임 확장 `runBlocking` 브리지 제거 일시 중단이 이미 시작되었습니다.

## 증거

- `rg -n "@Deprecated|DeprecationLevel\\.ERROR" --glob '*.kt'`는 소스에서 일치하는 항목을 반환하지 않았습니다.
- `rg --files -g '*LettuceSemaphore*.kt' -g '*LettuceSuspendSemaphore*.kt'`는 현재 트리에서 일치하는 항목을 반환하지 않았습니다.
- `git show --name-status 3c78fd75`는 `refactor: remove all @Deprecated APIs (10 items) (#274)`에 의해 삭제된 `LettuceSemaphore.kt`, `LettuceSuspendSemaphore.kt` 및 `LettuceSemaphoreTest.kt`를 보여줍니다.
- Issue #264가 종결되었으며 0.1.0 더 이상 사용되지 않는 API 목록이 제거되었습니다.
- Issue #271은 종료되었으며 대상 일시 중지-차단 확장 대리자 브리지 패턴이 제거되었습니다.
- 오래된 참조는 레거시 Lettuce 세마포어 클래스가 더 이상 사용되지 않는 소스로 여전히 존재한다는 문서 설명이었습니다.

## 결정

코드 변경을 고안하는 대신 약간의 문서 수정 PR로 #269를 닫습니다. 런타임 API 표면은 이미 정리 요구 사항을 충족합니다. 남은 위험은 오래된 마이그레이션 문서였습니다.
