---
title: "저장소와 학습 구조"
description: "계약, 저장소 구현, 프레임워크 연동, 완성된 예제가 각각 어느 모듈에 있는지 한눈에 정리합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 저장소와 학습 구조

계약, 저장소 구현, 프레임워크 연동, 완성된 예제가 각각 어느 모듈에 있는지 한눈에 정리합니다.

![Leader 저장소와 학습 구조](../../assets/overview/repository-learning-map.png)

## 네 개의 층으로 본다

`leader-core`는 API 계약과 로컬 구현을 맡습니다. 백엔드 모듈은 Redis, SQL, 문서 저장소, coordination 시스템, Kubernetes에 그 계약을 구현합니다. 프레임워크 모듈은 Spring Boot, Ktor, Micrometer를 연결합니다. 17개의 `examples/*` 프로젝트는 이 부품들을 실제 운영 시나리오로 조합합니다.

## 책임을 따라 읽는다

먼저 core에서 경쟁, 취소, 리스 의미를 익혀야 백엔드를 바꿔도 애플리케이션 규칙이 흔들리지 않습니다. elector를 고른 뒤 프레임워크 문서를 읽고, 예제에서는 시작·종료·메트릭·장애 동작을 확인합니다. 예제는 배포 대상에서 제외되므로 애플리케이션 의존성으로 추가하지 않습니다.

## Stable과 Preview의 의미

0.5.0에서 core, Redis, Exposed, MongoDB, Hazelcast, ZooKeeper, 프레임워크 연동, Micrometer는 stable입니다. DynamoDB, etcd, Consul, Kubernetes는 preview입니다. Preview에서도 경쟁 처리 계약은 같지만, 운영 투입 전 통합 시험과 되돌리기 계획을 더 엄격하게 준비해야 합니다.

## 릴리스 소스

- [`settings.gradle.kts`](../../../../settings.gradle.kts)
- [`README.ko.md`](../../../../README.ko.md)
- [`build.gradle.kts`](../../../../build.gradle.kts)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [학습 경로](../guides/learning-path.md)
- [백엔드 선택](../guides/backend-selection.md)

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### Leader election 런타임 구조도

[![Leader election 런타임 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-architecture-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/README.ko.md)_

### runIfLeader 다이어그램

[![runIfLeader 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-sequence-02.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/README.ko.md)_

### Multi-leader group: slot-based semaphore 다이어그램

[![Multi-leader group: slot-based semaphore 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-sequence-03.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/README.ko.md)_

### Bluetape4k Leader 개요

[![Bluetape4k Leader 개요](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/root-readme-overview-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/root-readme-overview-01.svg)

_배포본 README: [`README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/README.ko.md)_

<!-- release-readme-diagrams:end -->
