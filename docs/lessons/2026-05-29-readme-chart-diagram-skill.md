# README 차트 다이어그램 기술 강의

## 맥락

README 벤치마크 차트는 `bluetape4k-diagram` 시각적 계약에서 벗어났습니다. 일부 SVG 소스는 비기술 대체 글꼴을 사용했으며 차트 색상은 필요한 파스텔 톤보다 더 강했습니다.

## 결정

하나의 공유 파스텔 팔레트와 명시적인 글꼴 역할을 사용하여 모든 `docs/images/readme-charts/*.{svg,png}` 자산을 재생성합니다.

- 제목과 눈에 띄는 라벨을 위한 `Architects Daughter`.
- 자막, 눈금, 값, 범례 및 메모용 `Comic Mono`입니다.

로컬 렌더링의 경우 `fc-match`에만 의존하지 마십시오. `fc-list` 및 `fc-scan`가 설치된 글꼴 파일을 찾는 경우에도 이 컴퓨터는 `fc-match 'Architects Daughter'`에 대한 대체를 보고할 수 있습니다. 생성된 SVG는 필요할 때 검색된 글꼴 파일을 명시적으로 바인딩해야 합니다.

## 결과

이제 모든 README 차트 자산은 SVG 소스를 파스텔 차트 색상 및 명시적 기술 글꼴과 일치시켜 지원되는 PNG 임베드를 사용합니다.

## 검증

- `xmllint --noout docs/images/readme-charts/*.svg`
- `git diff --check`
- `rsvg-convert`를 사용하여 모든 차트 PNG를 렌더링했습니다.
- 클리핑, 간격, 글꼴 역할 및 명백한 중복을 위한 6개 차트 밀착 인화를 미리 보았습니다.

## 미래의 에이전트

bluetape4k 글꼴이 누락되었다고 주장하기 전에 `fc-list`, 직접 글꼴 경로 및 `fc-scan`를 검증하세요. 글꼴이 표시되면 `fc-match`가 대체를 반환했기 때문에 다른 글꼴로 전환하는 대신 해당 글꼴을 사용하도록 렌더링을 구성합니다.
