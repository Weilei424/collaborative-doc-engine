# Collaborative Document Engine

## Project Overview

A real-time collaborative document editing backend that solves the multi-user concurrent editing problem through server-authoritative operational transformation. Multiple clients can edit the same document simultaneously; the server serializes all operations, resolves conflicts, and fans out accepted operations to all connected instances.

This project is a portfolio and interview reference implementation demonstrating a horizontally scalable backend built with Spring Boot, PostgreSQL, Redis, Kafka, and STOMP/WebSocket collaboration.

## Business Context

### Purpose

Enable multiple users to read, edit, and share structured documents in real time while the server remains the ordering authority for accepted changes.

### Target Users

- Owners creating and managing documents
- Collaborators with role-based access
- Engineers and interviewers evaluating real-time collaboration architecture

### Core Value

- Durable document state and audit-friendly operation history
- Low-latency collaboration across backend instances
- Clear separation between hot-path fanout and durable event streaming
- An architecture that is practical to explain, defend, and evolve

## Architecture Overview

| Component | Role |
|---|---|
| PostgreSQL | Source of truth for documents, collaborators, operation history, and current document projection |
| Flyway | Schema lifecycle owner |
| JPA | Relational mapping and persistence validation |
| Redis Pub/Sub | Low-latency accepted-operation fanout and presence/session propagation between instances |
| Kafka | Durable accepted-operation stream for replay, audit, analytics, and async consumers |
| STOMP/WebSocket | Client collaboration transport |
| Spring Security | Authentication and request gating, with a pragmatic MVP identity flow and JWT-capable path |

### Architecture Summary

- PostgreSQL owns durable state.
- Redis owns speed-sensitive fanout and collaboration coordination.
- Kafka owns durable downstream event streaming.
- The backend is the ordering authority for accepted operations.
- Clients never decide the canonical server version.

## Data Flow Diagram

