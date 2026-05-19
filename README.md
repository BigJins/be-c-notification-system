# BE-C Notification System

Spring Boot 기반 알림 발송 시스템 과제 구현입니다.  
이벤트 발생 시 사용자에게 `EMAIL` 또는 `IN_APP` 알림을 비동기 처리로 발송하며, 중복 방지, 재시도, 장애 복구, 다중 인스턴스 환경을 고려했습니다.

## 프로젝트 개요

- 알림 등록 API는 요청을 즉시 접수하고, 실제 발송은 별도 worker가 처리합니다.
- `IN_APP` 알림은 등록 트랜잭션 안에서 즉시 `SENT` 처리합니다.
- `EMAIL` 알림은 DB 기반 큐(`delivery_attempt`)를 통해 비동기 발송합니다.
- 실제 메시지 브로커(Kafka, SQS 등)는 사용하지 않았고, 현재는 DB polling worker로 구현했습니다.
- 대신 알림 등록, 작업 적재, 작업 획득, 실제 발송 책임을 분리해 두어 운영 환경에서는 polling/claim 부분을 브로커 consumer로 교체할 수 있게 설계했습니다.
- 동일 이벤트 중복 발송 방지를 위해 3계층 dedup을 적용했습니다.
  - 1차: `notification` 수준 dedup
  - 1.5차: `delivery` 수준 dedup
  - 2차: `Idempotency-Key` 기반 replay 방지
- 서버 재시작, 일시 장애, 다중 인스턴스 동시 처리, stuck recovery까지 고려했습니다.

## 기술 스택

- Java 21
- Spring Boot 3.4.5
- Spring Web / Validation / Data JPA / Security / Actuator
- PostgreSQL 16
- Flyway
- Micrometer + Prometheus
- Springdoc OpenAPI
- JUnit 5 / AssertJ / Awaitility
- Testcontainers
- ArchUnit

## 실행 방법

### 사전 요구사항

- Java 21
- Docker

### 실행

```bash
docker compose up -d postgres
./gradlew bootRun
```

Docker 관련 참고:

- 정상 실행 시 필요한 DB 컨테이너는 `docker compose`가 띄우는 PostgreSQL 1개뿐입니다.
- 기본 이름은 보통 `assignment-postgres-1` 입니다.
- `hopeful_aryabhata` 처럼 별도로 떠 있는 다른 PostgreSQL 컨테이너는 이 프로젝트에 필요하지 않습니다.
- 이미 다른 PostgreSQL 컨테이너가 `5432` 포트를 점유 중이면 먼저 중지한 뒤 실행하는 것이 안전합니다.

기본 접속 정보:

- App: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Prometheus endpoint: `http://localhost:8080/actuator/prometheus`

기본 DB 설정:

- DB host: `localhost`
- DB port: `5432`
- DB name: `notification`
- DB user: `notification`
- DB password: `notification`

## 요구사항 해석 및 가정

### 요구사항 해석

- 알림 발송 실패가 비즈니스 트랜잭션에 영향을 주면 안 되므로, 등록과 발송을 분리했습니다.
- 단, “예외를 그냥 무시”하지 않기 위해 발송 시도는 `delivery_attempt` 로 영속화하고, 상태 전이와 실패 사유를 남기도록 설계했습니다.
- “동일 이벤트 중복 발송 방지”는 헤더 없는 일반 요청도 막아야 하므로 `event_id + recipient_id + type` 수준 dedup을 기본으로 잡았습니다.
- 실제 메시지 브로커는 쓰지 않되 운영 환경으로 전환 가능해야 하므로 DB polling worker + lease + reaper 구조로 구현했습니다.
- 비즈니스 규칙은 `DeliveryRelayService` 와 상태 전이에 남기고, 작업 획득 메커니즘만 교체 가능하게 두었기 때문에 향후 Kafka/SQS consumer로의 전환 범위를 인프라 계층으로 제한할 수 있습니다.

### 주요 가정

