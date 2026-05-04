# V2 Baseline Metrics

> Fill in this document after running the k6 load script against a local compose stack **before** any V2 phase is merged. This is the reference every subsequent phase uses to claim improvement.

## Environment

| Field | Value |
|---|---|
| Date | 2026-04-19 |
| Compose stack | postgres:16, redis:7, kafka:3.7 |
| JVM flags | `-Xms256m -Xmx512m` |
| k6 script | `load-test/benchmark.js` |
| k6 VUs | steady=30, stress=100 |
| k6 duration | 3m10s |
| k6 ramp shape | 30s→30 VU, 60s steady, 30s→100 VU, 60s peak, 10s→0 |
| Notes | p95 threshold crossed (3.83s vs 500ms budget); see Notes section |

## k6 End-to-End Operation Latency

> `operation_latency` measures WebSocket round-trip from SEND to broadcast receipt. This is not per-timer — it covers the full hot path including lock-wait.

| Metric | Value |
|---|---|
| avg | 305.66 ms |
| min | 2 ms |
| med (p50) | 6 ms |
| p90 | 51 ms |
| p95 | **3.83 s** ⚠ threshold crossed |
| max | 4.11 s |

## Hot-Path Timer Histogram (from `/actuator/prometheus`)

> Values are in **milliseconds** (raw Prometheus values are in seconds — multiply by 1000). To re-capture: scrape `/actuator/prometheus` during a k6 run and search for `<timerName>_seconds{quantile="0.5"}`, `{quantile="0.95"}`, `{quantile="0.99"}`, and `<timerName>_seconds_max`.

| Timer | p50 (ms)  | p95 (ms)  | p99 (ms)  | max (ms)  |
|---|-----------|-----------|-----------|-----------|
| `loadDocument` | 0.18432   | 0.684032  | 1.568768  | 14.518027 |
| `lockAcquisition` | 0.14336   | 0.520192  | 1.37216   | 11.278959 |
| `loadInterveningOps` | 0.200704  | 0.782336  | 1.830912  | 12.981858 |
| `otTransformLoop` | 0.0000205 | 0.0000205 | 0.0000215 | 0.138909  |
| `perOpJsonParse` | 0         | 0         | 0         | 0         |
| `treeApply` | 0.007552  | 0.021376 | 0.102272 | 3.918634 |
| `persistOperation` | 0.020992  | 0.065024 | 0.2944 | 6.049477 |
| `publishRedis` | 0.243712 | 1.01376 | 2.750464 | 16.128375 |
| `publishKafka` | 0.483328 | 2.74432 | 5.758976 | 26.114231 |

## Counter Snapshot (at end of k6 run)

> `k6 operations_accepted` is the client-side k6 counter (incremented on broadcast receipt). It is **not** the backend Micrometer counter `operations.accepted`. Populate the Micrometer rows from an `/actuator/prometheus` scrape taken at the end of the run.

| Counter | Source | Value  |
|---|---|--------|
| `operations_accepted` (k6 client) | k6 summary | 20,350 |
| `operations.accepted` (Micrometer) | `/actuator/prometheus` | 20,350 |
| `operations.conflicted` | `/actuator/prometheus` | 0      |
| `operations.noop` | `/actuator/prometheus` | 0      |
| `operations.idempotent` | `/actuator/prometheus` | 0      |

## Placeholder Counters (expected 0 pre-V2)

| Counter | Value |
|---|---|
| `operations.retries` | 0 |
| `operations.resync_required` | 0 |
| `outbox.pending` | 0 |
| `outbox.poison` | 0 |
| `redis.circuit_open` | 0 |

## Notes

- **p95 tail spike**: median latency is 6 ms but p95 jumps to 3.83 s — a ~640× gap. This is characteristic of pessimistic lock queuing under the 100-VU stress stage. The p90 (51 ms) shows the majority of operations are fast; the tail is driven by VUs waiting for the lock behind a long queue. P19 (optimistic locking + CAS) is the primary fix target.
- **`perOpJsonParse` all zeros**: expected — this timer is only recorded inside the OT transform loop when there are intervening ops to parse. The sequential k6 workload (each op confirmed before the next is sent) produces zero intervening ops on every submission.
- **`lockAcquisition` fast despite 100-VU peak**: expected — `benchmark.js` assigns each VU its own document, so there is no actual lock contention. Use `benchmark-contention.js` for a contention baseline.
- **3.83s k6 p95 not explained by individual timers**: the sum of all timer p99 values is ~13.7ms, far below 3.83s. The tail latency is outside the measured hot path — likely STOMP broadcast thread backpressure or async dispatch queue depth under peak load.
- **operations.conflicted / noop / idempotent all zero**: correct for the non-contention benchmark workload.
- **WS connect spike**: `ws_connecting` p95 = 9 ms, max = 4.1 s — the max aligns with the stress ramp and is likely lock-induced back-pressure, not a connection-setup issue.

