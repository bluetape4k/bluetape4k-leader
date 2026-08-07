---
manualId: "webhook-poller"
id: "webhook-poller"
title: "Webhook 폴러 워크숍"
locale: "ko"
kind: "example"
gradlePath: ":examples:webhook-poller"
sourceDir: "examples/webhook-poller"
releaseRef: "0.5.0"
artifact: null
---

# MongoDB webhook poller 워크숍

> 실행 가능한 안정판 워크숍 · MongoDB suspend 선출과 원자적 event claim · `0.5.0` 소스 기준

## 제공하는 기능 {#problem}

선출된 poller 하나가 대기 중인 이벤트를 원자적으로 claim하고 handler를 실행합니다. 결과에 따라 이벤트를 `DONE`, 재시도 가능한 `PENDING`, 종결 상태인 `FAILED`로 옮깁니다. 리더가 죽어 만료된 claim은 다음 리더가 회수합니다.

이 예제는 “누가 본문을 시작할 수 있는가”와 “본문이 진행 상태를 어떻게 남기는가”를 분리해서 보여줍니다. 리더 선출은 실행 권한을 조정할 뿐, 업무 로직의 멱등성까지 대신해 주지는 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

여러 인스턴스가 같은 일을 시작할 수 있지만 실제 본문은 한 곳에서만 실행해야 할 때 이 워크숍을 참고하세요. 먼저 예제를 그대로 실행합니다. 그런 다음 락 이름, 실행 시간 제한, 건너뛴 실행을 관찰할 지점은 유지하고 작업 본문만 작은 단위로 바꾸는 편이 안전합니다.

모든 인스턴스가 각각 실행해야 하는 작업, 대기열이 이미 배타적 소유권을 부여하는 작업, 리더 장애 뒤 재시도를 허용할 수 없는 작업에는 이 패턴이 맞지 않습니다.

## 의존성 좌표 {#coordinates}

