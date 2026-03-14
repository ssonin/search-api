# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build fat JAR
./gradlew clean shadowJar

# Run all tests
./gradlew clean test

# Run a single test class
./gradlew test --tests "ssonin.searchapi.repository.RepositoryVerticleTest"

# Run locally (requires Docker dependencies running first)
docker compose up --build postgres kafka embedding
./gradlew clean run

# Run everything via Docker
docker compose up --build

# Clean rebuild and restart (stops containers, removes volumes/data, rebuilds images, starts fresh)
docker compose down -v && docker compose up --build
```

The app listens on port **8888**. The embedding service runs on port **8000**.

## Architecture

**search-api** is a REST API for managing clients and documents with hybrid search (full-text + semantic vector search).

### Verticle-Based Design (Vert.x 5)

The app is composed of four Vert.x verticles that communicate exclusively via the **event bus**:

- **`App.java`** — Orchestrator: runs Flyway migrations, deploys all verticles
- **`ApiVerticle`** — HTTP layer: routes, validates JSON schemas, delegates to event bus; fetches query embeddings before dispatching search
- **`RepositoryVerticle`** — Data layer: PostgreSQL (reactive pool); handles all DB operations including embedding updates
- **`EmbeddingVerticle`** — HTTP client to Python embedding service; converts text → 384-dim vectors
- **`EmbeddingIngesterVerticle`** — Kafka consumer; reads computed embeddings from `searchapi.document.embedding` topic and forwards them to `RepositoryVerticle` via event bus

### Async Embedding Pipeline

Document embeddings are computed asynchronously after document creation via an event-driven pipeline:

```
POST /api/v1/clients/{id}/documents
  → RepositoryVerticle (transaction):
      INSERT document
      pg_logical_emit_message('document.created', doc_id)
  → WAL → Debezium → Kafka: searchapi.message
  → Python embedding service (Kafka consumer):
      Fetch document content from PostgreSQL
      Compute 384-dim embedding (all-MiniLM-L6-v2)
      Publish to Kafka: searchapi.document.embedding
  → EmbeddingIngesterVerticle (Kafka consumer):
      Forward to event bus: documents.embedding.update
  → RepositoryVerticle:
      UPDATE documents SET embedding = ?
```

Documents are immediately searchable via FTS after creation. Vector search results appear once the async pipeline completes (typically within seconds).

### Endpoints

| Method | Path | Handler |
|--------|------|---------|
| POST | `/api/v1/clients` | Create client |
| GET | `/api/v1/clients/{id}` | Get client |
| POST | `/api/v1/clients/{id}/documents` | Create document (transactional) |
| GET | `/api/v1/search?q=` | Hybrid search |

### Hybrid Search (Key Feature)

Search combines two strategies merged via **Reciprocal Rank Fusion (RRF)**:

1. **Full-Text Search** — PostgreSQL `tsvector` with `ts_rank`
2. **Vector Search** — pgvector cosine similarity on 384-dim embeddings (model: `all-MiniLM-L6-v2`)
3. **RRF merge** — `score = 1 / (60 + rank_position)`, summed across strategies

Query embeddings are fetched in `ApiVerticle` before dispatching to `RepositoryVerticle`, keeping the repository layer free of embedding concerns.

### Database

PostgreSQL 16 with the `pgvector` extension. Schema managed by Flyway (`src/main/resources/db/migration/`).

Key schema features:
- `tsvector` generated columns for FTS with GIN indexes
- HNSW index on document embeddings for fast ANN search
- Soft deletes via `state` column (ACTIVE/inactive)
- `UNIQUE (lower(email)) WHERE state = 'ACTIVE'` partial index on clients
- `wal_level=logical` configured for Debezium CDC
- `dbz_publication` pgoutput publication for Debezium replication slot

### Infrastructure (Docker Compose)

| Service | Image | Purpose |
|---------|-------|---------|
| `postgres` | `pgvector/pgvector:pg16` | Primary database |
| `kafka` | `apache/kafka:latest` | Message broker (KRaft mode, no Zookeeper) |
| `debezium` | `debezium/connect:2.7.3.Final` | WAL capture via Kafka Connect |
| `debezium-init` | `alpine/curl` | Registers Debezium connector on startup |
| `kafka-ui` | `provectuslabs/kafka-ui` | Kafka topic browser (port 8080) |
| `embedding` | custom | Python FastAPI embedding service |

Debezium connector config: `services/debezium/connector-config.json`

### Testing

- **`AppTest`** — Verticle orchestration and startup
- **`ApiVerticleTest`** — HTTP layer (uses WireMock for event bus stubs)
- **`RepositoryVerticleTest`** — Database layer (uses Testcontainers: `pgvector/pgvector:pg16`)
- **`EmbeddingVerticleTest`** — Embedding service integration (uses WireMock)
- **`EmbeddingIngesterVerticleTest`** — Kafka consumer integration (uses Testcontainers: `confluentinc/cp-kafka:7.9.0`)

Tests use JUnit 5 + VertxExtension + AssertJ.

## Package Structure

```
src/main/java/ssonin/searchapi/
├── App.java
├── api/
│   └── ApiVerticle.java
├── embedding/
│   ├── EmbeddingVerticle.java
│   └── EmbeddingIngesterVerticle.java
└── repository/
    ├── RepositoryVerticle.java
    ├── SqlQueries.java
    ├── NotFoundException.java
    └── ClientNotFoundException.java
```

SQL queries are centralized in `SqlQueries.java` as static methods returning parameterized query strings.
