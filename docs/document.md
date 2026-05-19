# BE-C Notification System — 도메인 사전 + 아키텍처 reference (Path B)

> AI 코딩 시 lookup 용 압축 reference. grill 이력 + Path B 재설계 풀 정당화는 design v3.4 (gstack) 참조.

---

## § 1. Domain Glossary

### 10 Value Object

| # | VO | 표현 | invariant | 모듈 |
|---|---|---|---|---|
| 1 | `NotificationId` | record(UUID) | UUID swap 방지 | notification |
| 2 | `DeliveryId` | record(UUID) | UUID swap 방지 | delivery |
| 3 | `DeliveryAttemptId` | record(UUID) | UUID swap 방지 | delivery |
| 4 | `RecipientId` | record(String) | ≤64자, blank 거부 | notification |
| 5 | `EventId` | record(String) | ≤64자, blank 거부, **notification-level dedup 중심축** | notification |
| 6 | `IdempotencyKey` | record(String) | ≤128자, blank 거부 | idempotency |
| 7 | `RequestHash` | record(String) | SHA-256 hex 64자 | idempotency |
| 8 | `DeliveryAttemptCount` | record(int) | 단조 증가 only (절대 리셋 X) — channel 별 누적 | delivery |
| 9 | `DeliveryAttemptSessionCount` | record(int) | 0 ≤ x ≤ max_attempts, 새 attempt session 마다 0 부터 | delivery |
| 10 | `NotificationPayload` | JsonNode wrapper | JSONB 직렬화 + immutable | notification |

> `ChannelType` 은 enum 으로만 유지 (State Enum 절 참조). enum 이 이미 swap 방지 + validation 강하므로 record wrapping 은 over-engineering.

JPA 매핑: 각 VO 마다 `@AttributeConverter` 1개 (또는 `@Embeddable` for record). Hibernate 6.2+ record 지원.

### State Enum

- `DeliveryState`: PENDING / SENT / DEAD
- `DeliveryAttemptState`: READY / IN_PROGRESS / DONE / FAILED
- `NotificationType`: ENROLLMENT_COMPLETED / PAYMENT_CONFIRMED / COURSE_STARTING_TOMORROW / ENROLLMENT_CANCELLED
- `ChannelType` (enum): EMAIL / IN_APP

> `NotificationState` 컬럼 없음 — notification 의 *overall state* 는 delivery aggregate 로 derive (모든 delivery DEAD → notification 도 dead-ish, 1 개라도 SENT → delivered-ish). DB 컬럼 미도입.

### DispatchResult (sealed interface)

```java
sealed interface DispatchResult {
    record Success() implements DispatchResult {}
    record TransientFailure(String reason, Throwable cause) implements DispatchResult {}
    record PermanentFailure(String reason, Throwable cause) implements DispatchResult {}
}
```

### 도메인 메서드 시그니처

**Notification (AR):**
- `markRead(Instant now)` — 단순 state mutator. *invariant #2 enforce 는 application service 책임* (NotificationService.markRead 가 deliveryRepository.existsByNotificationIdAndChannelAndState(id, IN_APP, SENT) 호출 → false 면 422 → true 면 invoke).

**NotificationLookup (cross-module read port, notification.application 소유):**
- `findById(NotificationId) → Optional<NotificationView>` — delivery 모듈이 ChannelAdapter 에 전달할 payload 등 read-only 정보 요청. `NotificationView` = notification.application 의 record DTO (R4 cross-module domain 직접 참조 금지 준수).

**DeliveryRegistrar (out-port, notification.application 소유, delivery 가 구현):**
- `scheduleFor(NotificationId, List<ChannelType> channels)` — 등록 흐름에서 notification.NotificationService 가 호출, delivery + delivery_attempt INSERT. 구현체는 `@Transactional(propagation = MANDATORY)` (호출자 트랜잭션 강제).

**DeliveryRetryRegistrar (in-module port, delivery.application 소유, admin 이 호출):**
- `issueNewAttempt(DeliveryId)` — admin retry 시 새 delivery_attempt READY row INSERT. delivery.attempt_count 는 유지 (별 메서드 markPending 이 책임).

**Delivery (AR):**
- `markSent(Instant now)`
- `markDead(String reason, Instant now)`
- `markPending(Instant now)` — admin retry 복귀 (attempt_count 유지)
- `incrementAttempt(Instant now) → DeliveryAttemptCount`
- `recordTransientFailure(String reason, Instant now)` — *Delivery.attemptCount (누적, channel 별, 절대 리셋 X)* 만 증가. **DeliveryAttempt.attemptCount (session 별, 새 row 마다 0) 와 의미 완전 분리** — 같은 시점에 application service 가 둘 다 갱신하지만 책임 메서드 다름.

**DeliveryAttempt (AR):**
- `claim(String workerId, Instant now, Instant claimedUntil)`
- `scheduleNextRetry(Instant now, Instant nextAttemptAt, DeliveryAttemptSessionCount nextCount, String reason)` — *now* 파라미터 명시 (updatedAt 갱신용)
- `markDone(Instant now)`
- `markFailed(DeliveryAttemptSessionCount finalCount, String reason, Instant now)`
- `incrementAttempt() → DeliveryAttemptSessionCount`

**RetryPolicy** (pure POJO, delivery.domain):
- `nextBackoff(DeliveryAttemptSessionCount n, Clock clock) → Duration`
- `shouldDead(DeliveryAttemptSessionCount n, DispatchResult result) → boolean`

---

## § 2. Aggregate Map

### 분류

| 후보 | 분류 | 근거 |
|---|---|---|
| Notification | **AR** | 비즈니스 lifecycle (read_at) + 사용자 노출 + 6개월 retention. payload + event-level dedup 소유. |
| Delivery | **AR** | 채널별 전달 lifecycle (state, attempt_count 누적) + 사용자 노출 (deliveries[]) + 6개월 retention. notification 1 → delivery N (channel 별). |
| DeliveryAttempt | **AR** | 별도 수정 액터(worker) + 별도 lifecycle (30일 vs 6개월) + N:1 (admin retry 마다 새 row) + broker 전환 시 토픽 경계 |
| IdempotencyRecord | **service-managed entity** | consistency boundary 약함 (key/hash/TTL 만) + 횡단 관심사. AR 으로 부풀리지 않음 = 정직한 절제 |

