# collaborative-doc-engine


A high-performance, distributed backend system for real-time collaborative document editing.

Built to demonstrate:

- Distributed systems fundamentals  
- Concurrency control (OT / CRDT concepts)  
- Event-driven architecture  
- Horizontal scalability  
- Cloud-native design patterns  

---

## 🚀 Overview

This project implements a scalable backend that enables multiple users to edit the same document concurrently with low latency and strong consistency guarantees.

It is designed to simulate production-level collaborative systems (e.g., Google Docs-style editing) using:

- **Spring Boot** – REST API & application framework  
- **PostgreSQL** – Durable document storage  
- **Redis (Pub/Sub + Cache)** – Real-time state propagation  
- **Kafka** – Event streaming & asynchronous processing  
- **Flyway** – Schema versioning & migrations  

---

## 🏗 Architecture
            Clients (Web / API)
                    │
                    ▼
        Spring Boot Application
                    │
    ┌───────────────┼────────────────┐
    ▼               ▼                ▼
    P
    
PostgreSQL Redis Kafka
(Persistent) (Real-time) (Event Log)
---

## 🧠 Edit Flow (End-to-End)

1. Client sends an edit operation to backend.
2. Backend:
   - Validates document version
   - Applies OT/CRDT transformation logic
   - Persists updated state in PostgreSQL
   - Updates Redis cache
   - Publishes update to Redis Pub/Sub channel
   - Emits event to Kafka for async consumers
3. Other connected clients receive the update in real-time.

---

## 🔄 Concurrency Model

### Why OT + CRDT Concepts?

The system explores hybrid concurrency strategies:

- **Operational Transformation (OT)**  
  Used to transform concurrent operations against a shared base version.

- **CRDT Concepts**  
  Used to guarantee convergence across distributed instances.

### Multi-Instance Behavior

Each backend instance:

- Subscribes to Redis channels for document updates  
- Publishes edits to the same channel  
- Acts as both publisher and subscriber  

This allows:

- Horizontal scaling  
- Real-time synchronization across instances  
- Low-latency fan-out updates  
- Eventual consistency across nodes  

---

## ⚡ Performance Strategy

To achieve real-time performance:

- Redis stores active document state
- PostgreSQL is optimized with indexing
- Kafka decouples heavy processing
- Asynchronous handling reduces request blocking
- Stateless service design enables horizontal scaling

Primary latency sources:

- Database write time  
- Network round-trip  
- Version conflict resolution  
- Inter-instance propagation delay  

---

## 🗄 Database Migrations (Flyway)

Flyway is used alongside Spring Data JPA to:

- Version-control schema changes  
- Ensure deterministic migrations  
- Support CI/CD pipelines  
- Prevent schema drift in distributed deployments  

Migration scripts are stored under:
src/main/resources/db/migration
---

## 📡 Redis Pub/Sub Behavior

For a document `doc-123`:

- Channel name: `document:doc-123`
- Instance A publishes edit
- Instance B receives event
- Instance B updates local state
- All connected clients get the change

All instances:

- Can publish  
- Can subscribe  
- Remain eventually consistent  

Redis handles real-time propagation.  
Kafka ensures durability and replayability.

---

## 📬 Kafka Usage

Kafka is used for:

- Event sourcing  
- Audit logs  
- Analytics pipelines  
- Async processing (e.g., history snapshots)  

**Redis = low latency**  
**Kafka = durability & scalability**

---
