# 리더 README 영웅 이미지

## 맥락

프로젝트 README에는 리더 선택 도메인을 표시하면서 bluetape4k 프로필 워크벤치 스타일과 일치하는 대표 이미지가 필요했습니다.

## 결정

생성된 래스터 자산을 `docs/assets/leader-election-workbench.png`에 저장하고 `README.md` 및 `README.ko.md` 모두에서 동일한 상대 경로를 참조합니다.

## 결과

이제 루트 README가 두 로케일의 소개 사본 앞에 리더 선출 워크벤치 그림과 함께 열립니다.

## 검증

- 자산이 `docs/assets` 아래에 PNG로 존재하는지 검증했습니다.
- README 참조 및 Markdown diff 형식을 검증했습니다.

## 향후 지침

현지화된 README 파일이 하나의 안정적인 상대 경로를 공유할 수 있도록 README 히어로 이미지를 `docs/assets` 내부에 보관하세요.