<svg viewBox="0 0 1240 620" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="data-flow-title">
  <title id="data-flow-title">Collaborative document engine data flow</title>
  <defs>
    <marker id="arrow" markerWidth="10" markerHeight="10" refX="9" refY="5" orient="auto">
      <path d="M0,0 L10,5 L0,10 z" fill="#666666"/>
    </marker>
  </defs>

  <rect x="60" y="240" width="180" height="104" rx="16" fill="#4299e1"/>
  <text x="150" y="280" text-anchor="middle" fill="white" font-size="20" font-weight="700">Clients</text>
  <text x="150" y="306" text-anchor="middle" fill="white" font-size="13">React UI + SockJS/STOMP</text>
  <text x="150" y="325" text-anchor="middle" fill="white" font-size="13">REST + WebSocket</text>

  <rect x="320" y="90" width="220" height="106" rx="16" fill="#ed8936"/>
  <text x="430" y="132" text-anchor="middle" fill="white" font-size="19" font-weight="700">REST Layer</text>
  <text x="430" y="158" text-anchor="middle" fill="white" font-size="13">Auth, CRUD, sharing, search</text>
  <text x="430" y="177" text-anchor="middle" fill="white" font-size="13">Document controllers</text>

  <rect x="320" y="260" width="220" height="120" rx="16" fill="#ed8936"/>
  <text x="430" y="303" text-anchor="middle" fill="white" font-size="19" font-weight="700">Collaboration Layer</text>
  <text x="430" y="329" text-anchor="middle" fill="white" font-size="13">Join, presence, submit op</text>
  <text x="430" y="348" text-anchor="middle" fill="white" font-size="13">ACL + session checks</text>

  <rect x="640" y="90" width="280" height="290" rx="18" fill="#9f7aea"/>
  <text x="780" y="132" text-anchor="middle" fill="white" font-size="19" font-weight="700">Spring Services</text>
  <text x="780" y="160" text-anchor="middle" fill="white" font-size="13">DocumentService</text>
  <text x="780" y="184" text-anchor="middle" fill="white" font-size="13">CollaborationSessionService</text>
  <text x="780" y="208" text-anchor="middle" fill="white" font-size="13">CollaborationPresenceService</text>
  <text x="780" y="232" text-anchor="middle" fill="white" font-size="13">DocumentOperationService</text>
  <text x="780" y="268" text-anchor="middle" fill="white" font-size="13">Authorization, idempotency,</text>
  <text x="780" y="287" text-anchor="middle" fill="white" font-size="13">transform and fanout</text>
  <text x="780" y="317" text-anchor="middle" fill="white" font-size="13">Locks document and assigns</text>
  <text x="780" y="336" text-anchor="middle" fill="white" font-size="13">next server version</text>
  <text x="780" y="365" text-anchor="middle" fill="white" font-size="13">Persists projection + op log</text>

  <rect x="1000" y="70" width="170" height="118" rx="16" fill="#48bb78"/>
  <text x="1085" y="112" text-anchor="middle" fill="white" font-size="19" font-weight="700">PostgreSQL</text>
  <text x="1085" y="138" text-anchor="middle" fill="white" font-size="13">Documents</text>
  <text x="1085" y="157" text-anchor="middle" fill="white" font-size="13">Collaborators + ops</text>

  <rect x="1000" y="232" width="170" height="118" rx="16" fill="#4299e1"/>
  <text x="1085" y="274" text-anchor="middle" fill="white" font-size="19" font-weight="700">Redis</text>
  <text x="1085" y="300" text-anchor="middle" fill="white" font-size="13">Low-latency fanout</text>
  <text x="1085" y="319" text-anchor="middle" fill="white" font-size="13">Sessions + presence</text>

  <rect x="1000" y="394" width="170" height="118" rx="16" fill="#48bb78"/>
  <text x="1085" y="436" text-anchor="middle" fill="white" font-size="19" font-weight="700">Kafka</text>
  <text x="1085" y="462" text-anchor="middle" fill="white" font-size="13">Accepted ops stream</text>
  <text x="1085" y="481" text-anchor="middle" fill="white" font-size="13">Replay + audit</text>

  <rect x="640" y="450" width="280" height="108" rx="16" fill="#48bb78"/>
  <text x="780" y="490" text-anchor="middle" fill="white" font-size="19" font-weight="700">Outputs</text>
  <text x="780" y="516" text-anchor="middle" fill="white" font-size="13">Topic broadcasts to connected</text>
  <text x="780" y="535" text-anchor="middle" fill="white" font-size="13">collaborators and async consumers</text>

  <path d="M240,265 L320,160" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M240,318 L320,320" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M540,145 L640,145" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M540,320 L640,320" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M920,130 L1000,130" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M920,285 L1000,285" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M920,350 C960,385 972,415 1000,450" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M780,380 L780,450" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M1000,285 C930,285 920,475 920,504" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M1000,453 L920,504" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)"/>
  <path d="M1000,285 C880,230 560,500 240,344" stroke="#666666" stroke-width="3" fill="none" marker-end="url(#arrow)" stroke-dasharray="10 7"/>

  <text x="688" y="62" fill="#666666" font-size="13">Server-authoritative ordering and persistence</text>
  <text x="915" y="220" fill="#666666" font-size="13">Hot-path fanout</text>
  <text x="912" y="386" fill="#666666" font-size="13">After-commit durable stream</text>
  <text x="165" y="374" fill="#666666" font-size="13">Subscribers receive sessions, presence, accepted ops</text>
</svg>

The collaboration hot path stays server-controlled: a client submits an operation with `operationId` and `baseVersion`, the backend validates access, transforms as needed, persists the accepted result, then publishes to Redis for low-latency fanout and Kafka for durability.

## Processing Pipeline

1. **Ingress**  
   REST calls handle document CRUD and sharing. STOMP endpoints handle join, leave, presence, and edit submission.

2. **Identity and ACL**  
   Spring Security authenticates HTTP and WebSocket traffic, then document-specific authorization checks gate read or write access.

3. **Validation**  
   Operation shape, session state, and collaborator semantics are validated before the write path proceeds.

