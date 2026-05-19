# ADR-0002 — Idempotency race semantics + outcome typing

- Status: Accepted
- Date: 2026-05-19
- Deciders: BE-C maintainer

## Context

The registration flow in `NotificationService.register` interacts with
`IdempotencyService` across two phases of one transaction:

1. **Gate** (read-only) — given an `Idempotency-Key` and `RequestHash`, decide
   whether the request is a Miss (proceed), HitSameHash (replay), or
   HitDifferentHash (409).
2. **Bind** (atomic write) — after the notification has been inserted or loaded,
   atomically record the `K → notification.id` mapping for future replays.

Phase 2 of `docs/document.md` §4 (the notification `INSERT ... ON CONFLICT DO
NOTHING`) sits between the two idempotency phases. The gate cannot fold into the
bind because the `targetId` only exists after phase 2 completes.

Two related defects motivated this ADR:

- **Interface ambiguity.** `IdempotencyService.lookupCurrent` + `persistIfAbsent`
  did not communicate the two-phase caller contract; the SQL-level race-safety
  invariant lived implicitly inside `persistIfAbsent`'s `ON CONFLICT ... WHERE
  expires_at <= EXCLUDED.created_at` clause.
- **Outcome encoding ambiguity.** `RegisterResult(boolean eventDuplicate, boolean
  replay)` allowed all four flag combinations but only three made sense. The
  fourth — `(eventDuplicate=true, replay=true)` — was emitted whenever an
  `Idempotency-Key` replay landed on a notification whose event would have
  deduped anyway. The design doc §4 hedged this with "(4 단계 미진입)" — i.e.
  `X-Event-Duplicate` was undefined for that case but the code shipped it
  anyway.

`/improve-codebase-architecture` candidates #1 (registration orchestration) and
#2 (idempotency interface) were merged into one redesign because they are two
sides of the same transaction.

## Decision

Three coupled changes, none of which alters the underlying DB schema or the
race-safety guarantees.

### 1. Document the gate/bind contract on `IdempotencyService`.

Rename:
- `lookupCurrent` → `checkOutcome` (gate phase, read-only).
- `persistIfAbsent` → `bind` (bind phase, atomic write under
  `Propagation.MANDATORY`).

Add a class-level javadoc that codifies the caller contract, the race semantics
(see below), and the load-bearing PG SQL clause (`WHERE expires_at <=
EXCLUDED.created_at`). The SQL stays in the JDBC layer rather than being
reified in Java — moving it would break race safety without producing a deeper
Java-level interface.

### 2. Type the register outcome as `sealed RegisterOutcome`.

Replace `RegisterResult(NotificationDetail, boolean, boolean)` with
`sealed RegisterOutcome { NewlyCreated, EventDuplicate, IdempotentReplay }`.
Each variant maps to one canonical HTTP response (status + headers). The
controller switches on the variant; the compiler enforces exhaustiveness.

Concretely:
- `NewlyCreated` → 202, `X-Event-Duplicate: false`, `X-Idempotent-Replay: false`.
- `EventDuplicate` → 200, `X-Event-Duplicate: true`, `X-Idempotent-Replay: false`.
- `IdempotentReplay` → 200, **`X-Event-Duplicate: false`**, `X-Idempotent-Replay: true`.

The `IdempotentReplay` row drops the previous `X-Event-Duplicate=true` emission.
Idempotency replay supersedes event-duplicate signaling — emitting both lets
clients decode the same outcome two different ways, which is the encoding
ambiguity we are fixing. Existing IT assertions for "Case C/D — both headers
true" are updated accordingly.

### 3. Hoist the `bind` call out of the inserted / event-duplicate branch.

The pre-refactor code called `idempotencyService.persistIfAbsent(...)` in both
branches with identical arguments. Hoisting it above the branch leaves a flat
three-phase body (gate → work → bind) that mirrors the §4 pseudocode 1:1.

### 4. Race semantics — `α` choice (first-write-wins).

Concurrent registrations with the **same `K` and different `H`** can both
initially succeed:

- Both pass the gate (both see Miss).
- Both produce distinct notifications (distinct `event_id` per request).
- Both call `bind`; PG's `ON CONFLICT (idempotency_key)` plus the `WHERE
  expires_at <= EXCLUDED.created_at` clause picks the first writer.
- Both clients receive `202`. The first writer's `H` owns the `K → target`
  mapping; future replays with the loser's `H` see `HitDifferentHash → 409`.

This window is not addressed by the current redesign and is documented as the
**α** choice. The alternative **β** — reserve-then-commit with a nullable
`target_id`, an explicit `PENDING` state, and a reaper for orphan reserves —
would close the window at the cost of a schema migration, a new entity
lifecycle, and additional integration tests.

For a 5-day take-home where the spec table only enumerates the sequential
header matrix (and explicitly hedges the both-replay case with
"(4 단계 미진입)"), **α** is the right ratio of guarantee to scope.

## Rationale

`/improve-codebase-architecture` candidate #2 originally suggested centralizing
race safety into a single `reserve(K, H, targetId) → result` method. That
collapses cleanly on paper but cannot be implemented without a schema change,
because the `targetId` (the `notification.id`) does not exist when the gate
runs. The two phases are structurally sequential — they bracket the
notification insert.

The `bind` method already returns the right race-safety contract from the
JDBC layer; the deepening was about **codifying the two-phase contract** and
**typing the outcome**, not about replacing the mechanism. That mirrors
ADR-0001's framing for failure classification: contract codification is the
load-bearing fix, the underlying mechanism was already correct.

Typing the outcome removes the encoding ambiguity in `RegisterResult`. The
sealed variants force the controller to handle each case explicitly and remove
a flag combination that the spec had already flagged as undefined.

## Consequences

- `RegisterResult` is deleted. `NotificationController` switches on
  `RegisterOutcome` instead of comparing booleans.
- Tier-3 IT assertions for "Case C/D — both replay and event-duplicate true"
  are updated to `X-Event-Duplicate: false` when `X-Idempotent-Replay: true`.
  Affected: `IdempotencyReplayIT` cases C+D, `HeaderAndEventCompositionIT`
  cases C+D, `NotificationFlowIT` step 6.
- `HeaderAndEventCompositionIT` class doc updated — the two headers are no
  longer "independent dimensions"; replay supersedes event-duplicate.
- `IdempotencyService.persist` is retained for tests / future tooling that
  needs unconditional save. Production registration goes through `bind`.
- The α race window remains; ADR documents it explicitly so a future
  `/improve-codebase-architecture` run does not re-suggest β without the
  context.

## When to revisit (promote to β)

Reopen if any of the following hold:

- A real-world incident shows two concurrent same-K-different-H requests both
  being accepted at the response layer, and the eventual 409 on replay is not
  an acceptable resolution.
- We need to guarantee at-most-one notification per `Idempotency-Key`
  regardless of body race (current α guarantees at-most-one *replay target*,
  not at-most-one *accepted notification*).
- The idempotency record's `target_id` becomes nullable for unrelated reasons,
  in which case the reserve shape gets cheaper.

## Related

- ADR-0001 — same contract-codification framing applied to adapter failure
  classification.
- `docs/document.md` §4 — response header matrix (with the
  "(4 단계 미진입)" hedge).
- `/improve-codebase-architecture` candidates #1 and #2, 2026-05-19.
