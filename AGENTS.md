# AGENTS.md

## Purpose

This checkout migrates `search-api` from Vert.x to Quarkus. The implemented Quarkus slice currently creates and retrieves clients. The legacy application manages clients and documents and searches them through a hybrid of:

- PostgreSQL full-text search
- pgvector cosine similarity search
- Reciprocal Rank Fusion (RRF) for document ranking

The legacy system also includes an asynchronous embedding pipeline driven by PostgreSQL logical messages, Debezium, Kafka and a Python embedding service. The document and search endpoints and this pipeline are not yet implemented in the Quarkus application.

## Current Quarkus Guidance

This section governs the current checkout. The Vert.x stack, layout, architecture, operational flow, build commands and test inventory below are legacy reference, not instructions to recreate those boundaries in the Quarkus client slice.

- Use Java 25, Gradle 9.7.0 and Quarkus 3.38.1 with JVM fast-jar packaging.
- Keep HTTP handling and request validation in `external/ClientResource`, typed application operations in `service/ClientService`, and reactive database access in `repository/ClientRepository`.
- Keep transport DTOs in `external/request` and `external/response`; map application errors through `external/ApiExceptionMappers`.
- Configure datasources in `src/main/resources/application.properties`; Flyway runs migrations at startup.
- For local client development, run `docker compose up -d --wait postgres`, then `./gradlew quarkusDev`. The client slice does not require Kafka, Debezium or the embedding service. The legacy Dockerfile does not yet package Quarkus.
- Run plain JUnit unit tests in `src/test/java` with `./gradlew test`. Do not start Quarkus or Docker in that suite; mock injectable dependencies when testing application classes.
- Run black-box packaged application tests in `src/integrationTest/java` with `./gradlew quarkusIntTest` and `@QuarkusIntegrationTest`. These tests use PostgreSQL Testcontainers; `./gradlew check` includes both suites. Do not introduce `@QuarkusTest` as the default HTTP contract boundary.
- Client creation returns 201/400/409; retrieval returns 200/400/404. Preserve snake_case payloads, `application/json` success bodies, `application/problem+json` errors and the creation `Location` header.
- Preserve 400 for unparseable client identifiers and 404 for parseable identifiers with no matching client. Canonical UUID spelling is not required. Duplicate active emails are compared case-insensitively.
- Required names and email are non-blank; validate email, accept unknown request properties, reject explicit null string values and omit absent optional descriptions from responses.
- The authoritative OpenAPI 3.1 contract is generated from resource and DTO annotations and served at `/q/openapi` (`?format=json` for JSON). Swagger UI is available at `/q/swagger-ui` in development mode.
- Reuse the extension's Problem Details schemas. `ProblemSchemaFilter` adjusts their `type` and `instance` formats to `uri-reference` at build time. Do not require `type`, `detail`, `instance` or validation extensions on every error; omitted `type` implies `about:blank`.
- Do not reintroduce a separately maintained `openapi.yml` or overlapping static endpoint schemas. Generated exports belong under `build/` and are outputs, not contract inputs.
- For QKM-012, verify the generated contract manually through `/q/openapi` and Swagger UI, and run the existing unit and packaged endpoint behaviour tests. Automated tests of the generated OpenAPI document are excluded from this task; do not add annotation tests or generated-document snapshots.

## Legacy Stack

- Java 21
- Gradle 8
- Vert.x 5
- PostgreSQL 16 + pgvector
- Flyway migrations
- Kafka + Debezium
- Python 3.11 FastAPI embedding service with `sentence-transformers`
- JUnit 5, Testcontainers, WireMock, AssertJ

## Legacy Repo Layout

```text
src/main/java/ssonin/searchapi/
  App.java                         Bootstraps config, Flyway, verticle deployment
  api/ApiVerticle.java             HTTP API and request validation
  repository/RepositoryVerticle.java
                                   All database access and search orchestration
  repository/SqlQueries.java       Centralized SQL
  embedding/EmbeddingVerticle.java HTTP client for embedding service
  embedding/EmbeddingIngesterVerticle.java
                                   Kafka consumer for async embedding updates

src/main/resources/db/migration/   Flyway schema
services/embedding-service/        Python embedding worker + HTTP service
services/debezium/                 Debezium connector config
services/postgres/                 Postgres init SQL
```

## Legacy Architecture Reference

- Preserve the event-bus split. HTTP stays in `ApiVerticle`, database logic stays in `RepositoryVerticle`, embedding HTTP calls stay in `EmbeddingVerticle`, Kafka ingestion stays in `EmbeddingIngesterVerticle`.
- Keep SQL in [`SqlQueries.java`](/Users/sergei.sonin/github/search-api/src/main/java/ssonin/searchapi/repository/SqlQueries.java). Do not scatter SQL strings through handlers unless there is a strong reason.
- `App.java` owns environment-driven config assembly and Flyway migration execution before verticle deployment.
- Query embeddings for `/api/v1/search` are fetched in the API layer before dispatching the search request to the repository layer.
- Document creation is intentionally asynchronous with respect to vector search. FTS should work immediately after insert; vector results appear once the Kafka pipeline updates `documents.embedding`.

