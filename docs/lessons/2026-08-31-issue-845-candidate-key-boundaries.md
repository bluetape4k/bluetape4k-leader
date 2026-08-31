# 후보 키는 문자열 연결이 아니라 경계를 인코딩해야 한다

## 맥락

Issue [#845](https://github.com/bluetape4k/bluetape4k-leader/issues/845)는 `lockName`과 `nodeId`를 `:`로 연결한 Redis 키가 서로 다른 후보를 같은 키로 만들 수 있음을 확인했다. `lockName`은 `:`를 허용하고 기본 `nodeId`도 `hostname:pid` 형식이므로 구분자만으로 두 필드의 경계를 복원할 수 없다.

## 놓친 가정과 근거

`keyPrefix:lockName:nodeId`가 충분히 구분된다는 가정이 잘못됐다. `(a, b:c)`와 `(a:b, c)`가 같은 키가 되는 회귀 테스트가 이를 재현했다. 기존 키를 그대로 읽으면 다른 lock의 값이나 Redis 자료형까지 후보로 해석할 수 있다는 점도 함께 드러났다.

## 결정

- 새 키는 version, record type, UTF-8 byte length를 포함해 각 필드의 경계를 보존한다.
- 새 namespace는 유효한 `lockName`과 겹칠 수 없는 구분자로 legacy namespace와 분리한다.
- legacy 후보는 exact `nodeId`와 Redis key type을 확인한 경우에만 TTL을 보존해 새 키로 이관한다.
- blocking과 suspend registry에 같은 codec과 migration 규칙을 적용한다.

## 결과와 검증

커밋 `f692f1d5`에서 충돌 쌍, `hostname:pid`, `list`, `refresh`, `updateResult`, `unregister` 격리를 고정했다. Lettuce 전체 325개 테스트와 `detekt`, binary compatibility 검사가 통과했다.

## 재발 방지

두 개 이상의 사용자 입력을 저장 키로 조합할 때는 delimiter 문자열 연결을 사용하지 않는다. 설계 검토에서 injectivity, namespace 분리, legacy 자료형, TTL 보존을 먼저 확인하고 delimiter를 각 필드에 넣은 충돌 쌍을 RED 테스트로 작성한다.
