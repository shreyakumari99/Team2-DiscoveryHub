# DiscoveryHub

E-Discovery & Legal Hold platform — an intern project. A microservices-based
system that ingests business communications (email + chat), makes them
searchable, lets investigators build cases, place legal holds, export
verifiable evidence packages, and keep an append-only audit trail of who did
what.

> Built with Java 21 / Spring Boot, Kafka, PostgreSQL, MongoDB, Elasticsearch,
> MinIO (S3-compatible object storage) and an Angular frontend. The whole
> stack starts with a single command.

---

## Architecture at a glance

Seven independently deployable backend services, a data generator, and an
Angular frontend. **Every service owns its own data — no shared database
schemas.** Services talk via REST (synchronous, where needed) and Kafka
(asynchronous).

| Service | Owns | Responsibility | FRs |
| --- | --- | --- | --- |
| **ingestion-service** | nothing (stateless) | Accepts email/chat messages via REST, validates, publishes to Kafka. Deliberately dumb — the component that *accepts* is not the component that *stores*. | FR-1.4 |
| **archive-service** | MongoDB (bodies) + MinIO (attachments) | Consumes from Kafka, assigns the immutable ID, dedups by `sourceMessageId` (idempotency), stores durably. **The only service allowed to delete** — held-item deletion is blocked here. | FR-1.5, FR-1.6, FR-4.6 |
| **search-service** | Elasticsearch index | Consumes archived-message events, indexes them, exposes full-text search with filters, highlighting, pagination, saved searches. | FR-3 |
| **case-service** | PostgreSQL `case_db` | Case CRUD, lifecycle state machine (Draft→Active→Under Review→Closed), custodians, evidence items. | FR-2 |
| **hold-retention-service** | PostgreSQL `hold_db` | Resolves hold scope (calls Search), publishes hold events async, runs the scheduled disposition job that asks Archive to delete expired messages (Archive refuses if held). | FR-4, FR-5 |
| **export-service** | PostgreSQL `export_db` + MinIO | Async export jobs: pulls evidence from Case + content from Archive, builds zip + manifest + per-item/package checksums, generates expiring download link. | FR-6 |
| **audit-service** | PostgreSQL `audit_db` (append-only) | Consumes a single shared `audit-events` Kafka topic that every other service publishes to. Nobody writes into Audit's DB directly. | FR-7 |
| **data-generator** | nothing | One-off Java job that builds the 10,000+ fake corpus by calling Ingestion's REST API like a real client. | FR-1.2 |
| **frontend** | nothing | Angular SPA: dashboard, case detail, search, holds, export jobs, audit trail. | FR-8 |

### Async messaging (Kafka topics)

| Topic | Producer(s) | Consumer(s) |
| --- | --- | --- |
| `message-ingested` | ingestion-service | archive-service |
| `message-archived` | archive-service | search-service, (hold-retention) |
| `case-events` | case-service | hold-retention-service, export-service |
| `hold-events` | hold-retention-service | archive-service |
| `export-events` | export-service | (case-service) |
| `audit-events` | **every** service | audit-service |

See [docs/architecture.md](docs/architecture.md) and
[docs/api-contracts.md](docs/api-contracts.md) for the full design and
REST/Kafka contracts.

---

## Tech stack

| Concern | Choice |
| --- | --- |
| Language / build | Java 21 (LTS), Maven |
| Framework | Spring Boot 3.3.x |
| Messaging | Apache Kafka (KRaft mode, no Zookeeper) |
| Relational store | PostgreSQL 16 (one DB per service) |
| Document store | MongoDB 7 (Archive message bodies) |
| Search | Elasticsearch 8.13 |
| Object storage | MinIO (S3-compatible — attachments + export packages) |
| Frontend | Angular + TypeScript |
| Packaging/deploy | Docker, docker-compose; Kubernetes manifests (stretch) |

---

## Prerequisites

- JDK 21+ (LTS) and Maven 3.9+
- Node.js 20+ LTS and npm
- Docker Desktop (with the Docker Compose plugin)
- (Optional) a local Kubernetes: Docker Desktop, minikube, or kind

---

## Quick start — full stack with one command

```bash
# from the repo root
docker compose up -d --build
```

Then open the frontend at http://localhost:8080.

Service endpoints:

| Service | URL |
| --- | --- |
| ingestion-service  | http://localhost:8081 |
| archive-service    | http://localhost:8082 |
| search-service     | http://localhost:8083 |
| case-service       | http://localhost:8084 |
| hold-retention-service | http://localhost:8085 |
| export-service     | http://localhost:8086 |
| audit-service      | http://localhost:8087 |
| MinIO console      | http://localhost:9001 (minioadmin / minioadmin) |
| Elasticsearch      | http://localhost:9200 |
| Kafka              | localhost:9092 |

Generate the corpus once the stack is up:

```bash
docker compose run --rm ingestion-service   # not this
# run the data-generator against the running ingestion-service:
cd data-generator && mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.url=http://localhost:8081 --count=10000"
```

---

## Running services from your IDE (infra only)

Start just the backing infrastructure, then run each Spring Boot app from your
IDE for fast iteration:

```bash
docker compose up -d postgres mongo elasticsearch kafka minio init-setup
```

Each service's `application.yml` defaults to `localhost` for all dependencies,
so they work out of the box against the dockerized infra.

---

## Build order (Maven)

Each service is a **self-contained Maven project** (no parent POM), but they
all depend on the `shared-contracts` artifact (Kafka event DTOs). Build it
**first** and install it to your local Maven repo:

```bash
cd services/shared-contracts
mvn clean install

# then any service, e.g.:
cd ../../services/case-service
mvn clean verify
```

Build everything (from repo root):

```bash
( cd services/shared-contracts && mvn -q install ) && \
for s in ingestion archive search case hold-retention export audit; do \
  ( cd services/$s-service && mvn -q verify ) || exit 1; \
done && \
( cd data-generator && mvn -q verify )
```

---

## Repository layout

```
.
├── docker-compose.yml          # full stack, single-command startup
├── .github/workflows/ci.yml    # build + test all services + frontend
├── data-generator/             # builds the 10k+ fake corpus via Ingestion API
├── services/
│   ├── shared-contracts/       # Kafka event DTOs shared by all services
│   ├── ingestion-service/
│   ├── archive-service/
│   ├── search-service/
│   ├── case-service/
│   ├── hold-retention-service/
│   ├── export-service/
│   └── audit-service/
├── frontend/                   # Angular SPA
├── infra/
│   ├── docker/                 # postgres init + kafka topic bootstrap
│   └── k8s/                    # Kubernetes manifests (stretch goal)
└── docs/
    ├── architecture.md
    └── api-contracts.md
```

---

## Demo script (matches the deliverable)

1. `docker compose up -d --build` → start everything
2. Run data-generator → 10,000+ messages ingested
3. Open frontend → dashboard shows total messages, active cases, holds, exports
4. Search with filters + highlighting (search-service)
5. Create a case, add custodians, add evidence (case-service)
6. Place a hold scoped by custodian/date/terms → propagates async (hold-retention)
7. **Prove deletion is blocked**: trigger disposition → held messages are skipped (archive-service refuses)
8. Request an export → watch job progress → download zip → verify checksums (export-service)
9. Open audit trail → every action above is recorded, append-only (audit-service)
10. **Resiliency demo**: stop a service → others keep working → restart → backlog drains (NFR-2)