- `Notification` 은 논리적 이벤트 1건이며, 채널은 `Delivery` 의 속성입니다.
- `IN_APP` 은 외부 네트워크 전송이 아니라 내부 알림함 적재로 간주해 등록 즉시 `SENT` 처리합니다.
- `EMAIL` 은 mock adapter로 처리하며, 실제 SMTP/SES 연동은 확장 포인트로 남겼습니다.
- `read_at` 은 사용자가 알림 자체를 읽었는지에 대한 상태이며, `IN_APP` 전달 성공이 있어야만 읽음 처리 가능합니다.

### 개선 의견

- 실제 운영 전환 시에는 DB polling 대신 Kafka/SQS 같은 브로커로 바꾸는 것이 적절합니다.
- 현재 `X-Admin-Token` 기반 관리자 보호는 과제 범위에 맞춘 단순 구현이며, 실제 운영이라면 OAuth2/JWT가 적합합니다.
- 장기 운영에서는 6개월 보관 정책용 cleanup worker, real SMTP, bounce webhook, tracing이 추가되어야 합니다.

### 요구사항 ↔ 구현 매핑

| 요구사항 | 구현 |
|---|---|
| 알림 등록 API | `POST /v1/notifications` |
| 알림 상태 조회 | `GET /v1/notifications/{id}` |
| 사용자 알림 목록 조회 | `GET /v1/notifications?recipientId=...&read=...` |
| 읽음 처리 | `PATCH /v1/notifications/{id}/read` |
| 비동기 처리 | `delivery_attempt` 저장 후 `DispatchWorker` 가 별도 처리 |
| 중복 발송 방지 | event dedup + channel dedup + `Idempotency-Key` |
| 재시도 / 최종 실패 | `RetryPolicy`, `DeliveryRelayService`, `DEAD` 상태 |
| stuck 복구 | `ReaperWorker` + `claimed_until` lease |
| 서버 재시작 후 재처리 | DB에 `delivery_attempt` 영속화 후 worker 재pickup |
| 다중 인스턴스 중복 처리 방지 | `FOR UPDATE SKIP LOCKED` + claim lease |

과제 설명의 표면 형태와 다른 부분:

- 과제 문구는 단일 `channel` 중심으로 읽힐 수 있지만, 본 구현은 fan-out 을 명시적으로 표현하기 위해 `channels[]` 를 사용했습니다.
- API prefix 는 `/api/...` 대신 버저닝을 위해 `/v1/...` 를 사용했습니다.
- `notificationType` 대신 `type`, `referenceData` 대신 자유형 `payload` + `eventId` 를 사용했습니다.

## 설계 결정과 이유

### 1. 단일 모델

- JPA Entity와 Domain Model을 분리하지 않고 하나의 모델로 유지했습니다.
- 이유:
  - 과제 범위에서 매핑 boilerplate를 줄일 수 있음
  - 도메인 상태 전이와 영속 모델을 한 곳에서 읽을 수 있음

### 2. 3 Aggregate 구조

- `Notification`: 알림 이벤트 자체
- `Delivery`: 채널별 전달 상태
- `DeliveryAttempt`: 재시도/claim/recovery를 위한 작업 단위

이렇게 분리한 이유:

- 사용자에게 보여줄 상태와 worker 재시도 상태를 분리할 수 있음
- `EMAIL` retry session을 별도 row로 추적 가능
- admin retry 시 새 시도 row를 발급할 수 있음

### 3. 3계층 dedup

- 1차: `UNIQUE(event_id, recipient_id, type)`
- 1.5차: `UNIQUE(notification_id, channel)`
- 2차: `Idempotency-Key`

이유:

- 헤더 없는 일반 요청도 중복 방지해야 함
- fan-out 과정에서 채널 중복도 막아야 함
- API replay safety는 별도 계층으로 보강해야 함

### 4. 비동기 처리 구조

- API는 등록만 수행
- worker가 `delivery_attempt` 의 `READY` row를 polling하여 claim 후 처리
- claim 시 `FOR UPDATE SKIP LOCKED` + lease(`claimed_until`) 사용
- stuck row는 reaper가 복구

