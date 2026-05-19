# BE-C Notification System

LiveClass 채용 과제. Spring Boot 3.x + Java 21 + PostgreSQL 16. 마감 2026-05-19.

## 핵심 frame (1줄씩)

- **단일 모델**: JPA Entity = Domain Model (Toby Clean Spring). `@Entity` 는 `domain/` 안에 직접.
- **channel = transport**: Notification = 논리적 사건, Delivery = 채널별 전달, DeliveryAttempt = retry/claim 큐. channel 은 notification identity 가 아닌 delivery 의 컬럼.
- **3 계층 dedup**: 1차 = `UNIQUE(event_id, recipient_id, type)` (notification-level, 헤더 없이 작동). 1.5차 = `UNIQUE(notification_id, channel)` (delivery-level). 2차 = optional `Idempotency-Key` 헤더.
- **DDD-Lite**: 3 AR (Notification, Delivery, DeliveryAttempt) + 1 service-managed entity (IdempotencyRecord). "1 tx N aggregate" 는 Outbox 패턴의 의도된 예외 (최대 6 INSERT).
- **Hexagonal**: port = `application/`. Spring stereotype 만 `domain/` 거부, JPA annotation 허용.
- **IN_APP optimization**: IN_APP delivery 는 생성 시 즉시 SENT (worker 안 거침, 같은 트랜잭션에 delivery_attempt 도 즉시 DONE). EMAIL 만 worker 가 폴링.
- **Phase 우선**: Phase 1~6 (date 무관, 완성 우선).

## 자주 깜박할 invariant

- `delivery.attempt_count` 단조 증가 only (절대 리셋 X) — channel 별 누적 의미.
- `delivery_attempt.attempt_count` 는 0 ≤ x ≤ max_attempts, 새 delivery_attempt row 마다 0 부터.
- `notification.payload` INSERT 후 immutable.
- `notification.read_at` 은 IN_APP delivery 가 1개라도 SENT 일 때만 NOT NULL 허용.
- `delivery.sent_at` NOT NULL ↔ `delivery.state` = SENT (channel=IN_APP 은 생성 시점에 강제).
- `NotificationState` 컬럼 없음 — overall state 는 delivery aggregate 로 derive.
- 응답 헤더 2개: `X-Event-Duplicate`, `X-Idempotent-Replay` (독립).

## 상세 reference

자세한 도메인 용어 / 10 VO / 6 도메인 이벤트 / state machine / ArchUnit 룰 / Phase 가이드:
→ **`docs/document.md`** 참조

각 절 lookup:
- Domain Glossary (10 VO + 용어) → § 1
- Aggregate Map (3 AR) → § 2
- Single Model + Hexagonal frame + Belonging 원칙 → § 3
- 3-Layer Dedup + 등록 흐름 → § 4
- State Machine + invariant (14개) → § 5
- Domain Event Map → § 6
- Architecture Boundaries + ArchUnit → § 7
- Phase N Guide → § 8

원본 (grill 이력 + 정당화 풀버전):
- v3.3 (channel-as-identity, superseded): gstack `assignment/kim-main-design-20260514-174736.md`
- v3.4 (Path B, channel-as-transport): gstack `assignment/kim-main-design-20260516-210124.md`

## 현재 위치

Phase 1 — 도메인 + 스키마 + invariant 단위 테스트. 코딩 시작 직전.