### 관계 다이어그램

```
            Notification (AR)
            ─────────────────
            │ 1
            │
            │ N (channel 별 — 본 과제 최대 2: EMAIL + IN_APP)
            ▼
              Delivery (AR)
              ─────────────
              │ 1
              │
              │ N (admin retry 마다 새 session)
              ▼
       DeliveryAttempt (AR)
       (FK: delivery_id)

   IdempotencyRecord (entity, target_id : UUID — FK 없음, 모듈 독립)
```

- Notification ↔ Delivery: FK reference (`delivery.notification_id`)
- Delivery ↔ DeliveryAttempt: FK reference (`delivery_attempt.delivery_id`)
- IdempotencyRecord ↔ Notification: 코드 레벨 의존 없음 (`target_id` 는 단순 UUID)

### "1 tx N aggregate" 위반 — 3 곳

1. **등록**: notification 1 + delivery N + delivery_attempt N + idempotency_record 0~1 = **최대 6 INSERT** (channels=[EMAIL,IN_APP] + 헤더 있을 때)
2. **디스패치 결과 반영 (`relay()`)**: delivery + delivery_attempt **UPDATE**
3. **Admin retry**: delivery UPDATE (state=PENDING, attempt_count 유지) + delivery_attempt 새 row **INSERT**

### 정당화 (signature)

> **Outbox 패턴의 본질 = cross-aggregate atomic consistency.** Notification (논리적 사건) ↔ Delivery (전달 의도) ↔ DeliveryAttempt (transport 큐) 의 atomic commit 이 "send 의도 손실 없음" 보장의 본질. 한 쪽만 commit + 다른 별 트랜잭션 시 → crash 윈도우에 row 미생성 → 영원히 PENDING. DDD "1 tx 1 aggregate" 의 *의도된 예외*.

---

## § 3. Single Model + Hexagonal + Belonging

### 단일 모델 (Toby Clean Spring frame)

- JPA Entity = Domain Model (같은 클래스)
- `@Entity`, `@Id`, `@Column`, `@Convert` 는 `domain/` 안에 직접 위치
- Spring stereotype (`@Component`, `@Service`, `@Configuration`, `@RestController`) 만 `domain/` 거부
- `support/Mapper` 폴더 + `*JpaEntity` 클래스 없음 (분리 안 함)
- DTO ↔ domain 변환은 `NotificationResponse.from(Notification, List<Delivery>)` static factory

### Hexagonal

```
adapter.in  →  application  ←  adapter.out
                   ↓
                domain (Spring stereotype 거부 / JPA persistence annotation 허용)
```

- `domain` → 어디에도 의존 X (Spring stereotype 만 거부, JPA annotation 허용)
- `application` → `domain` + 자기 port 인터페이스
- `adapter.out` → port 구현체
- `adapter.in` → `application` 호출만 (domain 직접 노출 X, DTO 통해 변환)
- Repository port 는 `application/` 안 (DDD 정통 위반 정직 명시 — Hexagonal 정합 우선)

### Belonging 원칙 (구현 중 경계 판단)

| 코드 종류 | 위치 | 예시 |
|---|---|---|
| state 전이 + invariant + 도메인 메서드 | `domain/` (entity 의 메서드로) | `delivery.markSent()`, `deliveryAttempt.scheduleNextRetry()`, `notification.markRead()` |
| JPA persistence annotation | `domain/` (entity 와 VO 에 직접) | Notification/Delivery/DeliveryAttempt 의 `@Entity` |
| Spring stereotype | `application/` 또는 `adapter/` (절대 `domain/` 안에 X) | NotificationService 의 `@Service` |
| 비즈니스 규칙 pure POJO (정책/계산기) | `domain/` (Spring/JPA 의존 0) | `RetryPolicy.nextBackoff()`, `RequestHash.of()` |
| Repository port (collection 추상) | `application/` (정직한 절제) | `NotificationRepository`, `DeliveryRepository`, `DeliveryAttemptRepository` |
| Use case 조정 (트랜잭션, repo 호출, port 호출) | `application/` | `NotificationService.register()`, `DeliveryRelayService.relay()` |
| 외부 설정 (`@ConfigurationProperties`) | `global/config/` | `RetryProperties`, `WorkerProperties` |
| 외부 시스템 ACL | `adapter/out/channel/` | `EmailAdapter`, `InAppAdapter` |
| Cross-module read API | `application/` (공급자 모듈 소유 — Published Language) | `NotificationLookup` (notification 소유, delivery 호출) |
| Cross-module read DTO | `application/` (공급자 모듈 소유, record 형식) | `NotificationView` (notification.application, NotificationLookup 반환 타입). delivery 가 받아 ChannelAdapter 에 전달. R4 우회 X — domain entity 절대 누출 X. |
| Cross-module out-port (consumer 소유) | `application/port/` (호출자 모듈 소유 — interface 만, delivery 가 adapter 구현) | `DeliveryRegistrar` (notification.application.port 소유, delivery.adapter.in.registrar 구현). 의존 방향: delivery → notification.application. design §7 단방향 유지. |
| Cross-module in-port (provider 소유) | `application/` (제공 모듈 소유 — interface + 구현 둘 다) | `DeliveryRetryRegistrar` (delivery.application 소유 + 구현, admin 이 호출). 의존 방향: admin → delivery. |

### DDD-Lite 적용 원칙 (정직한 절제)

> *signature: "DDD 정통을 그대로 따르지 않고 정직하게 절제 적용."*

6 원칙:
1. 단일 모델 (JPA Entity = Domain Model)
2. **3 AR + 1 service-managed entity** (Notification + Delivery + DeliveryAttempt + IdempotencyRecord)
3. "1 tx N aggregate" 위반은 정당화된 예외 (Outbox 패턴 본질)
4. Domain Event 는 문서 레벨만 (delivery_attempt row = persistence)
5. Value Object 강타입화 10개 (primitive obsession 거부, ChannelType 만 enum 으로 절제)
6. Repository port = `application/` (DDD 정통 위반 정직 명시)

