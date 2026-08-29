# Issue #827: Prometheus connectivity alert producer 정합성

## 맥락

Prometheus alert rule은 `leader_backend_connectivity_total`의 `DOWN`,
`UNKNOWN`, `PROVIDER_EXCEPTION` 상태를 감시했지만 예제 애플리케이션이
해당 counter를 주기적으로 생성하지 않았다. AOP 계측은 실제 선출 호출이
있을 때만 connectivity를 기록하므로, idle 상태에서는 alert가 읽을 series가
존재하지 않았다.

## 결정

`PrometheusBackendConnectivityProbe`를 예제 애플리케이션에 추가해 기존
`lettuceLeaderElector`와 같은 `StatefulRedisConnection`을 사용하는
`InstrumentedLeaderElector`의 `backendDiagnosticsProvider`를 재사용한다. 별도
Redis client를 만들지 않고,
설정 가능한 fixed delay와 양수 millisecond timeout으로 주기적인 active probe를
실행한다. Spring context에는 diagnostics-capable elector가 여러 개이므로
probe는 명시적인 `prometheusBackendDiagnosticsProvider` qualifier를 사용한다.

현재 Lettuce provider의 계약도 그대로 보존한다. open client state만 확인한
결과는 backend round trip이 없으므로 `UNKNOWN`/`CLIENT_STATE_UNCONFIRMED`,
닫힌 client state는 `DOWN`/`DISCONNECTED`로 보고한다. 실제 `UP`과
`PROVIDER_EXCEPTION` 라벨은 instrumented provider 단위 테스트와 실제
Testcontainers Redis의 Lettuce connection 경로로 검증한다. 운영 compose와 같은
Prometheus `v2.55.1`의 `promtool test rules`를 사용해 세 alert의 firing,
`for` 지연, label/annotation/runbook 경로를 실행 평가한다.

## 결과

예제 설정과 영어/한국어 README에 probe 주기·timeout과 metric producer를
기록했다. 기존 alert rule과 runbook의 status/reason 의미를 변경하지 않았고,
실제 Redis connection 종료는 `DOWN`으로, `isOpen` 예외는 provider 계약대로
`UNKNOWN`/`PROVIDER_EXCEPTION`으로 계측된다. passive `NOT_CHECKED` diagnostics는
계속 counter를 만들지 않으며, 단위 테스트의 두 번의 probe 호출과 Spring context의
fixed delay scrape 검증(누적 sample `> 1.0`)은 probe가 반복 실행되어 counter가
증가함을 확인한다.

## 검증

- RED: producer가 없던 상태에서 `PrometheusScrapeTest`의
  `UNKNOWN`/`CLIENT_STATE_UNCONFIRMED` series 검증이 실패했다.
- GREEN: `prometheus-dashboard` 전체 테스트 13개, 실패 0, 오류 0, skipped 0.
- GREEN: `PrometheusBackendConnectivityProbeTest`에서 `UP`, `DOWN`,
  `UNKNOWN`, `PROVIDER_EXCEPTION`, timeout 전달과 0 timeout 거부, 반복 counter
  증가와 passive diagnostics 무계측을 검증했다.
- GREEN: `PrometheusScrapeTest`는 `demo.backend-probe.fixed-delay-ms=200`과
  `initial-delay-ms=0` Spring scheduler가 실제 Actuator scrape에서
  `UNKNOWN/CLIENT_STATE_UNCONFIRMED` counter를 최소 두 번 누적(`> 1.0`)했는지
  확인해 cadence producer를 검증했다.
- GREEN: `PrometheusLettuceConnectivityProbeTest`에서 실제 Redis Testcontainers의
  closed connection `DOWN`과 `isOpen` provider exception `UNKNOWN` 경로를
  `leader_backend_connectivity_total`로 확인했다.
- GREEN: `PrometheusAlertRulesTest`가 production `leader-alerts.yml`을
  `promtool test rules`로 실행해 DOWN/UNKNOWN/PROVIDER_EXCEPTION alert firing,
  `for` 지연과 runbook annotation을 검증했다.
- GREEN: `leader-micrometer` 132개와 `leader-redis-lettuce` 318개 테스트가
  모두 통과했다.
- GREEN: 예제 Detekt와 `git diff --check`가 통과했다.

## 향후 지침

1. Alert rule에 metric을 추가할 때는 동일 변경에서 runtime producer와
   scrape-level series 회귀 테스트를 함께 추가한다.
2. Connectivity 상태와 reason을 임의로 합치거나 `UNKNOWN`을 `DOWN`으로
   재작성하지 않는다. provider가 보장하지 않는 `UP`을 client-local 상태만으로
   주장하지 않는다.
3. 주기적인 active probe에는 양수 bounded timeout을 요구하고, 기존 backend
   connection·instrumentation 경로를 재사용해 중복 client와 metric registry를
   만들지 않는다.
