# V2 Baseline Metrics

> Fill in this document after running the k6 load script against a local compose stack **before** any V2 phase is merged. This is the reference every subsequent phase uses to claim improvement.

## Environment

| Field | Value |
|---|---|
| Date | — |
| Compose stack | postgres:16, redis:7, kafka:3.7 |
| JVM flags | `-Xms256m -Xmx512m` |
| k6 script | `k6/load-test.js` |
| k6 VUs | — |
| k6 duration | — |
| k6 ramp shape | — |
| Notes | — |

## Hot-Path Timer Histogram (from `/actuator/prometheus`)

Submit 1000+ operations during the k6 run, then scrape the actuator endpoint.

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

| Counter | Value |
|---|---|
| `operations.accepted` | — |
| `operations.conflicted` | — |
| `operations.noop` | — |
| `operations.idempotent` | — |

## Placeholder Counters (expected 0 pre-V2)

| Counter | Value |
|---|---|
| `operations.retries` | 0 |
| `operations.resync_required` | 0 |
| `outbox.pending` | 0 |
| `outbox.poison` | 0 |
| `redis.circuit_open` | 0 |

## Notes

<!-- Anything anomalous observed during the run — GC pauses, lock contention spikes, etc. -->
