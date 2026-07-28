# 문제 426 가상 스레드 실행기 예

## 맥락

라이브러리는 가상 스레드 선출기를 노출했지만 사용자가 동시성 높은 리더 전용 작업에 이를 적용하는 방법을 보여주는 실행 가능한 예제가 없었습니다.

## 결정

하나의 잠금에 대해 많은 노드를 경주하고, 단 하나의 작업만 실행됨을 증명하고, 선택된 작업이 Java 가상 스레드에서 실행된다는 것을 검증하는 로컬 백엔드 예제를 추가합니다. 릴리스 분기 CI에 대해 안전하도록 예제 인프라가 없는 상태로 유지하세요.

## 결과

이제 `examples/virtual-thread-runner`는 가상 스레드 선출기와 코루틴 또는 차단 선출기를 사용해야 하는 시기를 문서화하고 CI 및 예제 워크플로에 연결됩니다.

## 검증

- `./gradlew :examples:virtual-thread-runner:test --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## 향후 지침

API 형태의 교육을 위해 로컬 가상 스레드 예제를 사용합니다. 시나리오에 외부 런타임 의미 체계가 필요한 경우에만 백엔드별 예제를 사용하세요.