4. **Ordering and Rebase**  
   The backend locks the document row, checks idempotency, loads intervening operations, and rebases or converts to `NO_OP` when needed.

5. **Persistence**  
   The materialized document snapshot and immutable operation record are saved in PostgreSQL under one transactional flow.

6. **Distribution**  
   Local subscribers receive immediate topic broadcasts, Redis propagates to other instances, and Kafka receives an after-commit accepted-operation event.

## System Architecture

<svg viewBox="0 0 1120 580" xmlns="http://www.w3.org/2000/svg" role="img" aria-labelledby="layered-title">
  <title id="layered-title">Layered architecture diagram</title>

  <rect x="70" y="40" width="980" height="78" rx="22" fill="#4299e1"/>
  <text x="550" y="73" text-anchor="middle" fill="white" font-size="22" font-weight="700">Client Layer</text>
  <text x="550" y="96" text-anchor="middle" fill="white" font-size="14">React + Vite frontend, REST API calls, SockJS/STOMP collaboration transport</text>

  <rect x="70" y="142" width="980" height="90" rx="22" fill="#ed8936"/>
  <text x="550" y="168" text-anchor="middle" fill="white" font-size="22" font-weight="700">API and Messaging Layer</text>
  <text x="550" y="192" text-anchor="middle" fill="white" font-size="14">DocumentController, CollaboratorController, AuthController, CollaborationController, WebSocketConfig</text>

  <rect x="70" y="256" width="980" height="122" rx="22" fill="#9f7aea"/>
  <text x="560" y="292" text-anchor="middle" fill="white" font-size="22" font-weight="700">Application Service Layer</text>
  <text x="560" y="318" text-anchor="middle" fill="white" font-size="14">DocumentService, DocumentOperationService, CollaborationSessionService, PresenceService, AuthorizationService</text>
  <text x="560" y="343" text-anchor="middle" fill="white" font-size="14">OperationTransformer, CurrentUserProvider, SimpMessagingTemplate fanout</text>

  <rect x="70" y="410" width="450" height="122" rx="22" fill="#48bb78"/>
  <text x="295" y="446" text-anchor="middle" fill="white" font-size="22" font-weight="700">Data Layer</text>
  <text x="295" y="472" text-anchor="middle" fill="white" font-size="14">PostgreSQL + Flyway + JPA</text>
  <text x="295" y="497" text-anchor="middle" fill="white" font-size="14">Document aggregate, collaborators,</text>
  <text x="295" y="518" text-anchor="middle" fill="white" font-size="14">immutable operation history</text>

  <rect x="600" y="410" width="450" height="138" rx="22" fill="#48bb78"/>
  <text x="825" y="444" text-anchor="middle" fill="white" font-size="20" font-weight="700">Platform and Coordination Layer</text>
  <text x="825" y="470" text-anchor="middle" fill="white" font-size="13">Redis Pub/Sub for low-latency fanout</text>
  <text x="825" y="491" text-anchor="middle" fill="white" font-size="13">Kafka for durable stream and replay</text>
  <text x="825" y="512" text-anchor="middle" fill="white" font-size="13">Docker Compose local runtime</text>
  <text x="825" y="533" text-anchor="middle" fill="white" font-size="13">Actuator health and Prometheus exposure</text>

  <path d="M560,118 L560,142" stroke="#666666" stroke-width="4"/>
  <path d="M560,232 L560,256" stroke="#666666" stroke-width="4"/>
  <path d="M560,378 L560,410" stroke="#666666" stroke-width="4"/>
</svg>

### Layer Responsibilities

- **Client and editor**: React, Vite, Tiptap, SockJS, and STOMP combine metadata management with live editing.
- **Backend responsibility**: Spring Boot owns request handling, authorization, operation ordering, document projection updates, and downstream publication.
- **State boundaries**: PostgreSQL is the source of truth, Redis carries transient coordination and fanout, and Kafka holds durable accepted-operation events.
- **Scalability posture**: Any backend instance can accept a client connection, while Redis and Kafka let collaboration and event processing extend beyond one node.