---

## § 4. 3-Layer Dedup

### Signature

> **"동일 이벤트 중복 발송 방지는 notification-level UNIQUE 와 delivery-level UNIQUE 가 함께 보장하고, API 재전송 안전성은 별도의 idempotency key 로 보완한다."**

### 3 계층

| 계층 | 메커니즘 | 작동 조건 | 응답 헤더 |
|---|---|---|---|
| 1차 | `UNIQUE(event_id, recipient_id, type)` (notification-level) | 헤더 없이도 작동. 같은 event 는 사용자에게 1번만 발생. | `X-Event-Duplicate: true` (200) |
| 1.5차 | `UNIQUE(notification_id, channel)` (delivery-level) | fan-out 시 같은 채널 두 번 거부. 트랜잭션 안에서 강제. | (헤더 없음, 내부 처리) |
| 2차 | optional `Idempotency-Key` 헤더 → `idempotency_record` (key, hash, target_id, 24h TTL) | 헤더 있을 때만. API replay safety. | `X-Idempotent-Replay: true` (200) |

### 등록 흐름 의사코드

```
1. parse: command (eventId, recipientId, type, channels[], payload)
   ⓘ channels[] 는 API level 에서 distinct() 로 정규화 — 같은 channel 두 번 명시되어도 1개로 처리.
     본 결정은 1.5차 dedup (uq_delivery_per_channel) 의 DB level 위반 응답 대신
     API level normalize 로 자연스럽게 1건 처리.
2. headerKey ← X-Idempotency-Key (optional)

3. if headerKey present:
     result ← IdempotencyService.lookupCurrent(headerKey, requestHash)
     case HIT same hash (TTL 유효) → return existing + X-Idempotent-Replay: true (200)
     case HIT diff hash → 409 Problem Details (IdempotencyConflictException)
     case MISS          → return Miss (계속, persist 는 트랜잭션 4 안에서)

4. TRANSACTION:
   try:
     INSERT notification (eventId, recipientId, type, payload, ...)   -- uq_notification_event 적용
     for each channel in channels[]:
       if channel == IN_APP:
         INSERT delivery (notification_id, channel=IN_APP, state=SENT, attempt_count=1, sent_at=now)
         INSERT delivery_attempt (delivery_id, state=DONE, attempt_count=1, ...)
       else:  -- EMAIL
         INSERT delivery (notification_id, channel=EMAIL, state=PENDING, attempt_count=0)
         INSERT delivery_attempt (delivery_id, state=READY, attempt_count=0, next_attempt_at=now)
     if headerKey present:
       INSERT idempotency_record (key, request_hash, target_id=notification.id, expires_at=now+24h)
     COMMIT
     → 202 + X-Event-Duplicate: false + X-Idempotent-Replay: false

   catch UniqueConstraintViolation (uq_notification_event):
     existing ← SELECT notification BY (event_id, recipient_id, type)
     ROLLBACK → return existing (with deliveries[])
     → 200 + X-Event-Duplicate: true
```

### Request Hash 계산

```
SHA-256(canonical-JSON({
  recipient_id,
  event_id,
  type,
  channels[],   -- 정렬된 배열
  payload
}))
```

canonical-JSON = key 정렬 + 공백 제거 + channels 배열 정렬. 같은 입력 → 항상 같은 hash.

### 응답 헤더 조합

| 시나리오 | X-Idempotent-Replay | X-Event-Duplicate | HTTP |
|---|---|---|---|
| 신규 등록 | false | false | 202 |
| 헤더 키 replay (같은 body) | true | (4 단계 미진입) | 200 |
| 헤더 키 + 다른 body | (409 응답에서 헤더 의미 없음) | — | 409 |
| 헤더 없이 event 중복 | false | true | 200 |
| 헤더 있고 event 매칭도 됨 | true | (4 단계 미진입) | 200 |

### 동시성 충돌 처리

- 같은 event 100 요청 동시 (헤더 없음) → 1건만 `uq_notification_event` 통과. 나머지 99건 catch UniqueConstraintViolation → SELECT existing → 200 + X-Event-Duplicate: true
- 같은 헤더 100 요청 동시 → 1건만 `idempotency_record` PK 통과. 나머지 99건 catch → IdempotencyService.lookup → HIT 경로 → 200 + X-Idempotent-Replay: true
- 같은 notification 에 같은 channel 추가 요청 → `uq_delivery_per_channel` 위반 → 트랜잭션 rollback, 안전.

---

## § 5. State Machine + Invariant

### Notification state (derive only)

> **state 컬럼 없음.** notification 의 *overall state* 는 delivery aggregate 로 derive:
- 모든 delivery 가 DEAD → notification 도 dead-ish
- 1 개라도 SENT → delivered-ish
- else → pending-ish

응답 body 에서 사용자 노출 시 application 레벨에서 계산. DB 컬럼 미도입 → invariant 줄어듦.

### Delivery 상태 전이 (channel 별)

```
EMAIL:
   PENDING ──현재 attempt 가 성공──→ SENT (terminal)
      │
      ├──현재 attempt 가 max 초과 or Permanent──→ DEAD
      │
      ├──Admin retry──────────────────────────→ PENDING (delivery.attempt_count 유지!)
      │
      └──Transient 실패 (현재 attempt 가 backoff 후 재진입) — PENDING 유지

IN_APP:
   생성 시점 즉시 SENT (terminal) — PENDING 거치지 않음
   admin retry 무의미 (이미 SENT terminal)
```

### DeliveryAttempt 상태 전이

```
READY ──claim──→ IN_PROGRESS ──Success──→ DONE (terminal)
                      │      ──Transient (n<max)──→ READY (next_attempt_at 갱신, attempt_count++)
                      │      ──Permanent or n>=max──→ FAILED (terminal)
                      └──claimed_until 만료──→ READY (reaper)
```