이유:

- 실제 브로커 없이도 운영형 비동기 구조를 흉내낼 수 있음
- 다중 인스턴스 환경에서도 중복 처리를 줄일 수 있음
- 서버 재시작 후에도 미처리 row가 남아 유실되지 않음

### 5. retry / failure 정책

- 일시 장애는 재시도
- 영구 실패는 즉시 실패 종료
- 최대 재시도 초과 시 `DEAD`
- 실패 사유는 `delivery`, `delivery_attempt` 양쪽에 기록

## 미구현 / 제약사항

- 실제 이메일 발송은 구현하지 않았고 mock adapter로 대체했습니다.
- 실제 메시지 브로커는 사용하지 않았습니다.
- 발송 예약 기능은 미구현입니다.
- 타입별 알림 템플릿 관리 기능은 미구현입니다.
- multi-device read sync는 단일 `read_at` 모델로 단순화했습니다.
- `same Idempotency-Key + different request body` 동시 경쟁은 `first-write-wins` 정책으로 정리했고, 더 강한 직렬화는 구현하지 않았습니다.

## AI 활용 범위

- 설계 초안 브레인스토밍
  - 알림 도메인을 `Notification / Delivery / DeliveryAttempt` 로 나누는 구조를 검토할 때, 대안 비교와 장단점 정리에 AI를 보조적으로 활용했습니다.
  - 특히 channel을 notification identity로 둘지, delivery transport로 둘지 같은 모델링 분기에서 아이디어 정리와 질문 목록 작성에 도움을 받았습니다.

- 상태 전이 / aggregate 분리 검토
  - `PENDING / SENT / DEAD`, `READY / IN_PROGRESS / DONE / FAILED` 같은 상태 전이 규칙을 빠뜨리지 않도록 체크리스트 용도로 활용했습니다.
  - aggregate 경계, dedup 계층, retry 책임 분리를 검토할 때 누락 가능성이 있는 예외 케이스를 점검하는 용도로 사용했습니다.

- 테스트 보강 아이디어 정리
  - 동시성 dedup, idempotency replay/conflict, stuck recovery, retry max attempts, admin retry 같은 시나리오를 더 촘촘히 검증하기 위한 테스트 아이디어를 정리하는 데 활용했습니다.
  - 단위 테스트와 통합 테스트를 어떤 층위로 나눌지, 어떤 실패 케이스를 추가하면 좋은지 제안받고 실제 반영 여부는 직접 판단했습니다.

- 문서 / ADR 초안 작성 보조
  - README, 아키텍처 설명, ADR-0001/0002 초안 문구를 정리할 때 표현 보조와 구조화에 활용했습니다.
  - 제출용 문서에서 요구사항 해석, 설계 이유, 미구현 범위를 더 읽기 쉽게 정리하는 데 도움을 받았습니다.

- 디버깅 / 제출 전 점검 보조
  - Swagger/OpenAPI 렌더링 문제, 수동 검증 시나리오 흐름, README 제출 형식 점검 같은 마감 전 체크리스트 성격의 작업에 보조적으로 활용했습니다.
  - 다만 실제 오류 원인 확인, 의존성 수정, 수동 API 검증, Docker 재기동 후 end-to-end 재확인은 직접 수행했습니다.

최종 설계 결정, 코드 수정, 테스트 검증, 요구사항 충족 여부 판단은 모두 직접 확인하며 반영했습니다.  
즉 AI는 아이디어 정리와 초안 보조 도구로 사용했고, 구현 책임과 품질 판단 책임은 작성자인 제가 직접 가졌습니다.

## API 목록 및 예시

### 1. 알림 등록

`POST /v1/notifications`

요청 예시:

```json
{
  "eventId": "payment-1001",
  "recipientId": "user-1",
  "type": "PAYMENT_CONFIRMED",
  "channels": ["EMAIL", "IN_APP"],
  "payload": {
    "subject": "결제가 완료되었습니다",
    "body": "수강 신청이 확정되었습니다"
  }
}
```