## Features

### Functional

- Document CRUD and paginated listing for accessible resources
- Ownership transfer and collaborator permission management
- Real-time join, leave, presence, and accepted-operation messaging
- Operation log plus materialized current document state
- Search and filtering support for document discovery

### Non-Functional

- Idempotency checks on `operationId` avoid duplicate accepted operations
- Pessimistic locking serializes version assignment on the document aggregate
- Kafka publication occurs after commit to avoid durable events for rolled-back writes
- Redis propagation filters same-instance echoes to prevent duplicate broadcasts
- Flyway-controlled schema evolution with `ddl-auto=validate`
- Actuator health and Prometheus endpoints for runtime visibility
- Automated test coverage across controllers, services, Redis, Kafka, and end-to-end collaboration paths

## Multi-Instance Collaboration Flow

1. Client connects to any backend instance and joins a document channel.
2. Client submits an operation with `operationId`, `baseVersion`, and typed payload.
3. The instance validates ACL and operation shape.
4. The backend acquires a pessimistic lock on the document row.
5. It checks idempotency and transforms against intervening operations if needed.
6. The accepted operation and updated document projection are persisted transactionally.
7. The accepted operation is published to Redis and fanned out to clients connected to other backend instances.
8. The accepted operation is published to Kafka for replay, audit, analytics, and async consumers.

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

All requests require an `X-User-Id` header containing a valid user UUID in the current MVP flow.

### STOMP Destinations

**Send (client to server)**

| Destination | Description |
|---|---|
| `/app/documents/{documentId}/sessions.join` | Join a document collaboration session |
| `/app/documents/{documentId}/sessions.leave` | Leave a document collaboration session |
| `/app/documents/{documentId}/presence.update` | Broadcast cursor or presence update |
| `/app/documents/{documentId}/operations.submit` | Submit an edit operation |

**Subscribe (server to client)**

| Topic | Description |
|---|---|
| `/topic/documents/{documentId}/sessions` | Session snapshot on join or leave |
| `/topic/documents/{documentId}/presence` | Presence events and cursor updates |
| `/topic/documents/{documentId}/operations` | Accepted operation broadcasts |

## Deployment

### Local Development

1. **Prerequisites:** Java 17 and Docker
2. Start infrastructure:
   ```bash
   docker compose up -d
   ```
3. Start the application:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
4. Verify:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

### Runtime Footprint

- `compose.yaml` runs `postgres`, `redis`, `kafka`, `backend`, and `frontend`
- The backend is packaged with a two-stage Dockerfile using Maven and Eclipse Temurin JRE 17
- Environment variables inject PostgreSQL, Redis, Kafka, and JWT settings
- Flyway runs at startup and validates the schema

### Scale-Out Direction

- Multiple backend instances can serve clients because fanout is not tied to one node
- Redis keeps collaboration fast
- Kafka keeps downstream processing durable
- The architecture is ready for stricter JWT-first authentication and more advanced async consumers

Current identity handling mixes an `X-User-Id` oriented development flow with JWT-capable security components. In the architecture story, this is best described as a pragmatic MVP authentication layer with a clearer JWT-first production path.

## Running Tests

```bash
cd backend
./mvnw test
```

Note: `RedisAcceptedOperationFanoutTest` is skipped without Docker via `@Testcontainers(disabledWithoutDocker = true)`.

## Design Decisions

- **Server-authoritative OT over CRDT**: deterministic server ordering keeps the conflict path simple and auditable; CRDT generality is not needed for the MVP operation set.
- **Pessimistic lock for version slot assignment**: `SELECT FOR UPDATE` on the document row serializes concurrent submits without optimistic retry loops.
- **Redis vs Kafka responsibility split**: Redis is for speed and hot-path fanout; Kafka is for durability and downstream consumers.
- **H2 in tests, PostgreSQL in production**: keeps the test suite fast and self-contained while Flyway validates schema shape.
- **Pragmatic MVP identity model**: `X-User-Id` remains convenient for local and portfolio use, with JWT already present as the clearer production direction.