> IN_APP delivery 의 delivery_attempt 는 생성 시점에 즉시 state=DONE, attempt_count=1. worker 가 안 잡음 (audit/일관성 위해 row 는 INSERT).

### 두 state 비대응 (의도된 설계)

`delivery.state` 와 `delivery_attempt.state` 는 1:1 대응 X. delivery = 사용자 관점 전달 상태, delivery_attempt = 특정 attempt session lifecycle.

| delivery.state (EMAIL) | delivery_attempt row 들 (시간 순) | 시나리오 |
|---|---|---|
| PENDING | 1 READY | 등록 직후 |
| PENDING | 1 IN_PROGRESS | 워커가 잡고 send 중 |
| SENT | 1 DONE | 정상 완료 |
| DEAD | 1 FAILED | retry 소진 또는 permanent |
| **PENDING** | **1 FAILED + 1 READY** | DEAD → admin retry, 아직 미실행 |
| **PENDING** | **1 FAILED + 1 IN_PROGRESS** | admin retry 후 워커가 새 attempt 잡고 send 중 |
| **SENT** | **1 FAILED + 1 DONE** | admin retry 성공 (audit 상 첫 시도 실패 + 둘째 성공) |
| **DEAD** | **1 FAILED + 1 FAILED** | admin retry 도 실패 |

IN_APP 의 경우 항상 `delivery.state=SENT` + `1 DONE` (단조 케이스).

### attempt_count 의미 분리

| 컬럼 | 의미 | 변화 패턴 |
|---|---|---|
| `delivery.attempt_count` | **누적** 시도 횟수 (channel 별, 사용자/운영자 노출, 절대 리셋 X) | 단조 증가 only |
| `delivery_attempt.attempt_count` | 이 attempt session 의 retry 진행 상태 | 0 → max_attempts (새 row 마다 0 부터) |

**예시 시나리오** (EMAIL): 첫 등록 → 5회 transient 실패 → DEAD → admin retry → 다음 시도 성공

| 시점 | delivery.attempt_count | attempt-1.attempt_count | attempt-2.attempt_count | delivery.state |
|---|---|---|---|---|
| 등록 직후 | 0 | 0 (READY) | — | PENDING |
| 첫 시도 실패 | 1 | 1 (READY, backoff) | — | PENDING |
| 5번째 실패 | 5 | 5 (FAILED) | — | DEAD |
| Admin retry | **5 (유지)** | 5 (FAILED) | 0 (READY) | PENDING |
| 다음 시도 성공 | **6** | 5 (FAILED) | 1 (DONE) | SENT |

### Notification AR Invariant (3)

1. payload INSERT 후 immutable (JPA `updatable=false` + 통합 테스트)
2. read_at not null 가능은 *최소 1개의 IN_APP delivery 가 SENT* 일 때만. **enforce: application service** (NotificationService.markRead 가 cross-AR query 후 invoke. entity 안에서 검증 X — Notification 과 Delivery 가 별 AR 이므로 deliveries collection 보유 X.)
3. dedup key (`uq_notification_event` on event_id+recipient_id+type) — DB unique constraint 강제

### Delivery AR Invariant (5)

1. state 전이만 허용: PENDING → SENT (terminal), PENDING ↔ DEAD (admin retry 복귀), IN_APP 은 생성 시 즉시 SENT 만 허용 (PENDING 거치지 않음)
2. attempt_count 단조 증가 only — 절대 리셋 X (admin retry 시에도 유지)
3. sent_at not null ↔ state=SENT
4. channel = IN_APP 이면 state=SENT, attempt_count ≥ 1 (생성 시점에 강제)
5. dedup key (`uq_delivery_per_channel` on notification_id+channel) — DB unique constraint 강제

### DeliveryAttempt AR Invariant (4)

1. state 전이: READY → IN_PROGRESS → {DONE | READY(transient) | FAILED}
2. attempt_count 단조 증가, 0 ≤ x ≤ max_attempts
3. claimed_by / claimed_until 동기화 (둘 다 null 이거나 둘 다 not null)
4. next_attempt_at 은 현 시각 이상

### IdempotencyRecord Invariant (2)

1. expires_at = created_at + 24h
2. request_hash 결정성 — SHA-256(canonical-JSON(...))

각 invariant = 도메인 단위 테스트 1개 (총 **14**개, Phase 1).

### Retry Policy

```
delay(n) = base * 2^(n-1) + jitter(0, base)
  where base = 30s, n = 1..5

n=1: 30s + j(0, 30s)   → ~30–60s
n=2: 60s + j(0, 30s)   → ~60–90s
n=3: 120s + j(0, 30s)  → ~120–150s
n=4: 240s + j(0, 30s)  → ~240–270s
n=5: 480s + j(0, 30s)  → ~480–510s

총 누적 deadline: ~15분
```

적용 단위 = `delivery_attempt.attempt_count` (session). EMAIL delivery 만 적용 (IN_APP 은 즉시 SENT).

### Transient vs Permanent 분류

| 분류 | 사례 | 처리 |
|---|---|---|
| **Transient** | SocketTimeout, ConnectException, IOException, HTTP 5xx/429, DB transient lock | retry. `attempt_count >= max_attempts` 면 DEAD |
| **Permanent** | HTTP 400/403/404, invalid recipient, payload > 1MB, `IllegalArgumentException`, mock `x_test_failure: permanent` | 즉시 DEAD |
| 분류 불가 | 기타 `RuntimeException` | **Transient 로 간주** (보수적) |

ChannelAdapter 구현체가 catch 한 예외를 위 규칙대로 분류해 `DispatchResult` 반환. DeliveryRelayService 가 결과 보고 retry/DEAD 결정.

---

## § 6. Domain Event Map

> 도메인 이벤트 ≡ delivery_attempt row. 별도 `ApplicationEvent` 클래스 미도입.

### 6 이벤트 ↔ row 매핑 + 책임 위치

