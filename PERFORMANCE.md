# Performance & Scalability Report

## Overview

This report evaluates the performance characteristics of the collaborative document system under two scenarios:

1. **Baseline (Independent Documents)** – users operate on separate documents (no contention)
2. **Contention (Shared Document)** – multiple users edit the same document concurrently

Benchmarks were conducted using k6.

---

## Benchmark Configuration

- Max Virtual Users: 100
- Duration: ~3 minutes
- Workload:
  - WebSocket-based real-time editing
  - REST operations for setup
- Environment: Local Docker-based deployment

---

## Results Summary

| Metric            | Baseline (Own Doc) | Contention (Shared Doc) |
|------------------|-------------------|--------------------------|
| p95 Latency      | 11 ms             | 3.05 s                   |
| Median Latency   | ~6 ms             | 484 ms                   |
| Throughput       | 261 ops/sec       | 44 ops/sec               |
| Error Rate       | 0%                | 0%                       |

---

## Key Findings

### 1. Excellent Performance Without Contention

- Sub-10ms latency for most operations
- High throughput (~261 ops/sec)
- Efficient WebSocket + Redis fanout pipeline

---

### 2. Contention as Primary Bottleneck

When multiple users edit the same document:

- Writes are serialized via pessimistic locking
- Operations queue behind the lock
- Latency increases linearly with concurrency

**Impact:**
- p95 latency rises to ~3 seconds
- Throughput drops to ~44 ops/sec

---

### 3. Operational Transformation Cost

Each operation must be transformed against all concurrent operations:

- Transformation complexity increases with concurrency
- Additional CPU + latency overhead

---

### 4. Fan-Out Amplification

- Each accepted operation is broadcast to all connected users
- Observed ~32:1 message amplification ratio

This demonstrates:
- Efficient Redis pub/sub handling
- No message loss under high load

---

### 5. Connection Overhead

- WebSocket connection latency increases under load
- Caused by authentication + DB access contention

---

## System Limits

| Aspect                          | Limit |
|---------------------------------|------|
| Max throughput (single doc)     | ~44 ops/sec |
| Comfortable concurrency         | ~30–40 users |
| Latency > 1s (median)           | ~50–60 users |
| Failure threshold               | Not reached (0% errors) |

---

## Design Tradeoffs

### Consistency vs Latency

This system prioritizes:

- Strong consistency
- Deterministic ordering
- Conflict-free document state

At the cost of:

- Higher latency under contention
