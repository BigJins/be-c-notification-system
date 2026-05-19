# ADR-0001 — Adapter-owned failure classification

- Status: Accepted
- Date: 2026-05-19
- Deciders: BE-C maintainer

## Context

`docs/document.md` §5 enumerates a catalog of Transient vs Permanent failure
classifications (IOException/5xx/timeouts → Transient; 4xx/`IllegalArgumentException`/
payload too large → Permanent; unknown `RuntimeException` → Transient conservatively).

The catalog has to live somewhere in code. Three shapes were considered during the
`/improve-codebase-architecture` review (candidate #3):

- **A — Adapter-owned.** Each `ChannelAdapter` catches its transport's exceptions and
  returns `DispatchResult.{Success, Transient, Permanent}` directly. Adapters do not throw.
- **B — Central classifier.** Adapters return Success or throw; `DeliveryRelayService`
  unwraps and calls a pure `FailureClassifier(Throwable) → DispatchResult` that owns the
  whole §5 catalog.
- **C — Hybrid.** Adapters throw a typed `TransportFailure(Kind, reason, cause)` carrying
  a hint; classifier respects the hint and falls back to the generic catalog.

Before this decision, the contract was undocumented. `DeliveryRelayService` had a
weak fallback (`classifyException`: `IllegalArgumentException` → Permanent, else →
Transient) that silently re-classified anything an adapter happened to throw.
Whether that fallback was a *safety net* or the *real classifier* was unclear, which
risked hiding bugs as quiet retries.

## Decision

**Choose A.** Adapter owns failure classification. `DeliveryRelayService` keeps only:

1. Dispatch timeout policy (`TimeoutException` → `TransientFailure`) — owned by the
   relay because the `CompletableFuture.get(timeout)` wrapper is the relay's policy,
   not the adapter's.
2. Thread-interrupt handling (`InterruptedException` → `TransientFailure`) — structural.
3. A **contract-violation guard**: if the adapter throws despite the contract,
   `recordDesignViolation("adapter_contract_violation")` + `log.error` +
   `PermanentFailure("adapter_contract_violation", cause)`. The delivery goes DEAD
   immediately so the bug is visible in the design-violation alert, not buried in
   retry storms.

The `ChannelAdapter` interface javadoc is now load-bearing — it states the
classification contract and references §5 as the catalog.

## Rationale

The first-order problem was **contract codification, not classifier strength**.
With a documented contract:

- The §5 catalog naturally co-locates with the exception types it classifies
  (SMTP-specific in `EmailAdapter`, future HTTP-specific elsewhere). Transport
  knowledge does not leak into a generic classifier.
- The relay's fallback stops impersonating a classifier. It is now an
  unambiguous guard: "adapter violated its contract — DEAD now, fix the bug."
- Generic rules (`IOException` → Transient) duplicate across adapters, but with
  N = 1 production adapter (EmailAdapter mock) the duplication cost is zero.
- A central classifier (B) is premature optimization at N = 1 and forces the
  classifier to import every transport's exception types — coupling reversal.

## Consequences

- `DispatchResult` sealed hierarchy unchanged. `RetryPolicy.shouldDead` semantics
  unchanged.
- `InAppAdapter`'s `PermanentFailure("inapp_dispatch_attempted", …)` return is
  unaffected — it is an invariant-violation marker, not a failure classification.
- One new test asserts the 3-way lock for the contract-violation path:
  `PermanentFailure("adapter_contract_violation", …)`,
  `notification.design.violation{kind=adapter_contract_violation}` +1,
  `delivery=DEAD` + `attempt=FAILED`.
- The `notification.design.violation` alert now fires for both `inapp_dispatch_attempted`
  and `adapter_contract_violation`. Dashboard caption updated accordingly (Phase 6 README).

## When to revisit (promote to C)

Revisit if **two or more production adapters** end up repeating the same
classification rules (e.g. both an SMS HTTP adapter and a webhook HTTP adapter
classifying HTTP 4xx → Permanent / 5xx → Transient). At that point the
duplication cost overtakes the coupling cost, and a `FailureClassifier`
keyed on a typed `TransportFailure(Kind, …)` exception (the C-shape) becomes
the cheaper design.

Triggering signals:

- ≥ 2 adapters import HTTP status-code constants for classification.
- Identical try/catch branches for `IOException` / `SocketTimeoutException`
  across adapters.
- A bug fixed in one adapter's classifier that needs porting to others.

## Related

- Candidate #3 of `/improve-codebase-architecture` review, 2026-05-19.
- `docs/document.md` §5 — failure classification catalog (source of truth).
- `docs/document.md` §6 — `DeliveryRelayService.relay` is the only consumer of
  `DispatchResult`; this ADR does not change that.
