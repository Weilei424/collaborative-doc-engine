# Developer Guide

## Prerequisites

| Tool | Version |
|---|---|
| Java | 17 |
| Maven | via `./mvnw.cmd` (wrapper included) |
| Node.js | 20+ |
| npm | bundled with Node |
| Docker Desktop | any recent version |

---

## Running locally

### All services via Docker Compose

```bash
docker compose up
```

Starts five containers: `postgres`, `redis`, `kafka`, `backend`, `frontend`.

On first run Docker pulls images (~600 MB). Subsequent starts are fast.

### Backend only (dev mode with hot reload)

Requires `postgres`, `redis`, and `kafka` already running (e.g. via `docker compose up postgres redis kafka`).

```bash
cd backend
./mvnw.cmd spring-boot:run
```

Backend listens on `http://localhost:8080`.

### Frontend dev server

```bash
cd frontend
npm install   # first time only
npm run dev
```

Vite dev server listens on `http://localhost:5173` with hot module replacement.
The dev server proxies `/api` and `/ws` to `http://localhost:8080` — configure in `frontend/vite.config.ts`.

---

## Demo data (dev profile)

When the stack is started with `SPRING_PROFILES_ACTIVE=dev` (the default in `compose.yaml`), `DemoDataSeeder` runs once on first boot and seeds the database. On subsequent restarts it detects existing data and skips.

### Accounts

All passwords are `demo1234`.

| Username | Email | Role flavour |
|---|---|---|
| `alice` | alice@demo.test | Product Manager — primary owner |
| `bob` | bob@demo.test | Engineering Lead |
| `carol` | carol@demo.test | Designer |
| `dave` | dave@demo.test | Data Analyst |
| `eve` | eve@demo.test | Stakeholder — mostly read-only |

### Documents and ownership

| Title | Owner | Visibility |
|---|---|---|
| Q3 Product Roadmap | alice | Shared |
| API Design Guidelines | bob | Shared |
| Design System v2 | carol | Shared |
| User Research Report | alice | Shared |
| Sprint 14 Planning | bob | Shared |
| Onboarding Checklist | alice | Public |
| Company Wiki Home | alice | Public |
| Database Migration Notes | bob | Private |
| Personal Task List | carol | Private |
| Marketing Copy Drafts | alice | Shared |
| Q3 KPI Dashboard Notes | dave | Shared |
| Release Notes v2.1 | bob | Shared |

### Resetting demo data

Stop the stack, drop the Postgres volume, then restart:

```bash
docker compose down -v
docker compose up
```

This wipes all data (including any documents you created manually) and re-seeds on next boot.

---

## Running tests

### Backend

```bash
cd backend
./mvnw.cmd test
```

- Unit and `@WebMvcTest` slice tests run without Docker
- `@SpringBootTest` and `@DataJpaTest` integration tests use [Testcontainers](https://testcontainers.com/) and require Docker
- If Docker is unavailable, Testcontainers tests skip gracefully (`@Testcontainers(disabledWithoutDocker = true)`)

To run a single test class:

```bash
./mvnw.cmd -Dtest=DocumentOperationConcurrencyTest test
```

### Frontend

```bash
cd frontend
npm run build   # TypeScript compile + Vite production build
```

There are no frontend unit tests at this time.

---

## Project layout

```
collaborative-doc-engine/
├── backend/
│   ├── src/main/java/com/mwang/backend/
│   │   ├── config/          Spring Security, JWT, WebSocket/STOMP, MDC
│   │   ├── domain/          JPA entities (Document, User, DocumentOperation, …)
│   │   ├── repositories/    Spring Data JPA repositories
│   │   ├── service/         Business logic (document ops, auth, collaboration session)
│   │   ├── web/controller/  REST controllers + WebSocket message handlers
│   │   ├── kafka/           Kafka producer and consumer for durable operation events
│   │   └── collaboration/   Redis fanout publisher/subscriber for real-time presence
│   ├── src/main/resources/
│   │   ├── db/migration/    Flyway SQL migrations (V1 – V5)
│   │   └── application.properties
│   └── src/test/java/…/
│       ├── testcontainers/  AbstractIntegrationTest, AbstractRepositoryTest
│       └── …                Unit, slice, and integration tests
├── frontend/
│   ├── src/
│   │   ├── api/             Axios wrappers for REST endpoints
│   │   ├── contexts/        AuthContext, ToastContext
│   │   ├── components/      DocumentCard, ErrorBoundary, PresenceBar, SessionPanel, …
│   │   ├── hooks/           useCollaboration (STOMP), useTiptapCollaboration
│   │   └── pages/           LoginPage, RegisterPage, DashboardPage, EditorPage, DocumentSettingsPage
│   ├── Dockerfile           Two-stage build: node:20-alpine → nginx:alpine
│   └── nginx.conf           API proxy, WebSocket proxy, SPA fallback
├── compose.yaml             Five-service dev stack
├── Dockerfile               Backend multi-stage build (Maven → JRE)
└── docs/                    Guides, architecture diagram, design specs, plans
```

---

## Key environment variables

These are set automatically by `compose.yaml`. Override them in `.env` for local dev.

| Variable | Compose default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/collabdb` | Postgres JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `collabuser` | Postgres user |
| `SPRING_DATASOURCE_PASSWORD` | `collabpass` | Postgres password |
| `SPRING_DATA_REDIS_HOST` | `redis` | Redis hostname |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka bootstrap address |
| `JWT_SECRET` | `PZQhbm2pxG2uTlbcHMxwTB4amG59/+uK+nYbCV/4u4o=` | JWT signing key (Base64) — **change in production** |

---

## Database migrations

Schema is owned by Flyway. Migration files live in:

```
backend/src/main/resources/db/migration/
  V1__init.sql
  V2__add_document_operation_history.sql
  V3__...
  ...
```

Rules:
- Never edit an existing migration file
- Always add a new `V{n}__description.sql` for schema changes
- JPA is set to `ddl-auto=validate` — it will refuse to start if the schema doesn't match the entities

---

## Architecture overview

See `docs/architecture.svg` for a visual overview.

**Collaboration pipeline (hot path):**
1. Client sends STOMP message to `/app/documents/{id}/operations.submit`
2. `CollaborationController` calls `DocumentOperationService.submitOperation`
3. Service acquires a pessimistic lock on the document row, assigns `serverVersion`, persists the operation
4. Service publishes an `AcceptedOperationEvent` to Redis
5. `RedisAcceptedOperationFanout` receives the event and broadcasts it to all subscribers on `/topic/documents/{id}/operations`
6. An async Kafka consumer (`KafkaAcceptedOperationIntegrationTest`) writes the event to durable storage for replay and analytics

**Auth:**
- JWT issued on login/register, passed as `Authorization: Bearer <token>` on REST, and as `?token=<jwt>` on the WebSocket handshake
- `JwtHandshakeInterceptor` validates the token and sets the principal before the STOMP connection is established

---

## Test base classes

| Class | Use when |
|---|---|
| `AbstractIntegrationTest` | `@SpringBootTest` — full context, needs Postgres + Redis + Kafka |
| `AbstractRepositoryTest` | `@DataJpaTest` — JPA slice, needs Postgres only |

Both use `@Testcontainers(disabledWithoutDocker = true)` and `@DynamicPropertySource` to inject container ports into the Spring context.
