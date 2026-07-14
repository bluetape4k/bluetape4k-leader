---
title: "MongoDB와 DynamoDB"
description: "원자적 document 갱신 또는 조건부 key-value 쓰기로 조정하고 TTL과 시계 의미를 따로 확인합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# MongoDB와 DynamoDB

원자적 document 갱신 또는 조건부 key-value 쓰기로 조정하고 TTL과 시계 의미를 따로 확인합니다.

## MongoDB

MongoDB 백엔드는 원자적 `findOneAndUpdate`로 소유권을 바꾸고 TTL index로 오래된 문서를 정리합니다. 블로킹과 suspend 구현을 제공하며 공통 단일 리더 연장 경로를 지원합니다. TTL 정리는 정확한 deadline에 실행되지 않으므로 정확성은 삭제 시점이 아니라 acquire 조건에 의존해야 합니다.

## DynamoDB

Preview 상태인 DynamoDB 백엔드는 conditional write와 논리 TTL을 사용합니다. contender가 동시에 몰릴 때의 capacity를 계산하고 throttling을 감시합니다. DynamoDB TTL 삭제는 비동기이므로 row가 실제로 사라졌는지가 아니라 저장된 expiry 조건으로 획득 여부를 판단합니다.

## 언제 선택할까

애플리케이션이 해당 저장소를 이미 필수 운영 의존성으로 사용할 때 선택합니다. 실제 서비스나 충실한 emulator에서 시계 차이, 조건 충돌, throttling, 재시도 모호성을 시험합니다. client timeout이 발생하면 쓰기 성공 여부를 알 수 없는 경우도 고려해야 합니다.

## 릴리스 소스

- [`leader-mongodb/README.ko.md`](../../../../leader-mongodb/README.ko.md)
- [`leader-dynamodb/README.ko.md`](../../../../leader-dynamodb/README.ko.md)
- [`examples/dynamodb-export/README.ko.md`](../../../../examples/dynamodb-export/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [백엔드 선택](../guides/backend-selection.md)
- [실패와 취소](../guides/failure-and-cancellation.md)
