---
manualId: "bluetape4k-leader-bom"
id: "bluetape4k-leader-bom"
title: "Leader BOM"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-bom"
sourceDir: "bluetape4k-leader-bom"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-bom
---

# Leader BOM

> 라이브러리 모듈

## 제공하는 기능 {#problem}

배포되는 모든 Leader 모듈의 버전을 맞춥니다. 애플리케이션이 이 내부 BOM을 따로 고를 필요는 없습니다. `bluetape4k-dependencies`가 이미 가져옵니다.

## 사용하기 좋은 경우 {#when-to-use}

버전 정렬 문제를 진단할 때 이 페이지를 보세요. 일반 설정에서는 생태계 플랫폼 버전 하나만 고르고 모든 Leader 모듈에서 버전을 뺍니다.

## 의존성 좌표 {#coordinates}

내부 좌표는 `io.github.bluetape4k.leader:bluetape4k-leader-bom`입니다. 사용자는 `io.github.bluetape4k:bluetape4k-dependencies:<version>`를 가져옵니다.

## 핵심 개념 {#concepts}

Gradle `java-platform` 모듈이라 런타임 클래스가 아니라 의존성 제약을 배포합니다. 버전 소유권은 애플리케이션의 `bluetape4k-dependencies`에서 이 BOM으로 이어집니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
}
```

## 작업별 API {#api-by-task}

런타임 API는 없습니다. `dependencyInsight`로 어떤 플랫폼이 모듈 버전을 선택했는지 확인합니다.

## 권장 패턴 {#patterns}

최상위 플랫폼 하나만 가져오고 모듈 선언에서는 버전을 뺍니다. 플랫폼 업그레이드도 한 번의 검토 가능한 변경으로 묶습니다.

## 연동 {#integrations}

사용자에게 공개되는 통합 지점은 `bluetape4k-dependencies`입니다. Maven에서는 dependency management로 가져옵니다.

## 설정 {#configuration}

BOM에는 프로퍼티, Bean, 생명주기가 없습니다. 안정판은 Maven Central에서 받습니다.

## 실패 유형과 해결 방법 {#failures}

Leader BOM을 직접 가져오면서 모듈 버전까지 쓰면 버전 소유권이 갈립니다. 모듈 버전을 지우고 해석된 제약을 확인하세요.

## 운영 {#operations}

빌드와 배포 메타데이터에는 내부 BOM 버전이 아니라 선택한 `bluetape4k-dependencies` 버전을 기록합니다.

## 테스트 {#testing}

플랫폼을 올린 뒤 Core 타입 하나와 선택한 백엔드 하나를 함께 컴파일하세요. Dependency locking으로 해석 결과를 고정할 수도 있습니다.

## 학습 경로와 예제 {#workshops}

저장소 개요에서 선출 모델을 고른 뒤 운영할 백엔드 페이지로 이동하세요. 예제에는 실행 가능한 조합이 나와 있습니다.

## 제약 사항 {#limitations}

버전 정렬이 외부 Redis, DB, Kubernetes, 프레임워크의 모든 버전과 호환된다는 뜻은 아닙니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader bom 아키텍처

[![leader bom 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.svg)

_배포본 README: [`bluetape4k-leader-bom/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/bluetape4k-leader-bom/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[안정판 BOM 빌드](../../../../bluetape4k-leader-bom/build.gradle.kts) · [안정판 저장소 안내](../../../../README.ko.md)