이 예제 자체는 라이브러리로 배포하지 않습니다. 저장소에서 직접 실행하세요. 실제 애플리케이션에서는 `bluetape4k-dependencies` 버전 하나만 정하고, 필요한 리더 선출 백엔드에는 별도 버전을 적지 않습니다.

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    // 이 워크숍에서 사용한 백엔드를 버전 없이 추가합니다.
}
```

## 핵심 개념 {#concepts}

- **조정 백엔드:** MongoDB 코루틴 선출기가 리더를 정하고 원자적 이벤트 소유권 획득으로 중복 처리를 막습니다.
- **공유 식별자:** 같은 논리 작업을 두고 경쟁하는 인스턴스는 같은 락 이름을 계산해야 합니다.
- **경쟁에서 탈락:** 리더가 되지 못한 결과는 장애가 아니라 정상적인 제어 흐름입니다.
- **제한된 소유권:** 제한 시간, 리스나 세션, 종료 처리가 다음 노드의 인계 시점을 결정합니다.
- **작업 본문의 안전성:** 재시도, 체크포인트, 멱등성은 애플리케이션 본문이 책임집니다.

빌드 성공만 보고 끝내지 말고 다음 동작을 출력과 테스트에서 확인하세요: 미리 넣은 이벤트 10건이 동시에 중복 claim되지 않고, 시도 횟수는 claim 시점에 증가하며, 종결 실패에는 `lastError`가 남는지.

## 빠르게 시작하기 {#quick-start}

사전 준비: JDK 21 이상, 테스트용 Docker 또는 데모에서 사용할 외부 MongoDB의 `MONGO_URL`.

```bash
MONGO_URL=mongodb://localhost:27017 ./gradlew :examples:webhook-poller:run
```

데모가 지원한다면 두 개 이상의 후보를 함께 실행하세요. 미리 넣은 이벤트 10건이 동시에 중복 claim되지 않고, 시도 횟수는 claim 시점에 증가하며, 종결 실패에는 `lastError`가 남는지를 확인한 다음 현재 소유자를 종료하거나 락을 반환해 다음 주기의 인계까지 살펴봅니다.

## 작업별 API {#api-by-task}

1. `WebhookPoller`에서 애플리케이션이 호출할 조정 경계를 확인합니다.
2. `WebhookPollerDemo`에서 클라이언트 생성, 후보 구성, 결과 출력 순서를 따라갑니다.
3. `WebhookPollerTest`에서 0.5.0 안정판이 보장하는 소유권 전이를 확인합니다.
4. 데모의 작업 본문을 작은 멱등 작업으로 바꾼 뒤 실제 업무에 적용합니다.

반환값도 API 계약의 일부입니다. 실행, 건너뛰기, 실패, 부분 완료를 구분해서 로그와 메트릭에 남겨야 합니다.

## 권장 패턴 {#patterns}

- 락 이름에 환경과 작업 이름을 넣습니다. 예: `prod:billing:nightly`.
- 선출된 본문은 작게 유지합니다. 준비 작업은 락 밖으로 옮기고, 영속 결과는 올바른 트랜잭션 경계 안에서 기록합니다.
- `waitTime`은 경쟁 결과를 빨리 돌려줄 수 있게, 소유 시간은 실제 장애 모델에 맞게 정합니다.
- 취소와 인터럽트 신호를 일반적인 건너뛰기로 바꾸지 않습니다.
- 멱등 키, 마커, 소유권 토큰, 재개 가능한 체크포인트로 인계를 안전하게 만듭니다.

## 연동 {#integrations}

이 워크숍은 MongoDB 코루틴 선출과 원자적 이벤트 소유권 획득을 실행 가능한 Gradle 애플리케이션에 연결합니다. 예제는 학습에 필요한 로컬 실행 환경을 준비합니다. 운영에서는 백엔드 클라이언트를 주입하고 애플리케이션 생명주기에 맞춰 닫아야 합니다.

스케줄러, HTTP 계층, 오퍼레이터, 코루틴 프레임워크는 조정 경계를 호출하는 역할만 맡아야 합니다. 락 획득을 무관한 업무 서비스 안에 숨기지 않으면 경쟁과 인계 상태를 테스트와 메트릭에서 확인하기 쉽습니다.

## 설정 {#configuration}

Mongo URI, 락 컬렉션과 이벤트 컬렉션, 환경별 `lockName`, `pollInterval`, `batchSize`, `maxAttempts`, `claimDuration`, 종료 제한 시간을 먼저 확인하세요. 같은 작업을 맡는 모든 인스턴스는 같은 논리 락 이름을 써야 하고, 서로 무관한 작업이나 환경은 다른 이름을 써야 합니다.

로컬에서는 짧은 값을 사용해 경쟁과 인계를 눈으로 확인합니다. 운영 값은 데모 기본값이 아니라 실제 본문 실행 시간, 백엔드 지연, 종료 예산, 중복 실행 비용을 측정해서 정해야 합니다.

## 실패 유형과 해결 방법 {#failures}

- **모든 노드가 실행됨:** 락 이름이나 namespace가 서로 다릅니다. 시작 로그에 최종 락 이름을 남깁니다.
- **아무 노드도 실행하지 않음:** 백엔드 연결, 인증 정보, RBAC, 기존 소유자를 확인합니다.
- **인계가 늦음:** 리스나 세션의 유지 시간 또는 클라이언트 제한 시간이 서비스 복구 예산보다 깁니다.
- **장애 뒤 작업이 반복됨:** 진행 상태를 기록하기 전에 소유권이 끝났습니다. 멱등 키나 체크포인트를 추가합니다.
- **종료가 멈춤:** 작업 본문이 취소를 무시하거나 클라이언트 또는 실행기를 닫지 않았습니다.

백엔드 상태, 계산된 락 이름, 획득 결과, 본문 결과, 반환과 인계 증거 순서로 진단하면 원인을 빠르게 좁힐 수 있습니다.

## 운영 {#operations}

시도, 획득, 건너뛴 실행, 실패, 본문 실행 시간, 인계 지연을 락 이름별로 기록합니다. 태그 값의 종류가 지나치게 늘어나지 않도록 제한하세요. 가끔 발생하는 정상적인 건너뛰기보다 장시간 획득 0건, 실행 시간 증가, 잦은 소유권 변경을 경보 대상으로 삼는 편이 낫습니다.

백엔드 준비, 인증 정보, 스키마와 키, 정리 정책, 클라이언트 종료를 누가 맡는지 운영 문서에 남기세요. 데모가 성공했다고 이 책임이 라이브러리로 넘어가지는 않습니다.

## 테스트 {#testing}

```bash
./gradlew :examples:webhook-poller:test
```

테스트에서는 경쟁 중 소유자 1개, 경쟁자가 예외 없이 실행을 건너뛰는지, 정상 종료와 실패 후 락이 반환되는지, 다른 노드가 다시 획득하는지 확인해야 합니다. Docker나 특권 모드가 필요한 통합 테스트는 빠른 단위 테스트와 분리할 수 있지만 모의 객체만으로 대체해서는 안 됩니다.

## 학습 경로와 예제 {#workshops}

이 페이지는 명령어 목록이 아니라 단계별 워크숍입니다. 다음 순서로 진행하면 예제의 의도를 놓치지 않습니다.

1. **기본 실행:** 후보 하나를 실행하고 작업 본문의 경계를 찾습니다.
2. **경쟁:** 후보를 여러 개 실행하고 실제 실행과 건너뛴 실행으로 결과가 갈리는 이유를 설명합니다.
3. **장애:** 현재 소유자를 종료하거나 본문을 실패시켜 반환 또는 만료를 관찰합니다.
4. **인계:** 다른 노드가 영속 상태를 깨뜨리지 않고 이어서 실행하는지 확인합니다.
5. **응용:** 락 네임스페이스와 작업 본문을 바꾸고 메트릭과 실패 중심 테스트를 하나 추가합니다.

워크숍을 마친 뒤에는 해당 백엔드 매뉴얼과 코어의 실행 모델·실패 의미론 장을 함께 읽고 서비스 코드에 적용하세요.

## 제약 사항 {#limitations}

리더가 죽은 뒤에는 at-least-once로 다시 전달될 수 있습니다. webhook 수신 측이나 handler가 `eventId`로 중복을 제거해야 합니다.

데모는 짧고 관찰하기 쉬운 시나리오에 집중합니다. 운영 토폴로지, 인증 정보 교체, 용량 계획, 재해 복구, 보편적인 `exactly-once` 실행 보장까지 정의하지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### webhook poller 아키텍처

[![webhook poller 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-architecture-01.svg)

_배포본 README: [`examples/webhook-poller/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/examples/webhook-poller/README.ko.md)_

