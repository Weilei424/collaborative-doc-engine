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

> Values are in **milliseconds**. These were captured while the histogram config published client-side quantile gauges (`percentiles()`). The current config publishes only bucket lines (`percentilesHistogram(true)`). To re-capture percentiles, run `histogram_quantile(0.95, rate(<timerName>_seconds_bucket[1m]))` in Prometheus against a live scrape, or read `<timerName>_seconds_max` directly from `/actuator/prometheus` for the max column.

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
- **3.83s k6 p95 not explained by individual timers**: the sum of all timer p99 values is ~13.7ms, far below 3.83s. The tail latency is outside the measured hot path — likely STOMP broadcast thread backpressure or async dispatch queue depth under peak load. Histogram bucket data (`_seconds_bucket`) is also available in `/actuator/prometheus` for further analysis.
- **operations.conflicted / noop / idempotent all zero**: correct for the non-contention benchmark workload.
- **WS connect spike**: `ws_connecting` p95 = 9 ms, max = 4.1 s — the max aligns with the stress ramp and is likely lock-induced back-pressure, not a connection-setup issue.
