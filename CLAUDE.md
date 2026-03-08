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
docker compose up --build postgres embedding
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

The app is composed of three Vert.x verticles that communicate exclusively via the **event bus**:

- **`App.java`** — Orchestrator: runs Flyway migrations, deploys all verticles
- **`ApiVerticle`** — HTTP layer: routes, validates JSON schemas, delegates to event bus
- **`RepositoryVerticle`** — Data layer: PostgreSQL (reactive pool), orchestrates embedding fetch
- **`EmbeddingVerticle`** — HTTP client to Python embedding service; converts text → 384-dim vectors

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

### Database

PostgreSQL 16 with the `pgvector` extension. Schema managed by Flyway (`src/main/resources/db/migration/`).

Key schema features:
- `tsvector` generated columns for FTS with GIN indexes
- HNSW index on document embeddings for fast ANN search
- Soft deletes via `state` column (ACTIVE/inactive)
- `UNIQUE (lower(email)) WHERE state = 'ACTIVE'` partial index on clients

### Testing

- **`AppTest`** — Verticle orchestration and startup
- **`ApiVerticleTest`** — HTTP layer (uses WireMock for event bus stubs)
- **`RepositoryVerticleTest`** — Database layer (uses Testcontainers: `pgvector/pgvector:pg16`)
- **`EmbeddingVerticleTest`** — Embedding service integration (uses WireMock)

Tests use JUnit 5 + VertxExtension + AssertJ.

## Package Structure

```
src/main/java/ssonin/searchapi/
├── App.java
├── api/
│   └── ApiVerticle.java
└── repository/
    ├── EmbeddingVerticle.java
    ├── RepositoryVerticle.java
    ├── SqlQueries.java
    ├── NotFoundException.java
    └── ClientNotFoundException.java
```

SQL queries are centralized in `SqlQueries.java` as static methods returning parameterized `Tuple` + query string pairs.
