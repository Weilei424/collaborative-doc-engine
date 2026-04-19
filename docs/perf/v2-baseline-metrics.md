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

> Actuator scrape was not captured during this run. Scrape `/actuator/prometheus` during a future run to populate per-timer p50/p95/p99 values. These are the V2 improvement gates.

| Timer | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---|---|---|
| `loadDocument` | — | — | — | — |
| `lockAcquisition` | — | — | — | — |
| `loadInterveningOps` | — | — | — | — |
| `otTransformLoop` | — | — | — | — |
| `perOpJsonParse` | — | — | — | — |
| `treeApply` | — | — | — | — |
| `persistOperation` | — | — | — | — |
| `publishRedis` | — | — | — | — |
| `publishKafka` | — | — | — | — |

## Counter Snapshot (at end of k6 run)

> `k6 operations_accepted` is the client-side k6 counter (incremented on broadcast receipt). It is **not** the backend Micrometer counter `operations.accepted`. Populate the Micrometer rows from an `/actuator/prometheus` scrape taken at the end of the run.

| Counter | Source | Value |
|---|---|---|
| `operations_accepted` (k6 client) | k6 summary | 21,260 |
| `operations.accepted` (Micrometer) | `/actuator/prometheus` | — |
| `operations.conflicted` | `/actuator/prometheus` | — |
| `operations.noop` | `/actuator/prometheus` | — |
| `operations.idempotent` | `/actuator/prometheus` | — |

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
- **Actuator timers not captured**: per-timer histogram rows above are blank. To populate them, scrape `http://localhost:8080/actuator/prometheus` at steady-state during the next run and filter for `_seconds_bucket` lines.
- **operations.conflicted / noop / idempotent**: not surfaced by the k6 script; require an actuator counter scrape or log analysis.
- **WS connect spike**: `ws_connecting` p95 = 9 ms, max = 4.1 s — the max aligns with the stress ramp and is likely lock-induced back-pressure, not a connection-setup issue.