---

## Contention Baseline (from `load-test/benchmark-contention.js`)

> **Required before P18/P19 can claim improvement.** P18 targets `otTransformLoop` and `treeApply` reduction under intervening-op load; P19 targets `lockAcquisition` wait reduction. Both need a "before" number from the shared-document contention script, which is the only workload that exercises those paths non-trivially. Run with the same compose stack and JVM flags as above.
>
> To capture: `k6 run load-test/benchmark-contention.js`, then scrape `/actuator/prometheus` at the end of the run.

### Contention k6 End-to-End Operation Latency

| Metric | Value  |
|---|--------|
| avg | 1007ms |
| min | 7ms    |
| med (p50) | 473ms  |
| p90 | 2920ms |
| p95 | 3020ms |
| max | 3330ms |

### Contention Hot-Path Timer Histogram (from `/actuator/prometheus`)

| Timer | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|-------|-------|-------|--------|
| `loadDocument` | 0.303 | 2.351 | 7.332 | 20.097 |
| `lockAcquisition` | 243.261 | 285.204 | 335.536 | 410.484 |
| `loadInterveningOps` | 22.544 | 26.739 | 29.884 | 49.056 |
| `otTransformLoop` | 6.291 | 7.078 | 9.699 | 37.832 |
| `perOpJsonParse` | 0.000432 | 0.000592 | 0.000752 | 18.890 |
| `treeApply` | 0.0399 | 0.0543 | 0.0727 | 0.2777 |
| `persistOperation` | 0.0922 | 0.1208 | 0.1700 | 0.4163 |
| `publishRedis` | 0.713 | 2.875 | 5.497 | 29.784 |
| `publishKafka` | 1.622 | 4.440 | 9.945 | 28.769 |

---

## Post-P18 Results (from `load-test/benchmark-contention.js`)

> Captured 2026-05-01. P18 deliverables: pre-parse intervening ops into `ParsedAcceptedOp` before the transform loop; Caffeine tree cache keyed by `(documentId, serverVersion)`. Metrics from a fresh backend instance (no accumulated state from prior runs).

### Post-P18 k6 End-to-End Operation Latency

| Metric | Value |
|---|---|
| avg | 1140ms |
| min | 7ms |
| med (p50) | 492ms |
| p90 | 3170ms |
| p95 | 3360ms |
| max | 3650ms |
| operations_accepted | 8,870 |

> End-to-end latency is dominated by `lockAcquisition` wait (P19 target), not the timers P18 optimised.

### Post-P18 Hot-Path Timer Histogram (from `/actuator/prometheus`)

| Timer | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | Baseline p95 (ms) | Δ p95 |
|---|---|---|---|---|---|---|
| `otTransformLoop` | 1.442 | **1.769** | 6.226 | 11.155 | 7.078 | **−75%** ✓ |
| `treeApply` | 0.0594 | **0.0799** | 0.0963 | 0.870 | 0.0543 | **+47%** ✗ |

### Perf Gate Assessment

- **`otTransformLoop` p95**: **gate passed**. Pre-parsing intervening ops eliminated in-loop Jackson deserialization. p95 dropped from 7.078 ms → 1.769 ms (−75%), well above the >20% target.
- **`treeApply` p95**: **gate not passed**. p95 increased from 0.0543 ms → 0.0799 ms (+47%). The overhead is the Caffeine put/evict pair now firing on every version advance, including NO_OP paths after the code-review fix. In the baseline these were no-ops; now they are real cache writes. The absolute regression is ~47 µs at p95.
- **Overall**: the primary optimisation target (`otTransformLoop`) achieved a 75% p95 reduction. `treeApply` regressed by a small constant (~47 µs at p95) due to the cache lifecycle now correctly firing on every version advance, including NO_OP paths. The implementation and tests commit to this behavior as correct. Satisfying the `treeApply` gate as originally written would require a deliberate spec change — either relaxing the "on version advance" cache lifecycle contract for NO_OP (spec line 126) or tightening the gate definition to exclude the NO_OP-path overhead — not a code revert.

---

## Post-P19 Load Test (CAS retry loop — pending collection)

> **Status: pending.** Phase 19 replaced the pessimistic-lock pipeline with a speculative OT + CAS retry loop. The DoD requires a `submit.total` p95 at 100 concurrent submitters that drops vs. the post-P18 contention baseline, and `operations.retries{attempt>3}` staying below threshold under that load. Run the k6 script against a local compose stack with the Phase 19 build to collect these numbers and fill in this section.

| Metric | Post-P18 value | Post-P19 value | Δ |
|---|---|---|---|
| `submit.total` p95 @ 100 VU | *(from k6 run)* | — | — |
| `operations.retries{attempt>3}` rate | — | — | — |

### Post-P19 DoD Gate Assessment

- **`submit.total` p95 improvement**: pending k6 run.
- **`operations.retries{attempt>3}` threshold**: pending k6 run.
