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
