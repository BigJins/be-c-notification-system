# BE-C Notification System

LiveClass 채용 과제 — 알림 발송 시스템 구현. Spring Boot 3.4 + Java 21 + PostgreSQL 16.

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택](#2-기술-스택)
3. [실행 방법](#3-실행-방법)
4. [명세 매핑](#4-명세-매핑)
5. [핵심 설계 결정](#5-핵심-설계-결정)
6. [테스트 전략](#6-테스트-전략)
7. [부하 시험 결과](#7-부하-시험-결과)
8. [Path C 확장 sketch](#8-path-c-확장-sketch)
9. [AI 활용 문서](#9-ai-활용-문서)

---

## 1. 프로젝트 개요

<!-- TBD: Task 34 — Path B 채널 fan-out 5분 컷 8 step 시나리오 (POST→GET→worker tick→dedup replay→Swagger→Grafana). design §8 "5분 데모 sequence" 기반. -->

## 2. 기술 스택

<!-- TBD: Task 34 — Java 21 / Spring Boot 3.4 / PostgreSQL 16 / Flyway / Lombok / Testcontainers / Micrometer+Prometheus / ArchUnit / Springdoc OpenAPI / k6 의존성 목록 + 선택 근거. -->

## 3. 실행 방법

<!-- TBD: Task 34 — docker compose up postgres + ./gradlew bootRun + curl 예제 4개 (신규 등록 202, 중복 이벤트 200, Idempotency-Key replay 200, markRead 204) + Swagger UI URL. -->

---

## 4. 명세 매핑

LiveClass 명세 5조건 ↔ 본 과제 구현 파일/메서드.

| # | 명세 조건 | 구현 |
|---|---|---|
| 1 | **알림 등록 + 채널 fan-out** — `POST /v1/notifications` 단일 호출로 notification 1건 + channel 수만큼 delivery 생성. IN_APP 은 등록 시점 즉시 SENT, EMAIL 은 worker 가 폴링해 발송. 응답 body 에 `deliveries[]` 포함. | `NotificationController.register` → `NotificationService.register` → `NotificationRegistrationStore.insertOrLoad` (notification INSERT) → `DeliveryRegistrar.scheduleFor` (delivery + delivery_attempt INSERT). IN_APP: `Delivery.forInApp` + `DeliveryAttempt.completedFor` (state=SENT/DONE, 동일 트랜잭션). EMAIL: `Delivery.forEmail` + `DeliveryAttempt.readyFor` (state=PENDING/READY). 파일: `src/main/java/com/livenotification/notification/adapter/in/web/NotificationController.java`, `src/main/java/com/livenotification/notification/application/NotificationService.java`, `src/main/java/com/livenotification/delivery/adapter/in/registrar/DeliveryRegistrarAdapter.java` |
| 2 | **3-Layer Dedup** — 동일 이벤트 중복 발송 방지 (1차·1.5차) + API 재전송 안전성 (2차). 응답 헤더 `X-Event-Duplicate` / `X-Idempotent-Replay` 독립 노출. | 1차: `NotificationRegistrationStore.insertIfAbsent` — `INSERT ... ON CONFLICT (event_id, recipient_id, type) DO NOTHING RETURNING id`. 1.5차: `uq_delivery_per_channel (notification_id, channel)` DB 제약 (DeliveryRegistrarAdapter). 2차: `IdempotencyService.persistIfAbsent` — `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. 헤더: `NotificationController` → `RegisterResult.eventDuplicate()` / `RegisterResult.replay()`. 파일: `src/main/java/com/livenotification/notification/application/NotificationRegistrationStore.java`, `src/main/java/com/livenotification/idempotency/application/IdempotencyService.java` |
| 3 | **Retry + DLQ** — 전송 실패 시 backoff retry, 영구 실패 fast-fail, max attempts 초과 시 delivery DEAD. `DeliveryAttempt` row 가 retry 큐 역할. | (Phase 3 구현 예정) `RetryPolicy.nextBackoff` + `DeliveryRelayService.relay` — Transient failure → attempt_count++ + next_attempt_at 갱신 (READY 재진입). Permanent failure / attempt_count >= max → delivery DEAD. `DispatchWorker` — `SELECT ... FOR UPDATE SKIP LOCKED` 폴링 + Virtual Thread. 파일 (Phase 3): `src/main/java/com/livenotification/delivery/application/` (DeliveryRelayService), `src/main/java/com/livenotification/delivery/domain/` (RetryPolicy) |
| 4 | **운영 가능성** — admin retry (DEAD delivery 복귀), stuck 행 복구 (reaper), Micrometer metrics 11개 (dispatch throughput / latency / queue depth / dead count 포함). | (Phase 4 구현 예정) `ReaperWorker` (`@Scheduled(30s)`) — `claimed_until` lease 만료 행 복구. `AdminRetryService` — `DeliveryRetryRegistrar.issueNewAttempt` + `delivery.markPending` (attempt_count 유지). Micrometer counter/timer/gauge 11개. 파일 (Phase 4): `src/main/java/com/livenotification/delivery/adapter/in/scheduler/`, `src/main/java/com/livenotification/admin/` |
| 5 | **재기동 안전 (Durability)** — 서버 재기동과 무관하게 발송 의도 손실 없음. Outbox 패턴 (notification + delivery + delivery_attempt 동일 트랜잭션 commit). Flyway 단일 init migration. DB state 가 진실의 원천. | `NotificationService.register` — `@Transactional` 한 트랜잭션 안에 notification 1 + delivery N + delivery_attempt N + idempotency_record 0~1 commit (최대 6 INSERT). `DeliveryRegistrarAdapter` — `@Transactional(propagation = MANDATORY)` 로 호출자 트랜잭션 강제. Flyway `V1__init.sql` 단일 파일. 재기동 후 `DispatchWorker` 가 READY 행 재폴링. 파일: `src/main/java/com/livenotification/notification/application/NotificationService.java`, `src/main/java/com/livenotification/delivery/adapter/in/registrar/DeliveryRegistrarAdapter.java`, `src/main/resources/db/migration/V1__init.sql` |

---

## 5. 핵심 설계 결정

### 5.1 3-Layer Dedup

**Signature**: "동일 이벤트 중복 발송 방지는 notification-level UNIQUE 와 delivery-level UNIQUE 가 함께 보장하고, API 재전송 안전성은 별도의 idempotency key 로 보완한다."

| Layer | UNIQUE 제약 | 동작 시점 | 응답 헤더 |
|---|---|---|---|
| 1차 | `uq_notification_event (event_id, recipient_id, type)` | notification INSERT 시점 — `NotificationRegistrationStore.insertIfAbsent` 가 `INSERT ... ON CONFLICT DO NOTHING RETURNING id` 실행 | `X-Event-Duplicate: true` (HTTP 200) |
| 1.5차 | `uq_delivery_per_channel (notification_id, channel)` | delivery INSERT 시점 — `DeliveryRegistrarAdapter.scheduleFor` 내 fan-out 루프. 같은 channel 두 번 요청 거부 | (헤더 없음, 내부 DB 제약으로 처리) |
| 2차 | `idempotency_record.idempotency_key` PK + 24h TTL | 요청 헤더 `Idempotency-Key` 있을 때만. `NotificationService.register` 진입 시 `IdempotencyService.lookupCurrent` 호출 | `X-Idempotent-Replay: true` (HTTP 200) |

#### 응답 헤더 독립성

`X-Event-Duplicate` 와 `X-Idempotent-Replay` 는 독립 boolean fact. 한 헤더의 값이 다른 헤더를 결정하지 않는다.

| 시나리오 | X-Event-Duplicate | X-Idempotent-Replay | HTTP 상태 |
|---|---|---|---|
| 신규 event + Idempotency-Key 없음 | false | false | 202 |
| 중복 event + Idempotency-Key 없음 | true | false | 200 |
| HitSameHash replay (Idempotency-Key 재사용) | true | true | 200 |
| 중복 event + Idempotency-Key 재사용 | true | true | 200 |

설명: `IdempotencyResult.HitSameHash` 경로에서 `loadReplay(id, true)` 를 호출 — `eventDuplicate=true` 를 unconditional 하게 세팅. underlying event 가 이미 등록되어 있다는 사실을 정직하게 헤더에 노출. `IdempotencyReplayIT` Case A~D 참조.

코드 위치: `NotificationService.register` 내 `case IdempotencyResult.HitSameHash hit -> { return loadReplay(new NotificationId(hit.targetId()), true); }` (`src/main/java/com/livenotification/notification/application/NotificationService.java`).

#### 1차 dedup 의 동시성 race 처리

`NotificationRegistrationStore.insertIfAbsent` 는 `INSERT ... ON CONFLICT (event_id, recipient_id, type) DO NOTHING RETURNING id` 패턴. 100 thread 동시에 같은 `(event_id, recipient_id, type)` 요청 시 → 1건만 INSERT 성공, 나머지 99건은 `RETURNING id` 가 빈 결과 → `findByEventIdAndRecipientIdAndType` 으로 기존 row fetch → 200 + `X-Event-Duplicate: true`. `ConcurrentDedupIT` 에서 검증.

JPA persistence-context 가 unique violation 으로 오염되지 않는 장점 — `NamedParameterJdbcTemplate` 직접 호출이므로 Hibernate session 을 거치지 않는다. `try/catch DataIntegrityViolationException` 패턴보다 안정적.

```java
// NotificationRegistrationStore.insertIfAbsent
return jdbcTemplate.query("""
    INSERT INTO notification (id, event_id, recipient_id, type, payload, created_at, updated_at)
    VALUES (:id, :eventId, :recipientId, :type, CAST(:payload AS jsonb), :createdAt, :updatedAt)
    ON CONFLICT (event_id, recipient_id, type) DO NOTHING
    RETURNING id
    """, params, rs -> rs.next() ? (UUID) rs.getObject("id") : null);
```

#### 2차 dedup atomic write

`IdempotencyService.persistIfAbsent` 도 동일 패턴 — `INSERT INTO idempotency_record ... ON CONFLICT (idempotency_key) DO NOTHING`. 100 thread 동시 같은 key 요청 시 1건만 INSERT, 나머지는 no-op. `lookupCurrent` 의 read-before-write race 는 `MANDATORY` 트랜잭션 안에서 read → insert 순서로 처리.

### 5.2 channel = transport (Path B)

<!-- TBD: Task 28 — Notification = 논리적 사건, Delivery = 채널별 전달 의도, channel 은 notification identity 가 아닌 delivery 의 컬럼. v3.3 channel-as-identity 와의 설계 tradeoff 비교. -->

### 5.3 동시성 3중 방어

<!-- TBD: Task 23 — SELECT ... FOR UPDATE SKIP LOCKED (다중 인스턴스), Semaphore(N=16) + Virtual Thread (인스턴스 내), claimed_until lease 30s + ReaperWorker (stuck 복구). DispatchWorker 코드 참조. -->

---

## 6. 테스트 전략

<!-- TBD: Task 23 (Tier 분류 표: T1 동시성 IT / T2 기능 IT / T3 통합 IT + 미적용 표) + Task 30 (Phase 5 T3 IT 6개 추가) + Task 31 (ArchUnit 6 rules). 현재 Phase 2 완료: 도메인 invariant 단위 16개 + 통합 IT 6개 (ConcurrentDedupIT, EventDedupNoHeaderIT, IdempotencyReplayIT, IdempotencyConflictIT, DeliveryPerChannelDedupIT, MarkReadEmailOnlyRejectIT). -->

---

## 7. 부하 시험 결과

<!-- TBD: Task 34 — k6 `dedup-race.js` (100 VU 동일 키 동시 → 1건만 생성 검증) + `throughput.js` (100 RPS 60초 지속 → P95 응답시간 + 폴링 지연) 결과 표. -->

---

## 8. Path C 확장 sketch

<!-- TBD: Task 28 — 미구현 항목 10개 표 (EmailBatch/digest, RecipientPreference, Bounce webhook, Real SMTP/SES, Template engine 등) + production 전환 sketch. design §8 "Path C sketch" 기반. -->

---

## 9. AI 활용 문서

<!-- TBD: Task 34 — Claude Code + gstack /superpowers 흐름 (brainstorming → writing-plans → executing-plans), design v3.3→v3.4 Path B 전환 의사결정 이력, 총 iteration 수 + 주요 correction 목록. -->
