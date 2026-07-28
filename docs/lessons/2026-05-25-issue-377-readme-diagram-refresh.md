# 문제 377 README 다이어그램 새로 고침

## 맥락

Milestone 0.2.2에는 현재 `bluetape4k-leader` 모듈 레이아웃에서 새로 고쳐진 README 방향 다이어그램이 필요했습니다. 루트 개요 다이어그램은 여전히 ​​`+2 more` 자리 표시자 뒤의 새로운 미리 보기 백엔드를 축소했으며 현재 DynamoDB, etcd, Consul 및 Kubernetes Lease 모듈을 명시적으로 표시하지 않았습니다.

## 결정

루트 README 개요 자산만 새로 고치고 기존 README 이미지 경로를 유지합니다. 저장소 전체 인어 기록 생성기는 이전 인어 블록을 현재 README 이미지 링크와 일치하지 않고 그룹 리더 시퀀스 소스로 단일 리더 시퀀스 자산을 다시 작성했기 때문에 최종 자산에 사용되지 않았습니다.

## 결과

이제 `root-readme-overview-01.svg`는 현재 모듈 맵을 열거합니다.

- BOM 및 핵심 API.
- 안정적인 백엔드 모듈.
- 미리보기 백엔드: DynamoDB, etcd, Consul 및 Kubernetes Lease.
- Ktor, Micrometer 및 Spring Boot 통합 모듈.
- `examples/` 디렉터리의 현재 예입니다.

일치하는 PNG는 `rsvg-convert`를 사용하여 SVG에서 재생성되었습니다.

## 검증

- `xmllint --noout docs/images/readme-diagrams/*.svg`
- `git diff --check`
- `rg -n 'docs/images/readme-diagrams/.*\.png' README.md README.ko.md`
- `sips -g pixelWidth -g pixelHeight docs/images/readme-diagrams/root-readme-overview-01.png`
- `root-readme-overview-01.png` 육안 검사: 잘린 라벨이나 오래된 `+ more` 자리 표시자가 없습니다.
- 작업 트리 감사 아티팩트:
  - `.omx/artifacts/issue-377-audit-readme-diagrams-worktree.log`
  - `.omx/artifacts/issue-377-audit-readme-diagram-quality-worktree.log`

글로벌 감사 스크립트는 클래스 다이어그램 `interface` 레이블 및 기존 K8s/Spring 텍스트 문제와 같이 관련되지 않은 루트가 아닌 다이어그램의 기존 결과와 함께 계속 종료됩니다. 변경된 루트 개요 자산에는 해당 작업 트리 아티팩트에 감사 결과가 없습니다.

## 향후 지침

과거의 Mermaid 블록에서 README 다이어그램을 재생성할 때 먼저 각 Mermaid 소스가 동일한 현재 이미지 링크에 매핑되는지 검증하세요. 개수 또는 순서가 일치하지 않으면 생성기 출력을 수락하는 대신 영향을 받는 자산을 중지하고 수동으로 재생성하십시오.