응답 예시:

```json
{
  "id": "3f6f3cbe-5b31-4f4f-91a8-0c3d0e06836e",
  "eventId": "payment-1001",
  "recipientId": "user-1",
  "type": "PAYMENT_CONFIRMED",
  "readAt": null,
  "deliveries": [
    {
      "channel": "EMAIL",
      "state": "PENDING"
    },
    {
      "channel": "IN_APP",
      "state": "SENT"
    }
  ]
}
```

헤더:

- 신규 등록: `202 Accepted`
- 이벤트 중복: `X-Event-Duplicate: true`
- idempotency replay: `X-Idempotent-Replay: true`

### 2. 단건 조회

`GET /v1/notifications/{id}`

### 3. 사용자 알림 목록 조회

`GET /v1/notifications?recipientId=user-1&read=false&page=0&size=20`

### 4. 읽음 처리

`PATCH /v1/notifications/{id}/read`

- `IN_APP` 전달 성공 건에 대해서만 허용

### 5. 관리자 재시도

`POST /v1/admin/notifications/{id}/retry`

헤더:

```text
X-Admin-Token: dev-token-do-not-use-in-prod
```

## 평가자용 검증 시나리오

### 1. 자동 검증

가장 빠른 검증 방법은 전체 테스트 실행입니다.

```bash
./gradlew test
```

현재 기준:

- 39 test classes
- 81 tests
- 81/81 pass

대표 테스트:

- 사용자 대표 흐름: [NotificationFlowIT.java](/C:/assignment/src/test/java/com/livenotification/integration/tier3/NotificationFlowIT.java) `representativeUserScenario_endToEnd`
- 동시 요청 dedup: [ConcurrentDedupIT.java](/C:/assignment/src/test/java/com/livenotification/integration/dedup/ConcurrentDedupIT.java) `concurrentSameEvent_only1Accepted_othersReturnDuplicate`
- idempotency replay/conflict: [IdempotencyReplayIT.java](/C:/assignment/src/test/java/com/livenotification/integration/dedup/IdempotencyReplayIT.java), [IdempotencyConflictIT.java](/C:/assignment/src/test/java/com/livenotification/integration/dedup/IdempotencyConflictIT.java)
- retry / 최종 실패: [RetrySuccessIT.java](/C:/assignment/src/test/java/com/livenotification/integration/retry/RetrySuccessIT.java), [RetryMaxAttemptsIT.java](/C:/assignment/src/test/java/com/livenotification/integration/retry/RetryMaxAttemptsIT.java), [PermanentFailureIT.java](/C:/assignment/src/test/java/com/livenotification/integration/retry/PermanentFailureIT.java)
- 복구 / 재기동 / 다중 인스턴스: [StuckRecoveryIT.java](/C:/assignment/src/test/java/com/livenotification/integration/recovery/StuckRecoveryIT.java), [DurabilityIT.java](/C:/assignment/src/test/java/com/livenotification/integration/recovery/DurabilityIT.java), [MultiWorkerIT.java](/C:/assignment/src/test/java/com/livenotification/integration/recovery/MultiWorkerIT.java)

### 2. 수동 검증

가장 쉬운 수동 검증 방법은 Swagger UI입니다.

접속 주소:

- `http://localhost:8080/swagger-ui.html`
- 또는 `http://localhost:8080/swagger-ui/index.html`

서버 실행:

```bash
docker compose up -d postgres
./gradlew bootRun
```

아래 Step 1~7만 따라가면 주요 요구사항을 대부분 확인할 수 있습니다.

#### Step 1. API 목록 확인

Swagger UI에서 아래 API가 보이는지 확인합니다.

- `POST /v1/notifications`
- `GET /v1/notifications/{id}`
- `GET /v1/notifications`
- `PATCH /v1/notifications/{id}/read`
- `POST /v1/admin/notifications/{id}/retry`

#### Step 2. 알림 등록이 즉시 수락되는지 확인

