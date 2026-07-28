# 227호 리더 등

## 맥락

Issue #227은 전체 etcd v3 리더 선출 백엔드에 대한 서사시입니다. 이 분기에서는 내구성 있는 디자인 아티팩트, 모듈 등록, 최초의 내부 jetcd 경계 유형, 공개 단일 리더 선출기, 공개 그룹 선출기, 감시 지원 이벤트 게시자 및 Spring Boot 공장 배선으로 구현을 시작했습니다.

## 결정

첫 번째 PR을 좁게 유지: `leader-etcd` 모듈을 추가하고, 활성 `bluetape4k-projects` `1.8.1-SNAPSHOT`에 대해 프로젝트 버전을 지정하고, 낮은 수준 리스/잠금 경계를 증명한 다음, 첫 번째 공개 단일 리더 선출기를 추가하는 동시에 그룹 선출, 감시 지원 이벤트 게시 및 Spring Boot 자동 구성을 제한된 후속 조각으로 추가합니다.

실제 통합 테스트에는 `bluetape4k-testcontainers` `EtcdServer.Launcher.etcd`를 사용하세요. 모의 기반 jetcd 테스트는 경계 위임에 유용하지만 서버 끝점 연결, 리스 유지 또는 etcd 잠금 대기열을 입증하지는 않습니다.

## 결과

- 설정 및 리더 BOM에 `leader-etcd`를 추가했습니다.
- etcd 옵션 모델, 키/경로 인코딩, 리스 타이밍/핸들 도우미, 백엔드 오류 분류 및 `JetcdEtcdLockClient` 경계가 추가되었습니다.
- jetcd Lock 리스로 지원되는 차단, 코루틴 및 가상 스레드 단일 리더 선출기를 추가했습니다.
- 슬롯당 하나의 jetcd 잠금 키로 지원되는 블로킹 및 코루틴 그룹 선출기를 추가했습니다.
- 구성된 접두사를 감시하고 etcd 소유권 키 `PUT`/`DELETE` 이벤트를 `Elected`/`Revoked`에 매핑하는 `EtcdLeaderElectionEventPublisher`를 추가했습니다.
- 호출자가 소유한 jetcd `Client` Bean이 있는 경우 차단, 코루틴 및 그룹 선출기에 대한 etcd Spring Boot 자동 구성을 추가했습니다.
- `@LeaderElection` 및 `@LeaderGroupElection`가 Spring에서 etcd 지원 팩토리를 검증할 수 있도록 etcd AOP 팩토리 빈을 추가했습니다.
- 기존 `LockExtender` 계약을 통해 활성 잠금 확장 지원을 추가했습니다.
- 잠금 획득 시간 초과 정리를 취소하여 리스 취소 전에 jetcd 잠금 미래를 보류 중으로 취소하여 시간 초과된 경쟁자가 보유자 뒤에 대기하지 않도록 했습니다.
- 모듈 README 쌍을 추가하고 `leader-etcd`를 CI 및 Nightly 모듈 적용 범위에 연결했습니다.
- 도우미 계약에 대한 단위 테스트를 추가했습니다.
- 모의 잠금 클라이언트 적용 범위와 경합/해제, 취소 정리, 클라이언트 확장, 그룹 최대 리더 경계, 슬롯 재획득, 감시 이벤트 전달 및 활성 잠금 확장 동작과 일치하는 실제 EtcdServer 지원 통합 테스트를 추가했습니다.

## 검증

- `./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers --configuration testRuntimeClasspath --refresh-dependencies --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:test --tests 'io.bluetape4k.leader.etcd.EtcdLeaderElectorFactoryTest' --no-build-cache --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:test --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-etcd:compileKotlin :bluetape4k-leader-etcd:compileTestKotlin :bluetape4k-leader-spring-boot:compileKotlin :bluetape4k-leader-spring-boot:compileTestKotlin --no-daemon --console=plain`
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.BackendConditionalTest' --tests 'io.bluetape4k.leader.spring.LeaderPropertiesBindingTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.EtcdAopFactoryAutoConfigurationTest' --no-build-cache --no-daemon --console=plain`
- `git diff --check`

최신 전체 테스트 실행에서는 59개의 `leader-etcd` 테스트가 실행되었습니다. 즉, 순수 단위 테스트 31개, 모의 jetcd 경계 테스트 3개, 실제 EtcdServer 통합 테스트 25개(단일/그룹 이벤트 전달에 대한 감시 게시자 적용 범위, 대기열에 있는 경쟁자 억제 및 호출자 소유 클라이언트 수명 주기 포함).

## 퓨쳐 가드

백엔드 동작에 충분한 것으로 모의된 jetcd 테스트를 처리하지 마십시오. 나중에 추가되는 모든 공용 etcd 선출기에 대해 경합이 리더 계약 결과를 반환하고, 리스 만료로 소유권이 복구되고, 정리가 로컬 프로세스 상태에 의존하지 않음을 증명하는 일치하는 EtcdServer 지원 테스트를 포함합니다.

그룹 상태는 현재 슬롯당 가장 낮은 `createRevision` 잠금 키를 읽으므로 대기열에 있는 경쟁자가 활성 리더로 계산되지 않고 감사 ID로 소유권 토큰으로 대체됩니다. etcd에서 사람이 읽을 수 있는 그룹 상태를 요청하기 전에 더욱 풍부한 사이드카 메타데이터를 추가하세요.

조사식 게시자는 의도적으로 백엔드 상태 변경 사항만 내보냅니다. `Skipped`를 방출할 것이라고 기대하지 마십시오. 건너뛴 시도는 로컬 획득 결과이며 리스너/데코레이터 API에 의해 계속해서 보호됩니다.

jetcd Lock은 대기 중인 경쟁자에 대한 키도 생성합니다. Watch 게시자는 `Elected`를 방출하기 전에 현재 가장 낮은 `createRevision` 소유자를 재검증해야 합니다. 그렇지 않으면 경합으로 인해 거짓 긍정 선택 이벤트가 생성될 수 있습니다.

Spring 자동 구성은 의도적으로 `EtcdLeaderElectionEventPublisher`를 생성하지 않습니다. 건설 중에 실시간 시계를 엽니다. 백엔드 이벤트 스트림이 필요한 애플리케이션은 게시자 수명 주기를 명시적으로 소유해야 합니다.
