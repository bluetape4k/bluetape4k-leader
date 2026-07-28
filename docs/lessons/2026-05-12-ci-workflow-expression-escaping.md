# 배운 교훈 — CI 워크플로 표현 이스케이프 처리(2026-05-12)

## 맥락

`leader-exposed-r2dbc` 워크플로 작업이 추가된 후 작업을 생성하기 전에 `ci.yml`가 failure했습니다. GitHub는 다음과 같이 보고했습니다.

```text
Unexpected symbol: '\'leader-exposed\''
```

GitHub Actions 표현식 내에서 쉘/Python 스타일 이스케이프 따옴표를 사용한 깨진 줄:

```yaml
if: ${{ needs.changes.outputs[\'leader-exposed\'] == \'true\' }}
```

## 결정

일반 GitHub Actions 표현식 인용을 사용하세요.

```yaml
if: ${{ needs.changes.outputs['leader-exposed'] == 'true' }}
```

선택한 YAML 인용 스타일에서 요구하지 않는 한 `${{ ... }}` 표현식 또는 YAML 스칼라 값에서 작은따옴표를 이스케이프하지 마십시오.

## 결과

세 가지 `leader-exposed-r2dbc` CI 작업에서 잘못된 형식의 표현이 수정되었으며 아티팩트 경로의 불필요한 이스케이프도 동일하게 수정되었습니다.

## 검증

- `actionlint .github/workflows/ci.yml`
- `actionlint .github/workflows/nightly.yml`
- `git diff --check`
- `rg -n "\\\\'" .github/workflows`가 일치하는 항목을 반환하지 않았습니다.

## 미래의 규칙

모든 CI 작업 흐름 편집은 병합 전에 `actionlint`를 실행해야 합니다. 작업이 없는 0초 GitHub Actions failure는 일반적으로 Gradle/테스트 failure가 아니라 워크플로 구문 분석 또는 검증 failure를 의미합니다.