| 도메인 이벤트 (개념) | row 표현 | 책임 위치 |
|---|---|---|
| `DeliveryCreated` | delivery_attempt INSERT, state=READY (EMAIL) 또는 state=DONE (IN_APP), attempt_count=0/1 | `DeliveryRegistrarAdapter.scheduleFor` (delivery.adapter.in.registrar) — notification.NotificationService 가 호출 |
| `DeliveryAttempted` | delivery_attempt UPDATE state=IN_PROGRESS, claimed_by/claimed_until 세팅 | `DeliveryRelayService.claimBatch` (delivery.application) — DispatchWorker tick 시 호출 |
| `DeliverySent` (Success) | delivery_attempt UPDATE state=DONE + delivery UPDATE state=SENT, attempt_count++ | `DeliveryRelayService.relay` (Success 분기) |
| `DeliveryTransientFailed` | delivery_attempt UPDATE attempt_count++, state=READY, next_attempt_at=future + delivery.attemptCount++ (Delivery.recordTransientFailure) | `DeliveryRelayService.relay` (TransientFailure 분기, n<max) |
| `DeliveryPermanentFailed` | delivery_attempt UPDATE state=FAILED + delivery UPDATE state=DEAD | `DeliveryRelayService.relay` (PermanentFailure 또는 TransientFailure n>=max) |
| `DeliveryAdminRetried` | delivery_attempt INSERT (새 row, attempt_count=0) + delivery UPDATE state=PENDING (attempt_count 유지) | `AdminRetryService.retry` + `DeliveryRetryRegistrar.issueNewAttempt` |

### Broker 전환 매핑

> **변경 포인트는 transport layer 뿐.**

- 현재: `DispatchWorker` 가 delivery_attempt row 를 poll (`@Scheduled(1s)` + SKIP LOCKED + Virtual Thread + Semaphore)
- 이후: `KafkaConsumerDispatcher` 가 `delivery-events` topic 구독
- 이벤트 schema = 위 6 이벤트 그대로
- delivery_attempt 가 곧 Kafka topic 의 이벤트 메시지

### 기각 옵션

Spring `ApplicationEventPublisher` 미도입. 사유:
- delivery_attempt 와 역할 겹침 (이벤트 두 곳에 존재)
- 이벤트 손실 약점
- broker 전환 스토리에서 무관 (변경 포인트는 delivery_attempt → Kafka topic)

---

## § 7. Architecture Boundaries

### 의존 그래프

```
                    ┌──────────┐
                    │  admin   │
                    └────┬─────┘
                         │
            ┌────────────┼─────────────┐
            ▼            ▼             ▼
      ┌─────────┐  ┌──────────────┐  ┌─────────────┐
      │ delivery│─▶│ notification │  │ idempotency │
      └─────────┘  └──────────────┘  └─────────────┘
            │            │                  │
            └────────────┼──────────────────┘
                         ▼
                     ┌────────┐
                     │ global │
                     └────────┘
```

- delivery → notification (`NotificationLookup` read port + `DeliveryRegistrar` out-port 구현, 둘 다 notification.application 소유)
- notification → idempotency (등록 흐름 2차 dedup)
- admin → notification (조회) + delivery (`DeliveryRetryRegistrar` 호출, delivery.application 소유)
- notification 은 delivery/admin 에 의존 X (단방향) — DeliveryRegistrar 는 notification 이 소유한 *interface* 라 notification → delivery 의존 아님 (interface 만 정의, 구현체는 delivery 모듈)
- idempotency 는 다른 도메인 모듈에 의존 X (`target_id` 는 단순 UUID)
- 모든 모듈 → global

### 모듈 트리 (간략)

```
com.livenotification/
├── notification/{domain, application, adapter.in.web, adapter.out.persistence}
├── delivery/{domain, application, adapter.in.scheduler, adapter.out.persistence, adapter.out.channel}
├── idempotency/{domain, application, adapter.in.scheduler, adapter.out.persistence}
├── admin/{application, adapter.in.web}
└── global/{config, error}
```

- `support/` 폴더 없음 (단일 모델 — Mapper 클래스 없음)
- `*JpaEntity` 클래스 없음 (`@Entity` 는 `domain/` 안)
- delivery/ 모듈 안에 Delivery + DeliveryAttempt entity, EmailAdapter + InAppAdapter + ChannelRouter, DispatchWorker + ReaperWorker

### ArchUnit 6 룰 (Phase 6 작성)

위치: `src/test/java/com/livenotification/architecture/ArchitectureTest.java`

| # | 룰 | 검증 |
|---|---|---|
| R1 | `domain/` 은 Spring stereotype 의존 0 (JPA annotation 은 허용) | `noClasses().resideIn("..domain..").dependOnClassesThat().resideInAnyPackage("org.springframework.stereotype..", "org.springframework.context..", "org.springframework.web..")` |
| R2 | `application/` 은 `adapter/` 의존 0 | `noClasses().resideIn("..application..").dependOnClassesThat().resideInAPackage("..adapter..")` |
| R3 | `adapter.in.*` 는 `adapter.out.*` 직접 import 금지 (port 통해) | `noClasses().resideIn("..adapter.in..").dependOnClassesThat().resideInAPackage("..adapter.out..")` |
| R4 | Cross-module `domain` 직접 참조 금지 (port 통해) | `delivery.domain` 이 `notification.domain.Notification` import 금지 |
| R5 | 순환 의존 0 (5 모듈 슬라이스) | `slices().matching("com.livenotification.(*)..").should().beFreeOfCycles()` |
| R6 | `notification` 모듈은 `delivery/admin` 에 의존 0 (단방향). `idempotency` 는 등록 흐름의 2차 dedup 사유로 의존 허용 — design §7 의존 그래프 정합. | `noClasses().resideIn("..notification..").dependOnClassesThat().resideInAnyPackage("..delivery..", "..admin..")` |

### 위반 메시지 예시

```
❌ R1: com.livenotification.notification.domain.Notification
   depends on org.springframework.stereotype.Component
   (domain 은 Spring stereotype 금지. JPA annotation 만 허용.)

❌ R4: com.livenotification.delivery.domain.Delivery
   depends on com.livenotification.notification.domain.Notification
   (cross-module domain 직접 참조 금지. port 경유 필수.)

❌ R6: com.livenotification.notification.application.NotificationService
   depends on com.livenotification.delivery.application.DeliveryRelayService
   (notification 은 delivery 에 의존 X — 단방향 강제. notification → idempotency 는 허용.)
```

