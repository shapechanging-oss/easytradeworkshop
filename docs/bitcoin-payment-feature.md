# Bitcoin Payment Feature — Design, Scaling Analysis & Production Notes

This document accompanies the bitcoin-payment feature added to `credit-card-order-service`.
The analysis is grounded in live production telemetry pulled from Bluebox (`bluebox ask`)
and a read of the current service code. All production numbers below are from the 24h
window around 2026-09-29; conversation ids are cited so the evidence is traceable.

> **Caveat (per Bluebox):** A clean or healthy monitoring result is one input, not a
> guarantee. Monitoring reflects only current, observed behaviour; new load, untested
> paths and un-instrumented effects can still cause problems. This service is **not**
> currently OpenTelemetry-instrumented, so some of the scaling analysis below rests on
> code reading rather than measured telemetry — those points are marked.

---

## 1. Components under the most stress (at 100x current traffic)

Current baseline (Bluebox, 24h; conv `conv_4Tkw5V6NAZ95BjpZt9jg2I` / `conv_59JmnnWsKSghib1EIZrp9K`):

| Component | Current load | 100x projected | Risk at 100x |
|---|---|---|---|
| **MSSQL database** | ~8,300 calls/24h (~0.10/s) from this service | ~10 calls/s + write amplification | **HIGHEST — and a blind spot.** No CPU/mem/connection telemetry exists for the DB. The service opens a **fresh JDBC connection per call** (`DriverManager.getConnection` in `DatabaseHelper.getConnection()`), and several write paths open a *second* connection internally. Databases saturate on connections/IO long before app CPU. This is the component most likely to break first. |
| **feature-flag-service** | ~185,400 req/24h total (~2.15/s); ~9,076 from this service | ~215/s | **HIGH — first *monitored* tier to saturate.** Highest average+peak CPU of the group today. `getLatestStatus` evaluates a flag on **every** request over a fresh `HttpClient` with **no timeout**; a slow flag service would block request threads and cascade. |
| **credit-card-order-service (this service)** | ~9,400 req/24h (~0.11/s); busiest = `GET /v1/orders/{accountId}/status/latest` (9,073/24h) | ~11/s | **MEDIUM.** JVM heap/CPU headroom ~87–98% today. Embedded Tomcat thread pool + per-call DB connections become the constraint under load, not heap. |
| **third-party-service** | 21 calls/24h (negligible) | ~2,100/24h | **LOW.** Async, scheduler-driven (`POST /v1/manufacturer`), off the request hot path. |

**Bottom line:** at 100x, the order of stress is **DB connections → feature-flag-service CPU/latency → this service's thread pool**. The new bitcoin flow adds DB writes and (optionally) an outbound settlement call, so it lands squarely on the two highest-risk tiers — which is why the changes below target connection pooling and timeouts first.

---

## 2. Proposed architecture for 100x

The feature is built to be **safe by default and horizontally scalable**. Concrete changes in this PR, plus the operational recommendations to reach a full 100x posture.

### Implemented in this PR (highest-leverage, lowest-risk)
1. **Database connection pooling (HikariCP).** Replaces per-call `DriverManager.getConnection()` with a bounded, reused pool. This is the single most important change for the DB tier — it caps concurrent connections (protecting the DB, the likely first bottleneck) and removes per-request connect overhead. Pool size is configurable via env (`DB_POOL_MAX_SIZE`, default 20) so it can be tuned per replica against the DB's real connection ceiling.
2. **Bounded HTTP timeouts** on the feature-flag / outbound clients (connect + request), so a slow dependency sheds load instead of exhausting request threads. Configurable via `HTTP_CLIENT_TIMEOUT_SECONDS` (default 5s).
3. **Feature-flag gating** of the entire bitcoin flow (`bitcoin_payments_enabled`, default **false**). Roll out progressively; instant kill-switch with no redeploy. Mirrors the existing `credit_card_meltdown` OpenFeature pattern.
4. **Idempotent create** keyed on a client-supplied idempotency reference, so retries under load (or at-least-once upstreams) don't double-charge. Enforced by a unique index on the new table.
5. **Stateless design preserved.** No new in-memory session state → the service remains freely horizontally scalable behind the existing reverse proxy; add replicas + an HPA (the helm chart already supports `hpa`).

### Recommended alongside (operational, called out — not all code)
- **Make the DB horizontally readable / vertically sized:** the DB is the shared bottleneck. Add read replicas for the read-heavy `status/latest` path, and right-size the instance. **Add DB monitoring first** (see §3) so this is measured, not guessed.
- **Cache feature-flag reads:** a short TTL cache (seconds) on flag lookups cuts ~215/s of flag traffic dramatically since the flag rarely changes.
- **Async settlement:** keep any bitcoin settlement/confirmation off the request path (scheduler/queue, like the existing `WorkScheduler`), so request latency is decoupled from blockchain confirmation times.
- **Autoscaling:** enable the helm `hpa` for this service and feature-flag-service, targeting CPU + request latency.

```
                         (reverse proxy / ingress)
                                   │
                    ┌──────────────┴──────────────┐   N replicas, stateless
                    │  credit-card-order-service   │   + HPA on CPU/latency
                    │  /v1/payments/bitcoin  (NEW) │
                    └───┬───────────────┬──────────┘
        HikariCP pool   │               │  bounded-timeout HttpClient
        (bounded conns) │               │  (+ short-TTL flag cache: recommended)
                        ▼               ▼
                 ┌────────────┐   ┌──────────────────────┐
                 │  MSSQL DB  │   │ feature-flag-service  │
                 │ + replicas │   │ (kill-switch flag)    │
                 │ (recommend)│   └──────────────────────┘
                 └────────────┘
```

---

## 3. Existing production issues to address alongside

1. **CRITICAL — divide-by-zero taking the payment service to 100% failure (fixed in this PR).**
   `OrderController.CountArythmeticSequenceTotal` (line ~330) hard-coded `theGreatDivider = 0`,
   throwing `java.lang.ArithmeticException: / by zero` on `GET /v1/orders/{accountId}/status/latest`.
   It is demo code gated behind the OpenFeature flag **`credit_card_meltdown`**; production was
   observed **100% failing** on that endpoint (430/430 requests, conv `conv_2VoaOg8R83K7cWPN7GjH0X`),
   which means the flag is currently **enabled in production**. This PR sets the divider to its
   correct non-zero value (`2`, per the code's own comment) so the endpoint no longer throws.
   **Operational action required (not code): disable the `credit_card_meltdown` flag in production.**

2. **HIGH — no DB connection pool / no HTTP timeouts.** Addressed by the pooling + timeout changes
   above; these were latent scaling bugs regardless of the bitcoin feature.

3. **MEDIUM — observability & alerting gaps (operational):**
   - This service has **no OpenTelemetry instrumentation**. A 100% outage did **not** raise a
     Bluebox/Davis problem — recommend instrumenting the service and tuning alerting so an
     outage of this size pages automatically.
   - **No SLOs** are defined for this chain. Define p95-latency and error-rate SLOs for
     `credit-card-order-service` and `feature-flag-service` to give a 100x load test a pass/fail bar.
   - **MSSQL has zero infrastructure telemetry** — add a host/DB agent so DB headroom can be
     assessed before any load test.

4. **LOW / separate — `broker-service` `KeyNotFoundException`.** A recurring error in a different
   service (51 occurrences/7d). Out of scope for this PR; flagged for separate root-causing.
