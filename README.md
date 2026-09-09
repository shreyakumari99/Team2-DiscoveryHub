# DiscoveryHub

E-Discovery & Legal Hold platform — an intern project. A microservices-based
system that ingests business communications (email + chat), makes them
searchable, lets investigators build cases, place legal holds, export
verifiable evidence packages, and keep an append-only audit trail of who did
what.

> Built with Java 21 / Spring Boot, Kafka, PostgreSQL, MongoDB, Elasticsearch,
> Amazon S3 and an Angular frontend. The whole stack starts with a single
> command.

An investigator can:

1. **Ingest** simulated email + chat communications (10,000+ message corpus)
2. **Search** the full corpus with filters and highlighted hits
3. **Build cases** with custodians and evidence items
4. **Place legal holds** to freeze relevant messages — deletion is blocked, and
   overlapping holds are handled correctly
5. **Export** evidence packages, with attachments, verifiable by SHA-256
6. **Review** an append-only audit trail of every action

---

## Contents

- [Architecture at a glance](#architecture-at-a-glance)
- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start--full-stack-with-one-command)
- [Running services from your IDE](#running-services-from-your-ide-infra-only)
- [Build order](#build-order-maven)
- [Tests and coverage](#tests--one-command-with-coverage)
- [Repository layout](#repository-layout)
- [Demo script](#demo-script-matches-the-deliverable)

Deeper reading: [docs/architecture.md](docs/architecture.md) for design
decisions and the "why X not Y" rationale, [docs/api-contracts.md](docs/api-contracts.md)
for every REST endpoint and Kafka event, and [AGENTS.md](AGENTS.md) for build
notes and the gotchas that cost us time.

---

## Architecture at a glance

Seven independently deployable backend services, a data generator, and an
Angular frontend. **Every service owns its own data — no shared database
schemas.** Services talk via REST (synchronous, where needed) and Kafka
(asynchronous).

| Service | Owns | Responsibility | FRs |
| --- | --- | --- | --- |
| **ingestion-service** | nothing (stateless) | Accepts email/chat messages via REST, validates, publishes to Kafka. Deliberately dumb — the component that *accepts* is not the component that *stores*. | FR-1.4 |
| **archive-service** | MongoDB (bodies) + S3 (attachments) | Consumes from Kafka, assigns the immutable ID, dedups by `sourceMessageId` (idempotency), stores durably. **Owns legal-hold state** and is **the only service allowed to delete** — held-item deletion is blocked here. | FR-1.5, FR-1.6, FR-4.5, FR-4.6 |
| **search-service** | Elasticsearch index | Consumes archived-message events, indexes them, exposes full-text search with filters, highlighting, pagination, saved searches. | FR-3 |
| **case-service** | PostgreSQL `case_db` | Case CRUD, lifecycle state machine (Draft→Active→Under Review→Closed), custodians, evidence items. | FR-2 |
| **hold-retention-service** | PostgreSQL `hold_db` | Resolves hold scope (calls Search), publishes hold events async, runs the scheduled disposition job that asks Archive to delete expired messages (Archive refuses if held). | FR-4, FR-5 |
| **export-service** | PostgreSQL `export_db` + S3 | Async export jobs: pulls a case's evidence (or a hold's full scope), fetches messages **and attachments** from Archive, builds zip + manifest + per-item and package checksums, generates an expiring download link, and re-verifies packages on demand. | FR-6 |
| **audit-service** | PostgreSQL `audit_db` (append-only) | Consumes a single shared `audit-events` Kafka topic that every other service publishes to. Nobody writes into Audit's DB directly. | FR-7 |
| **service-commons** | nothing (library) | Shared audit publisher, actor propagation, resilient HTTP clients, S3 abstraction — one implementation of each cross-cutting concern. | cross-cutting |
| **data-generator** | nothing | One-off Java job that builds the 10,000+ fake corpus by calling Ingestion's REST API like a real client. | FR-1.2 |
| **frontend** | nothing | Angular SPA: dashboard, case detail, search, holds, export jobs, audit trail. | FR-8 |

### Async messaging (Kafka topics)

| Topic | Producer(s) | Consumer(s) |
| --- | --- | --- |
| `message-ingested` | ingestion-service | archive-service |
| `message-archived` | archive-service | search-service |
| `case-events` | case-service | hold-retention-service |
| `hold-events` | hold-retention-service | archive-service |
| `message-hold-state` | archive-service | search-service |
| `export-events` | export-service | (UI via polling) |
| `audit-events` | **every** service | audit-service |

`message-hold-state` carries the *resulting* per-message hold state after
archive has applied a hold. search-service mirrors that rather than re-deriving
`held` from a raw hold event — archive owns the overlapping-hold rule, and two
places deciding it independently would let the index contradict the archive.

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
| Object storage | Amazon S3 via AWS SDK v2 (attachments + export packages) |
| Resilience | resilience4j (circuit breakers + retry) |
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

Object storage is Amazon S3, so supply credentials first:

```bash
cp .env.example .env    # fill in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION
```

`.env` is gitignored and is never baked into an image. Then:

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
| Elasticsearch      | http://localhost:9200 |
| Kafka              | localhost:9092 |

Generate the corpus once the stack is up, by running the data-generator against
the running ingestion-service:

```bash
cd data-generator
mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.url=http://localhost:8081 --count=10000"
```

---

## Running services from your IDE (infra only)

Start just the backing infrastructure, then run each Spring Boot app from your
IDE for fast iteration:

```bash
docker compose up -d postgres mongo elasticsearch kafka init-setup
```

Each service's `application.yml` defaults to `localhost` for all dependencies,
so they work out of the box against the dockerized infra.

---

## Build order (Maven)

Each service is a **self-contained Maven project** (no parent POM), but they all
depend on two library modules: `shared-contracts` (Kafka event DTOs) and
`service-commons` (audit trail, resilient HTTP, S3). Install both first, in
this order:

```bash
cd services/shared-contracts && mvn clean install
cd ../service-commons        && mvn clean install

# then any service, e.g.:
cd ../case-service && mvn clean verify
```

---

## Tests — one command, with coverage

```bash
./scripts/test-all.sh                  # backend + frontend, with coverage
./scripts/test-all.sh --backend-only
./scripts/test-all.sh --skip-coverage  # faster
```

No infrastructure required: backend tests use H2, mocked clients and an
in-memory object store; frontend tests use jsdom. Reports land in
`services/<module>/target/site/jacoco/index.html` (JaCoCo) and
`frontend/coverage/index.html` (Vitest + v8).

Around **102 backend tests** and **26 frontend tests**. The ones that carry the
weight:

| Test | Proves |
| --- | --- |
| `LegalHoldLedgerTest.releasingOneOfTwoOverlappingHoldsKeepsTheMessageProtected` | FR-4.5 — overlapping holds |
| `ArchiveControllerTest.deletingHeldMessageIsBlocked` | FR-4.6 — held delete returns 409 |
| `DispositionLogicTest.theRunRecordsWhichMessagesWereDeletedAndWherePreserved` | FR-5.3 — per-message disposition record |
| `ExportPackageBuilderTest` | FR-6.3 attachments, FR-6.5 reproducibility + tamper detection |
| `ExportJobLifecycleTest` | FR-6.1 hold scope, FR-6.6 retry rules |
| `SearchQueryBuilderTest.multipleTypesAreOredNotAnded` | FR-3.2 — filter composition |
| `AuditEventConsumerTest` | FR-7 — append-only persistence and filtering |
| `CaseStateMachineTest`, `CaseServiceLifecycleTest` | FR-2.2 lifecycle, FR-2.5 read-only |

---

## Repository layout

```
.
├── docker-compose.yml          # full stack, single-command startup
├── .env.example                # copy to .env and add your AWS credentials
├── scripts/test-all.sh         # one-command test suite + coverage
├── .github/workflows/ci.yml    # build + test all services + frontend
├── data-generator/             # builds the 10k+ fake corpus via Ingestion API
├── services/
│   ├── shared-contracts/       # Kafka event DTOs shared by all services
│   ├── service-commons/        # audit trail, actor context, resilient HTTP, S3
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
│   
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
6. Place a hold scoped by custodian/date/terms → returns instantly, scope resolves async (hold-retention)
7. **Prove deletion is blocked**: trigger disposition → held messages are skipped, and the run lists exactly which ones (archive refuses with 409)
8. **Prove overlapping holds are correct**: place a second hold covering some of the same messages, release the first → those messages stay protected
9. Request an export → watch the status update live → download the zip → press **Verify** to re-check every checksum against the manifest
10. Open audit trail → every action above is recorded against the named investigator, filterable by case, action, actor and date
11. **Resiliency demo**: stop archive-service → ingestion keeps accepting and the dashboard degrades one tile at a time → restart → the Kafka backlog drains and the messages appear in search