### 기각된 룰 (over-engineering 회피)

- R7 (`@Entity` 위치 강제) — *단일 모델 frame 에서 무의미*. `@Entity` 는 `domain/` 안이 옳음.
- R8~R11 (Controller/Scheduler/Repository port 위치) — R2~R6 으로 간접 강제.

### 동시성 3중 방어

| 레이어 | 메커니즘 | 목적 |
|---|---|---|
| 다중 인스턴스 | `SELECT delivery_attempt ... FOR UPDATE SKIP LOCKED` | 같은 attempt row 두 인스턴스가 못 잡음 |
| 인스턴스 내부 | `Semaphore(permits=N)` (default N=16) | Virtual Thread 폭주 방지 (외부 SMTP 보호) |
| Stuck 행 | `claimed_until` lease (30s) + reaper 30s 주기 | 워커 중도 사망/멈춤 시 복구 |

> **외부 I/O 중심 작업(EMAIL dispatch)이므로 Java 21 Virtual Thread를 적용했습니다. 다만 외부 채널 보호를 위해 Semaphore 기반 concurrency limit을 추가했습니다.**

---

## § 8. Phase 1~6 Guide

> Date 무관, 완성 우선. Phase 단위로 끝까지 완성. 시간 부족 시 Phase 5/6 일부 deferred 가능.

### Phase 1 — 도메인 + 스키마 + invariant

**작업:**
- Gradle (Kotlin DSL) + Spring Boot 3.x + Java 21
- `docker-compose.yml` (PostgreSQL 16)
- Flyway V1 마이그레이션 — **4 테이블**:
  - `notification` + `event_id` + `uq_notification_event` UNIQUE INDEX (event_id+recipient_id+type, channel 없음)
  - `delivery` + `uq_delivery_per_channel` UNIQUE INDEX (notification_id+channel)
  - `delivery_attempt`
  - `idempotency_record` + `target_id`
- 모듈 패키지 골격 (`support/` 없음)
- 도메인 entity = JPA Entity (Notification, Delivery, DeliveryAttempt, IdempotencyRecord)
- 10 VO record + AttributeConverter (ChannelType 은 enum + EnumType.STRING)
- `RetryPolicy` pure POJO + `RetryProperties` (yaml) + `RetryPolicyConfig` Bean factory
- `ClockConfig` Bean
- HikariCP + PG `statement_timeout` / `lock_timeout`
- 도메인 invariant 단위 테스트 **14개** (Spring/JPA 의존 없이)

**Commit tag:** `phase-1-domain`

### Phase 2 — 등록 API + 3 계층 dedup + 오류 응답

