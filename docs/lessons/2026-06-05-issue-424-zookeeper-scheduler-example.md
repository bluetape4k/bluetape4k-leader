# 이슈 #424 ZooKeeper 스케줄러 예시

## 맥락

Issue #424에는 하나의 노드에서만 실행해야 하는 레거시 예약 작업에 대해 실행 가능한 ZooKeeper 예제가 추가되었습니다.

## 결정

`ZooKeeperLeaderElector`를 유일한 선택 경계로 사용하고 데모 및 테스트에서 큐레이터 수명주기 호출자를 소유하게 유지하세요. 또한 이 예제에서는 bluetape4k 도우미(`ZooKeeperServer.Launcher`, `Base58`, `requireNotBlank` 및 bluetape4k 어설션)를 사용하므로 원시 큐레이터 레시피가 아닌 생태계를 보여줍니다.

## 결과

새로운 `examples:zookeeper-scheduler` 모듈은 로케일 및 CI/examples 워크플로 등록 모두에서 README 항목을 사용하여 실행, 건너뛰기 및 재획득 동작을 보여줍니다.

## 검증

- `./gradlew :examples:zookeeper-scheduler:compileKotlin --no-daemon --no-configuration-cache`
- `./gradlew :examples:zookeeper-scheduler:test --no-daemon --no-configuration-cache`
- `./gradlew :examples:zookeeper-scheduler:run --no-daemon --no-configuration-cache`
- `./gradlew projects --no-daemon --no-configuration-cache`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## 미래의 규칙

예제 모듈은 에코시스템 사용을 명시적으로 증명해야 하며 PR 생성 전에 설정, README 로케일 파일, `AGENTS.md`, CI 및 예제 워크플로에 걸쳐 등록되어야 합니다.