## Legacy Operational Flow

Document embedding pipeline:

1. `POST /api/v1/clients/{id}/documents`
2. `RepositoryVerticle` inserts the document and emits `pg_logical_emit_message`
3. Debezium forwards the logical message to Kafka topic `searchapi.message`
4. Python embedding service consumes the message, loads document content from Postgres, computes a 384-dim embedding, and publishes to `searchapi.document.embedding`
5. `EmbeddingIngesterVerticle` consumes that topic and sends `documents.embedding.update` on the event bus
6. `RepositoryVerticle` updates `documents.embedding`

## Legacy Build And Run

Common commands:

```bash
./gradlew clean shadowJar
./gradlew clean test
./gradlew test --tests "ssonin.searchapi.repository.RepositoryVerticleTest"
docker compose up --build
docker compose up --build postgres kafka embedding
./gradlew clean run
```

Default ports:

- app: `8888`
- embedding service: `8000`
- postgres: `5432`
- kafka: `9092`
- debezium: `8083`
- kafka-ui: `8080`

Important environment variables:

- `PGHOST`
- `PGPORT`
- `PGDATABASE`
- `PGUSER`
- `PGPASSWORD`
- `HTTP_PORT`
- `EMBEDDING_SERVICE_HOST`
- `EMBEDDING_SERVICE_PORT`
- `KAFKA_BOOTSTRAP_SERVERS`

## Legacy Testing Reference

- `EmbeddingVerticleTest` is isolated and does not require Docker.
- `AppTest`, `ApiVerticleTest`, `RepositoryVerticleTest`, and `EmbeddingIngesterVerticleTest` rely on Testcontainers and therefore require a working Docker environment.
- When changing HTTP contracts, update tests in [`ApiVerticleTest.java`](/Users/sergei.sonin/github/search-api/src/test/java/ssonin/searchapi/api/ApiVerticleTest.java).
- When changing SQL, ranking, schema, or repository event-bus contracts, update [`RepositoryVerticleTest.java`](/Users/sergei.sonin/github/search-api/src/test/java/ssonin/searchapi/repository/RepositoryVerticleTest.java) and relevant migrations.
- When changing the embedding HTTP contract or failure handling, update [`EmbeddingVerticleTest.java`](/Users/sergei.sonin/github/search-api/src/test/java/ssonin/searchapi/embedding/EmbeddingVerticleTest.java).
- When changing Kafka topics or payload shape, update [`EmbeddingIngesterVerticleTest.java`](/Users/sergei.sonin/github/search-api/src/test/java/ssonin/searchapi/embedding/EmbeddingIngesterVerticleTest.java) and the Python service together.

## Change Guidance

- Keep JSON field naming consistent with the public API: snake_case in payloads and responses.
- If you change embedding dimensionality or model, update all of these together:
  - Python model output
  - `vector(384)` schema in migrations
  - any tests generating fake embeddings
  - search SQL casts and assumptions
- If you change Kafka topic names or Debezium message shape, update:
  - `services/debezium/connector-config.json`
  - `services/embedding-service/main.py`
  - `EmbeddingIngesterVerticle`
  - corresponding tests
- If you add Quarkus API endpoints, keep request validation in the resource layer, maintain OpenAPI annotations on resources and DTOs, and document verified behaviour in `README.adoc`.
- If you change schema, prefer a new Flyway migration instead of editing existing migrations.

## Legacy Caveats

- The `state` columns exist on `clients` and `documents`, but repository queries do not currently filter by `state`. Do not describe soft delete behavior as implemented unless you add the query constraints too.
- There is no synonym or thesaurus layer in the SQL or application code. Do not assume semantic matching comes from PostgreSQL thesaurus dictionaries; current semantic behavior comes from vector search only.
- `documents.client_id` is indexed but not protected by a database foreign key. Application code checks client existence before insert, but the database itself does not enforce the relationship.
- `/api/v1/search` currently depends on the embedding service being available for every query.

## When Updating Docs

- Keep [`README.adoc`](/Users/sergei.sonin/github/search-api-quarkus/README.adoc) and this file aligned with the implemented Quarkus slice and its generated contract. `CLAUDE.md` still describes the legacy baseline pending the broader documentation migration.
- Prefer documenting verified behaviour over intended behaviour, and label unmigrated document, search and pipeline descriptions as legacy reference.