**작업:**
- `notification.adapter.in.web` REST API + DTO
- request body 에 `channels: ["EMAIL", "IN_APP"]` 배열만 받음 (*단일 `channel` 호환 미지원* — 단일은 1-element array `channels: ["EMAIL"]` 로). API 진입 시 `distinct()` 로 정규화 — 같은 channel 두 번 → 1개.
- 응답 body 에 `deliveries[]` 포함 (각 channel, state, sent_at)
- `ControllerAdvice` (RFC 7807 Problem Details)
- `NotificationService` 등록 흐름 (notification 1 + delivery N + delivery_attempt N + idempotency 0~1, 한 트랜잭션)
- `X-Event-Duplicate` / `X-Idempotent-Replay` 헤더 (독립)
- Springdoc OpenAPI 자동 노출
- **N+1 회피**: GET /v1/notifications/{id} repository 메서드 = `@Query("SELECT n FROM Notification n LEFT JOIN FETCH n.deliveries WHERE n.id = :id")`. GET /v1/notifications?recipient_id= list 메서드 = `@EntityGraph(attributePaths = "deliveries")`.
- PATCH /v1/notifications/{id}/read = NotificationService.markRead 가 deliveryRepository.existsByNotificationIdAndChannelAndState(id, IN_APP, SENT) cross-AR query 후 notification.markRead invoke (Notification invariant #2 application-level enforce).
- slice tests (`@WebMvcTest`, `@DataJpaTest + Testcontainers`)

**테스트:**
- T1: `ConcurrentDedupIT` (notification-level), `EventDedupNoHeaderIT`
- T2: `IdempotencyReplayIT`, `IdempotencyConflictIT`, `DeliveryPerChannelDedupIT` (delivery-level), `MarkReadEmailOnlyRejectIT` (Notification invariant #2 통합 검증 — EMAIL-only notification 에 PATCH /read → 422)

**Commit tag:** `phase-2-api`

### Phase 3 — Worker + retry + transient/permanent

**작업:**
- `delivery` 모듈 worker (`DispatchWorker` — SKIP LOCKED + Virtual Thread + Semaphore) — delivery_attempt 폴링
- `DeliveryRelayService`
- `ChannelAdapter` (EmailAdapter mock + InAppAdapter + ChannelRouter)
- IN_APP 즉시 SENT optimization (등록 시 처리, worker 진입 안 함)
- `RetryPolicy` pure POJO domain + Transient/Permanent 분류

**테스트:**
- T1: `RetryMaxAttemptsIT`
- T2: `PermanentFailureIT`, `RetrySuccessIT`, `DeliveryAttemptCountCumulativeIT`, `StateNonCorrespondenceIT`, `InAppImmediateSentIT`

**Commit tag:** `phase-3-dispatch`

### Phase 4 — Stuck recovery + multi-worker + admin retry

**작업:**
- `ReaperWorker` (`@Scheduled(30s)`)
- `admin` 모듈 (`AdminRetryService` 새 delivery_attempt 발급 + `AdminNotificationController`)
- `SecurityConfig` (X-Admin-Token)
- Micrometer 메트릭 11개:
  - 핵심 6: `notification.registered`, `delivery.dispatched`, `delivery.dispatch.duration`, `delivery_attempt.queue.size`, `delivery_attempt.lag`, `notification.idempotency.replay`
  - 추가 4: `delivery_attempt.claimed.stuck`, `delivery.dead`, `delivery.admin.retried`, `delivery_attempt.cleanup.deleted`
  - 운영 alert 1: `notification.design.violation` (tag `kind`) — *0 이 정상, 증가 시 즉시 alert*. 현재 InAppAdapter.send 호출 시 +1 (kind=`inapp_dispatch_attempted`). README §"Micrometer 메트릭" 에 alert 권장 표시.

**테스트:**
- T1: `StuckRecoveryIT`, `RestartDurabilityIT`, `MultiWorkerIT`
- T2: `AdminRetryIT`

**Commit tag:** `phase-4-ops`

### Phase 5 — Cleanup + expiry + fan-out + 조합

**작업:**
- `DeliveryAttemptCleanupWorker` (DONE/FAILED 30일)
- `IdempotencyCleanupWorker` (24h TTL)

**테스트 (T3 채택 6):**
- `NotificationPayloadImmutabilityIT`
- `AdminRetryUsesCurrentPayloadIT`
- `HeaderAndEventCompositionIT`
- `MultiChannelFanOutIT` (1 POST channels=[EMAIL,IN_APP] → notification 1 + delivery 2 + EMAIL 워커, IN_APP 즉시 SENT)
- `PartialChannelFailureIT` — fan-out 시 EMAIL DEAD + IN_APP SENT 의 응답 deliveries[] 두 state 다르게 노출. 명세 "한 채널 실패해도 다른 채널 도달" 컨트랙트 검증.
- `NotificationFlowIT` — 대표 사용자 시나리오 end-to-end: POST channels=[EMAIL,IN_APP] → 즉시 GET (IN_APP SENT + EMAIL PENDING) → 1~2s 후 GET (EMAIL SENT) → 동일 event 재POST (X-Event-Duplicate) → 새 event + Idempotency-Key 두 번 POST (X-Idempotent-Replay). *5분 데모 자동화가 아니라 대표 사용 시나리오의 일관성 검증*. README 챕터 6 Tier 분류 표에 그 의미 명시.

**시간 남으면 복귀 후보:**
- `DeliveryAttemptCleanupIT`, `IdempotencyExpiryIT` (운영 깊이 시그널)

**끝까지 후순위 (검증 중복으로 미적용 가능):**
- `MaxAttemptsPerAttemptIT` (DeliveryAttemptCountCumulativeIT 와 중복)
- `EventDedupConcurrentIT` (ConcurrentDedupIT + EventDedupNoHeaderIT 합성)
- `IdempotencyServiceUnitTest` (도메인 invariant 단위 14개와 부분 중복)

**Commit tag:** `phase-5-resilience`

### README 작성 일정 — Phase-parallel (CEO review D2 결정)

> README 를 Phase 6 단일 cliff 에 몰아두면 Phase 5 미끄러질 때 정직 미구현 시그널 직격. 각 Phase 끝낼 때 해당 챕터 즉시 작성. 총량 ~8.5h, cliff 0.

| 시점 | 작성 챕터 | 분량 |
|---|---|---|
| Phase 1 끝 | 챕터 5 schema/DDD-Lite/3 AR 절 | 1h |
| Phase 2 끝 | 챕터 4 (명세 매핑 표) + 챕터 5 의 3-Layer Dedup 절 | 1h |
| Phase 3 끝 | 챕터 5 의 동시성/Virtual Thread+Semaphore 절 + 챕터 6 Tier 분류 표 | 1h |
| Phase 4 끝 | 챕터 5 의 channel-as-transport tradeoff 절 + 챕터 8 (Path C sketch) 골격 | 1.5h |
| Phase 6 | 챕터 1/2/3/7/9 + Grafana 대시보드 + k6 결과 + ArchUnit + ERD + 마감 정리 | 4h |

### Phase 6 — Grafana + k6 + ArchUnit + README 마감

**작업:**
- k6 2 스크립트:
  - `dedup-race.js` — 동일 키 100 VU 동시 → 1건만 생성
  - `throughput.js` — 100 RPS 지속 60초 → P95 응답 + 폴링 지연
- Grafana 대시보드 JSON 6 패널 (`grafana/notification-dashboard.json`):
  - 행 1: Dispatch Throughput by Result / Dispatch Latency (P50/P95/P99) / DeliveryAttempt Queue Depth
  - 행 2: DeliveryAttempt Lag / Dead Deliveries by Type / Recovery & Admin Interventions
- README 9 챕터 (Phase 1~4 에 이미 부분 작성):
  1. 프로젝트 개요 + 5분 컷
  2. 기술 스택
  3. 실행 방법
  4. 요구사항 해석 (명세 매핑 표) — *Phase 2 끝*
  5. 설계 결정 (DDD-Lite, 3 계층 dedup, 단일 모델, Hexagonal, **channel-as-transport**) — *Phase 1~4 끝*
  6. 동시성·내구성 검증 (Tier 분류 + 미적용 표) — *Phase 3 끝*
  7. 부하 시험 결과 (k6)
  8. 미구현 + 개선 제안 (Path C: email_batch + recipient_preference + DigestWorker sketch) — *Phase 4 끝 골격, Phase 6 마감*
  9. AI 활용 문서
- 명세 매핑 표
- ERD 도식 (notification + delivery + delivery_attempt + idempotency_record)
- **ArchUnit 6 룰** 테스트 작성
- 최종 빌드·테스트 통과 확인

**Commit tag:** `phase-6-polish`

### 5분 데모 sequence (Path B 채널 fan-out)

| step | 시간 | 동작 | 평가자가 보는 것 |
|---|---|---|---|
| 0 | 15s | `docker compose up postgres` | PG 16 부팅 |
| 1 | 10s | `./gradlew bootRun` | Spring Boot 부팅 + 워커 시작 로그 |
| 2 | 5s | `curl -X POST /v1/notifications -d '{"event_id":"e1","recipient_id":"u1","type":"PAYMENT_CONFIRMED","channels":["EMAIL","IN_APP"],"payload":{...}}'` | 202 + `X-Event-Duplicate: false` + body 에 `deliveries[]` 2개 (EMAIL PENDING, IN_APP SENT) |
| 3 | 3s | `curl /v1/notifications/{id}` | IN_APP delivery state=SENT (즉시) + EMAIL delivery state=PENDING |
| 4 | 1~2s 대기 후 다시 GET | (worker tick) | EMAIL delivery state=SENT (워커 처리 완료) |
| 5 | 10s | 같은 event_id 로 다시 POST | 200 + `X-Event-Duplicate: true` (1차 dedup) |
| 6 | 20s | `Idempotency-Key` 헤더 붙여서 새 event POST → 같은 헤더로 다시 POST | 첫 202 + 두번째 200 + `X-Idempotent-Replay: true` (2차 dedup) |
| 7 | 30s | Swagger UI 열어서 OpenAPI spec | Springdoc 자동 노출 |
| 8 | 30s | Grafana 대시보드 열어서 메트릭 | dispatch 카운터·latency·queue depth 흐름 |
| | ~4분 40초 | | |

### 완성 전략 (시간 부족 시 어디부터 절제?)

1. Phase 6 의 k6 부하 시험 — 결과 표만 README (실행 환경 한정 명시). 1~2시간 절감.
2. Phase 5 의 후순위 복귀 후보 2개 (DeliveryAttemptCleanupIT, IdempotencyExpiryIT) — 미적용 가능. 1~2시간 절감.
3. Phase 5 의 Tier 3 4개 중 일부 — 미적용 가능 (미적용 표 README 명시).

Phase 1~4 는 *절제 불가* — 명세 5조건 + 시그널 핵심.

### Path C sketch — README 챕터 8 의 깊이 (CEO review D1 결정: 정직 미구현)

본 과제는 5일 take-home — *production 모델 까지 구현* 안 함. 다만 *어디까지 안 했는지* 와 *production 으로 가려면 어떻게* 가 README 챕터 8 에 1.5~2 페이지로 들어감. 골격:

```
## 8. 미구현 + 개선 제안

### 8.1 Path C 의 본질
   channel-as-transport 모델에서 Notification → Delivery → DeliveryAttempt 까지
   본 과제 구현. production 으로 가면 EmailBatch (digest) + RecipientPreference
   (사용자 선호) + Bounce webhook + Real SMTP + Template engine 등이 붙음.
   본 과제 scope 에 안 넣은 이유는 5일 안에 검증 깊이 못 채워서.

### 8.2 미구현 항목 표 (10개)
   | # | 항목 | 미구현 이유 | production sketch |
   | 1 | EmailBatch (digest) | digest schedule + type override 검증 시간 0 | delivery 에 batch_id UUID nullable 컬럼 추가 + email_batch 테이블 신설 + FK + DigestWorker (@Scheduled) |
   | 2 | RecipientPreference | 사용자 선호 모델 + admin UI scope 밖 | recipient_id PK + email_mode + type_overrides JSONB |
   | 3 | Bounce/Complaint webhook | 실 SMTP 없으므로 unmockable | /v1/internal/email-bounces + DEAD + 차단 list |
   | 4 | Real SMTP/SES | 명세가 mock 허용 + 인프라 의존성 | JavaMail/AWS SDK + RetryPolicy 그대로 |
   | 5 | Template engine | payload freeform JSONB 로 충분 | Mustache/Freemarker + locale 단계 hook |
   | 6 | OpenTelemetry | 단일 서비스 in-process trace 만 가능 | broker 전환 시 traceparent 컬럼 |
   | 7 | Real broker (Kafka/SQS) | scope = broker 전환 가능 구조만 | DispatchWorker → KafkaConsumerDispatcher 교체 |
   | 8 | Scheduled delivery | 명세에 없음 | notification.send_after 컬럼 + worker filter |
   | 9 | Multi-device read sync | 명세는 read_at 단일 컬럼 충분 | notification_read_per_device 테이블 |
   | 10 | OAuth2 + RBAC + mTLS | X-Admin-Token 으로 본 과제 충분 | Spring Security 6 + Resource Server + mesh mTLS |
   | 11 | NotificationCleanupWorker + DeliveryCleanupWorker (6개월 retention) | 본 과제는 delivery_attempt(30일) + idempotency(24h) cleanup 만 구현. 6개월 retention 은 Volume 가정 절에 언급되지만 worker 미구현. | @Scheduled 일 1회 SELECT WHERE created_at < now - 6 month + DELETE batch. FK ON DELETE CASCADE 가 이미 Phase 1 schema 에 박혀 있으므로 notification 삭제만으로 delivery + delivery_attempt 모두 cascade 삭제. |
   | 12 | UUIDv7 (time-ordered ID) | 본 과제 볼륨 (100K/일, 1.2 RPS 평균) 에서 B-tree page split 이득 거의 0. JDK 21 native impl 없음 — lib 추가 또는 manual impl 필요한데 의존성 정책 위반. UUIDv4 가 충분. | 대량 트래픽 production 환경 시 `com.github.f4b6a3:uuid-creator` lib 추가 또는 manual impl ~30줄 (`Uuid.v7().toJavaUuid()`). entity factory 의 `UUID.randomUUID()` 호출만 교체. |

### 8.3 production-readiness 우선순위
   ship 우선: 7 → 4 → 3 → 5 → 1 → 2 → 8 → 9 → 6 → 10 → 11 → 12. 각각 별 PR 분리 가능
   (본 과제의 schema/aggregate 경계가 그 분리를 안 막음).
```

---

## Cross Reference

원본 (grill 이력 + v3.x 변경 이력 + Path B 재설계 풀 정당화):
- v3.3 (channel-as-identity, superseded): `C:\Users\kim\.gstack\projects\assignment\kim-main-design-20260514-174736.md`
- v3.4 (Path B, channel-as-transport): `C:\Users\kim\.gstack\projects\assignment\kim-main-design-20260516-210124.md`

빠른 reminder (50줄 pointer):
`C:\assignment\CLAUDE.md`
