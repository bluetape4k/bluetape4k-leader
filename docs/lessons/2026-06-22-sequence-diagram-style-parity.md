# 2026-06-22 시퀀스 다이어그램 스타일 패리티

## 맥락

README 다이어그램 새로 고침은 XML, 마커 및 기하학 검사를 반복적으로 통과했지만 여러 `*-sequence-*` 자산은 여전히 확립된 모범 사례 시퀀스 제품군처럼 보이지 않았습니다. 가장 명확한 로컬 참조는 `leader-redis-lettuce-sequence-*`였습니다. 손으로 쓴 제목, 참가자 헤더, 수직 생명선, 활성화 표시줄, 수평 메시지 레인, 알약 라벨, 차분한 `alt` 및 `else` 영역, 고정된 크기의 화살촉 등이 있었습니다.

실패 원인은 단일의 깨진 표시나 경로가 아니었습니다. 일부 다이어그램에는 유효한 SVG 및 렌더링된 PNG 출력이 있었지만 여전히 이전 순서도와 유사한 스타일이나 모듈별 스타일이 유지되었습니다. 밀착 시트는 전체 시퀀스 계열이 나란히 배치된 후에만 드리프트를 볼 수 있게 만들었습니다.

## 결정

시퀀스 스타일 패리티를 SVG 체크리스트뿐만 아니라 시각적 계약으로 취급하십시오. 이 저장소의 경우 향후 설계 노트에서 명시적인 예외를 기록하지 않는 한 `*-sequence-*`라는 다이어그램은 `leader-redis-lettuce-sequence-*` 제품군을 따라야 합니다.

복구 단계에서는 모든 시퀀스 다이어그램을 정규화하고 이전 로컬 스타일을 유지하려고 시도하는 대신 이상값을 다시 그렸습니다.

- `bluetape4k-leader-sequence-02/03`
- `leader-hazelcast-sequence-02/03`
- `leader-k8s-sequence-02`
- `leader-ktor-sequence-01`
- `leader-redis-redisson-sequence-02/03`
- `leader-spring-boot-sequence-01/02`

예제 시퀀스 세트도 동일한 시각적 문법으로 유지되었으므로 README에서는 모범 사례 시퀀스 다이어그램과 대략적인 예제별 변형을 혼합하지 않습니다.

## 검증

향후 시퀀스 새로 고침에는 다음 검사를 사용하세요.

```bash
xmllint --noout docs/images/readme-diagrams/*-sequence-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/*-sequence-*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/*-sequence-*.svg
git diff --check -- docs/images/readme-diagrams
```

그런 다음 `16x16`가 아닌 이상 시퀀스 마커를 거부하는 마커 감사를 실행하고, `markerUnits="userSpaceOnUse"`를 사용하고, `stroke-dasharray="none"`로 마커 본체를 강화합니다.

정적 검사로는 충분하지 않습니다. CairoSVG로 렌더링하고, 위험도가 높은 PNG를 전체 크기로 검사하고, `examples-*-sequence-*` 및 리더 `*-sequence-*` 다이어그램 모두에 대한 밀착 시트를 만듭니다. 축소판이 다른 다이어그램 계열처럼 보이는 경우 전체 크기 PNG를 다시 열고 완료를 보고하기 전에 다시 그립니다.

## 향후 지침

다음 사항만 검증하여 시퀀스 다이어그램 체크리스트 완료를 주장하지 마십시오.

- 파일 이름
- XML 유효성
- 마커 크기 속성
- 성공적인 PNG 렌더링
- 일반 형상 감사 성공

먼저 렌더링된 PNG를 현재 모범 사례 시퀀스 계열과 비교합니다. 예상되는 시각적 신호는 참가자 머리글, 생명줄, 활성화 표시줄, 수평 메시지 줄, 호출 회선에 위치하지 않는 알약 라벨, 차분한 분기 영역 및 점선 반환 메시지에도 있는 실선 화살촉입니다.

사용자 검토에서 시퀀스 다이어그램이 모범 사례와 일치하지 않는다고 보고하면 모든 `*-sequence-*` 자산을 제품군으로 감사하세요. 밀착 인화에 여전히 다른 로컬 스타일이 표시되는 경우 명명된 다이어그램 하나를 수정하는 것만으로는 충분하지 않습니다.
