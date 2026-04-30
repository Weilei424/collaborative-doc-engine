# Collaborative Doc Engine

Real-time collaborative document editing backend (Java 17, Spring Boot, PostgreSQL, Redis, Kafka).

## Running Locally

```bash
docker compose up -d          # start Postgres, Redis, Kafka
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

## Kafka Operations

### Dead-Letter Topic (DLT)

The consumer uses `document-operations.DLT` for unprocessable messages (malformed JSON, exhausted retries).

**Inspect DLT messages:**

```bash
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic document-operations.DLT \
  --from-beginning
```

**Replay DLT back to the main topic after fixing the root cause:**

```bash
# Pipe DLT records back into the main topic
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic document-operations.DLT \
  --from-beginning \
  --max-messages <N> | \
kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic document-operations
```

Replay is safe within the dedup TTL window: the consumer deduplicates by `operationId` via Redis (TTL 5 min). Operations delivered within the last 5 minutes will be suppressed on replay; older replayed operations will be re-broadcast to collaborators.

### Consumer Group Reset (full re-read from main topic)

```bash
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group notification-consumer-group \
  --reset-offsets \
  --topic document-operations \
  --to-earliest \
  --execute
```

## Fault Tolerance

### Redis Degradation

Redis is used for low-latency cross-instance fanout of accepted operations and presence events. It is **best-effort** — a Redis outage does not affect the hot path.

**When Redis is down:**
- `operations.submit` continues to succeed. The submitting client's editor receives the accepted operation via local STOMP fanout (same-instance).
- Cross-instance clients stop receiving real-time updates during the outage.
- The `redis.circuit_open` counter in `/actuator/prometheus` increments each time a publish is silenced because the circuit breaker is already open.
- The `redis.publish_failures` counter increments for each raw Redis publish failure (these failures drive the circuit breaker's failure-rate window toward tripping).
- The readiness probe (`/actuator/health/readiness`) stays `UP` — Redis health is advisory only.

**Client recovery:** Clients on other instances detect version gaps and call `GET /api/documents/{id}/operations?sinceVersion={v}` to catch up. No manual intervention or client reload required.

**Automatic recovery:** Lettuce reconnects automatically with `autoReconnect=true`. Once Redis is back, the `RedisMessageListenerContainer` rebinds subscriptions (within 5s recovery interval) and normal cross-instance fanout resumes. No server restart needed.

**Operator signals:**
- `redis.publish_failures` counter rising indicates Redis is reachable but failing commands; `redis.circuit_open` spiking means the circuit breaker has tripped and all publishes are being dropped.
- `/actuator/health` (requires authentication) shows Redis health as an advisory indicator.
