# ZooKeeper API 7계층 검토

날짜: 2026-07-04 범위: Issue #571, #572, #581, 마일스톤 0.5.0

## 검토된 모듈

- `leader-zookeeper`: 경로 구성, CuratorFramework 편의 오버로드, 소유자 디스패처 수명 주기 일시 중지.
- `leader-consul`: 공개 옵션 검증.

## 7계층 결과

1. 정확성: 통과
   - ZooKeeper 잠금 이름은 이제 znode 경로 세그먼트가 되기 전에 공유 `validateLockName()` 계약을 전달합니다.
   - 기본 경로는 연결하기 전에 큐레이터 경로 규칙을 사용하여 정규화되고 검증됩니다.

2. API 및 계약 호환성: 통과
   - 기존 문자열 기반 편의 오버로드는 소스 호환 상태로 유지되며 형식화된 경로 오버로드에 위임됩니다.
   - `ZooKeeperElectionPath`는 위치 문자열 교환을 피하려는 새로운 호출 사이트에 대해 단일 형식 인수를 제공합니다.

3. 동시성 및 취소: PASS
   - 단일 리더 선택 일시 중단은 더 이상 `runIfLeader` 호출당 단일 스레드 실행기를 생성하지 않습니다.
   - 제한된 재사용 가능 소유자-디스패처 풀은 대기 중인 획득 뒤에 있는 릴리스를 차단하지 않고 큐레이터의 동일 스레드 획득/해제 제약 조건을 유지합니다.
   - 취소 전파 및 `NonCancellable` 릴리스 동작은 유지됩니다.

4. 백엔드 소유권 안전성: 통과
   - 잘못된 슬래시, 유사 순회, 빈 세그먼트 및 예약된 잠금 이름은 구성된 ZooKeeper 네임스페이스를 벗어날 수 없습니다.
   - 기존의 유효한 루트 및 후행 슬래시 기본 경로 동작이 유지됩니다.

5. 테스트: 합격
   - 잘못된 ZooKeeper 잠금/기본 경로 회귀를 추가했습니다.
   - 동기화, 비동기, 정지 및 그룹 편의 API에 대한 유형화된 오버로드 적용 범위를 추가했습니다.
   - 일시 중지 소유자-디스패처 재사용 범위를 추가하고 전체 ZooKeeper 모듈 테스트 모음을 다시 실행했습니다.
   - 세션 이름, 임대 범위 및 잠금 지연에 대한 Consul 옵션 검증 회귀를 추가했습니다.

6. 보안 및 관찰 가능성: 통과
   - 새로운 토큰이나 자격 증명 로깅이 없습니다.
   - 큐레이터 znode 생성 전에 경로 검증이 failure하여 네임스페이스 이스케이프 시도가 방지됩니다.

7. 유지보수성: 합격
   - 변경 사항은 ZooKeeper 및 Consul API/검증 표면 내에 유지됩니다.
   - 수명주기 소유권은 `ZooKeeperSuspendLeaderElector` 및 해당 공장에 문서화되어 있습니다.

## 검증 증거

- `./gradlew :bluetape4k-leader-consul:compileKotlin :bluetape4k-leader-consul:compileTestKotlin :bluetape4k-leader-zookeeper:compileKotlin :bluetape4k-leader-zookeeper:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-consul:test --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectionOptionsTest' :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperApiCoverageTest' --warning-mode all`
- `./gradlew :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElectorTest.SuspendedJobTester - 코루틴 job 경합에서 단일 리더만 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-zookeeper:test --warning-mode all`
- `git diff --check`

## Deferred 검증

전체 저장소 테스트는 요청된 워크플로우에 따라 전체 스택 이슈 트레인이 구현될 때까지 의도적으로 연기됩니다.
