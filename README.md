# Collaborative Document Engine

## Project Overview

A real-time collaborative document editing backend that solves the multi-user concurrent editing problem through server-authoritative operational transformation. Multiple clients can edit the same document simultaneously; the server serializes all operations, resolves conflicts, and fans out accepted operations to all connected instances. This project is a portfolio and interview reference implementation demonstrating horizontally scalable backend design with Spring Boot.

---

## Architecture Overview

| Component | Role |
|---|---|
| PostgreSQL | Source of truth: documents, operation history, ACL state |
| Flyway | Schema lifecycle owner |
| Redis Pub/Sub | Low-latency accepted-operation fanout and presence coordination between instances |
| Kafka | Durable event stream: audit, replay, analytics, async consumers |
| STOMP/WebSocket | Client collaboration transport |
| Spring Security | Request authentication via `X-User-Id` filter (JWT upgrade path noted) |

### Multi-instance collaboration flow

1. Client connects to any backend instance and joins a document channel
2. Client submits an operation with `operationId`, `baseVersion`, and typed payload
3. Instance validates ACL, acquires pessimistic lock on the document row
4. Instance checks idempotency, transforms against any intervening ops if needed
5. Accepted operation and updated document projection are persisted transactionally
6. Accepted operation published to Redis (all instances fan out to local WebSocket clients)
7. Accepted operation published to Kafka (durable, replayable, async consumers)

---

## API Surface

### REST Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/documents` | Create a new document |
| `GET` | `/api/documents` | List documents accessible to the authenticated user |
| `GET` | `/api/documents/{id}` | Get a document by ID |
| `PUT` | `/api/documents/{id}` | Update document metadata |
| `DELETE` | `/api/documents/{id}` | Delete a document |
| `GET` | `/api/documents/{documentId}/collaborators` | List collaborators |
| `POST` | `/api/documents/{documentId}/collaborators` | Add a collaborator |
| `PUT` | `/api/documents/{documentId}/collaborators/{userId}` | Update collaborator role |
| `DELETE` | `/api/documents/{documentId}/collaborators/{userId}` | Remove a collaborator |
| `PUT` | `/api/documents/{documentId}/collaborators/owner` | Transfer document ownership |

All requests require an `X-User-Id` header containing a valid user UUID.

### STOMP Destinations

**Send (client → server):**

| Destination | Description |
|---|---|
| `/app/documents/{documentId}/sessions.join` | Join a document collaboration session |
| `/app/documents/{documentId}/sessions.leave` | Leave a document collaboration session |
| `/app/documents/{documentId}/presence.update` | Broadcast cursor/presence update |
| `/app/documents/{documentId}/operations.submit` | Submit an edit operation |

**Subscribe (server → client):**

| Topic | Description |
|---|---|
| `/topic/documents/{documentId}/sessions` | Session snapshot on join/leave |
| `/topic/documents/{documentId}/presence` | Presence events (cursor positions) |
| `/topic/documents/{documentId}/operations` | Accepted operation broadcasts |

---

## Local Startup

1. **Prerequisites:** Java 17, Docker
2. Start infrastructure:
   ```
   docker compose up -d
   ```
3. Start the application:
   ```
   cd backend && ./mvnw spring-boot:run
   ```
4. Verify:
   ```
   curl http://localhost:8080/actuator/health
   ```

---

## Running Tests

```
cd backend
./mvnw test
```

Note: `RedisAcceptedOperationFanoutTest` is skipped without Docker (`@Testcontainers(disabledWithoutDocker = true)`).

---

## Design Decisions

- **Server-authoritative OT over CRDT** — deterministic server ordering keeps the conflict path simple and auditable; CRDT generality is not needed for the MVP operation set.

- **Pessimistic lock for version slot assignment** — `SELECT FOR UPDATE` on the document row serializes concurrent submits without optimistic retry loops; correct for a write-heavy collaboration path.

- **Redis vs Kafka responsibility split** — Redis for speed (sub-millisecond fanout, presence), Kafka for durability (replay, audit, downstream consumers); no overlap.

- **H2 in tests, Postgres in production** — keeps the test suite fast and self-contained; schema validated by Flyway on both; Testcontainers deferred due to local Docker constraints.

- **`X-User-Id` header identity** — pragmatic for portfolio/interview demonstration; the deferred upgrade path to JWT is noted in `BACKLOG.md`.