### Webhook poller 처리 흐름

[![Webhook poller 처리 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-flow-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-flow-01.svg)

_배포본 README: [`examples/webhook-poller/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/examples/webhook-poller/README.ko.md)_

### Webhook poller 실행 시나리오 다이어그램

[![Webhook poller 실행 시나리오 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-scenario-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-scenario-01.svg)

_배포본 README: [`examples/webhook-poller/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/examples/webhook-poller/README.ko.md)_

### webhook poller 실행 흐름

[![webhook poller 실행 흐름](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-sequence-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/examples-webhook-poller-sequence-01.svg)

_배포본 README: [`examples/webhook-poller/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/examples/webhook-poller/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

- [안정판 예제 README](../../../../examples/webhook-poller/README.ko.md)
- [Gradle 빌드](../../../../examples/webhook-poller/build.gradle.kts)
- [주요 구현](../../../../examples/webhook-poller/src/main/kotlin/io/bluetape4k/leader/examples/webhook/WebhookPoller.kt)
- [데모 진입점](../../../../examples/webhook-poller/src/main/kotlin/io/bluetape4k/leader/examples/webhook/WebhookPollerDemo.kt)
- [검증 테스트](../../../../examples/webhook-poller/src/test/kotlin/io/bluetape4k/leader/examples/webhook/WebhookPollerTest.kt)