`POST /v1/notifications` 를 열고 `Try it out` 을 누른 뒤 아래 body로 `Execute` 합니다.

```json
{
  "eventId": "demo-1",
  "recipientId": "u1",
  "type": "PAYMENT_CONFIRMED",
  "channels": ["EMAIL", "IN_APP"],
  "payload": {
    "subject": "hello",
    "body": "world"
  }
}
```

확인할 것:

- 응답 코드 `202`
- 응답 헤더 `X-Event-Duplicate: false`
- 응답 body에 `deliveries` 2개 존재
- `IN_APP` 는 `SENT`
- `EMAIL` 은 처음엔 `PENDING`

메모:

- 이후 단계에서 쓰기 위해 response body의 `id` 값을 복사해 둡니다.

#### Step 3. 상태 조회로 비동기 처리 확인

`GET /v1/notifications/{id}` 를 열고 방금 복사한 `id` 로 조회합니다.

즉시 조회 시 확인할 것:

- `IN_APP = SENT`
- `EMAIL = PENDING`

몇 초 뒤 다시 같은 API를 실행해서 확인할 것:

- `EMAIL = SENT`

이 단계에서 “등록 API는 즉시 수락하고, 실제 이메일 발송은 worker가 나중에 처리한다”는 비동기 구조를 확인할 수 있습니다.

#### Step 4. 목록 조회와 읽음 필터 확인

`GET /v1/notifications` 를 열고 아래 값으로 실행합니다.

- `recipientId = u1`
- `read = false`
- `page = 0`
- `size = 20`

확인할 것:

- `u1` 의 알림 목록이 조회됨
- `read=false` 필터가 적용됨

#### Step 5. 읽음 처리 확인

`PATCH /v1/notifications/{id}/read` 를 열고 같은 `id` 로 실행합니다.

확인할 것:

- 응답 코드 `204`

그 다음 `GET /v1/notifications/{id}` 를 다시 조회해서 확인할 것:

- `readAt` 이 채워짐

#### Step 6. 동일 이벤트 중복 발송 방지 확인

다시 `POST /v1/notifications` 로 돌아가서 Step 2의 JSON을 그대로 한 번 더 실행합니다.

확인할 것:

- 응답 코드 `200`
- 응답 헤더 `X-Event-Duplicate: true`

#### Step 7. 영구 실패 + 관리자 재시도 확인

먼저 `POST /v1/notifications` 로 아래 body를 전송합니다.

```json
{
  "eventId": "demo-dead-1",
  "recipientId": "u1",
  "type": "PAYMENT_CONFIRMED",
  "channels": ["EMAIL"],
  "payload": {
    "subject": "dead",
    "body": "world",
    "x_test_failure": "permanent"
  }
}
```

확인할 것:

- 잠시 후 `GET /v1/notifications/{id}` 조회 시 `EMAIL` delivery 가 `DEAD`

그 다음 `POST /v1/admin/notifications/{id}/retry` 를 열고:

- Path의 `id` 입력
- Header에 `X-Admin-Token: dev-token-do-not-use-in-prod` 입력

실행 후 확인할 것:

- 응답 코드 `204`

#### 추가 확인 1. Idempotency-Key replay / conflict

이 시나리오는 Swagger보다 CLI가 더 편합니다.

- 같은 `Idempotency-Key` + 같은 body -> replay (`200`)
- 같은 `Idempotency-Key` + 다른 body -> conflict (`409`)

이 동작은 [IdempotencyReplayIT.java](/C:/assignment/src/test/java/com/livenotification/integration/dedup/IdempotencyReplayIT.java), [IdempotencyConflictIT.java](/C:/assignment/src/test/java/com/livenotification/integration/dedup/IdempotencyConflictIT.java) 로 자동 검증됩니다.

#### 추가 확인 2. 운영 시그널

브라우저에서 아래 주소를 열면 메트릭을 직접 볼 수 있습니다.

- `http://localhost:8080/actuator/prometheus`

검색 키워드:

- `notification`
- `delivery`
- `idempotency`
