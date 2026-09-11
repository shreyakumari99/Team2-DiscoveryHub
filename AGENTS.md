# AGENTS.md — DiscoveryHub

Quick reference for working in this repo. Read this before running builds.

## What this is

A microservices e-discovery & legal-hold platform (intern project). 7 Java
Spring Boot backend services + a data-generator + an Angular frontend, all
containerized with a single `docker compose up`. See `README.md` and
`docs/architecture.md` for the full design.

## Toolchain

- **Java 21 LTS** (project target). Maven 3.9+.
- **Node 20 LTS** + npm (frontend). Angular CLI 22.
- **Docker** with the Compose plugin.
- The repo was developed on a machine with JDK 26; to keep Mockito/Byte Buddy
  working on newer JDKs, each service POM overrides `byte-buddy.version` to
  1.17.0. If you move past Java 25, bump it further (see
  https://github.com/raphw/byte-buddy).

## Build order (IMPORTANT)

Every service depends on **two** library modules — `shared-contracts` (Kafka
event DTOs) and `service-commons` (audit trail, resilient HTTP, S3). Install
both **first**, in this order, or nothing else resolves:

```bash
cd services/shared-contracts && mvn clean install
cd ../service-commons     && mvn clean install
```

Then any service:

```bash
cd services/<name>-service && mvn clean verify
```

## Running the tests (one command)

```bash
./scripts/test-all.sh                  # everything, with coverage
./scripts/test-all.sh --backend-only
./scripts/test-all.sh --frontend-only
./scripts/test-all.sh --skip-coverage  # faster
```

No infrastructure needed: backend tests use H2 and mocked clients, frontend
tests use jsdom. Coverage lands in `services/<module>/target/site/jacoco/` and
`frontend/coverage/`.

There are ~102 backend tests and 26 frontend tests. The ones that encode the
rubric proofs:
- `LegalHoldLedgerTest.releasingOneOfTwoOverlappingHoldsKeepsTheMessageProtected` — FR-4.5, the hard one
- `ArchiveControllerTest.deletingHeldMessageIsBlocked` — FR-4.6 (held delete → 409)
- `DispositionLogicTest.heldMessagesAreSkippedNotDeleted` + `theRunRecordsWhichMessagesWereDeletedAndWherePreserved` — FR-4.6 / FR-5.3
- `CaseStateMachineTest` + `CaseServiceLifecycleTest` — FR-2.2 lifecycle, FR-2.5 read-only
- `ExportPackageBuilderTest` — FR-6.3 attachments, FR-6.5 reproducibility and tamper detection
- `ExportJobLifecycleTest` — FR-6.1 hold scope, FR-6.6 retry rules
- `SearchQueryBuilderTest.multipleTypesAreOredNotAnded` — FR-3.2 filter composition
- `AuditEventConsumerTest` — FR-7 append-only persistence and filtering

## Frontend

```bash
cd frontend
npm install        # already done in this workspace
npm run build -- --configuration production   # outputs dist/frontend/browser
ng serve           # dev server with /api proxy -> localhost:80xx backends
```

The dev-server proxy (`proxy.conf.mjs`) maps `/api/cases`→:8084,
`/api/search`→:8083, `/api/holds`→:8085, `/api/exports`→:8086, `/api/audit`→:8087,
`/api/messages`→:8082. In docker, nginx does the equivalent routing (see
`frontend/Dockerfile`).

## Credentials (required before the first run)

Object storage is **Amazon S3**, so archive-service (attachments) and
export-service (packages) need working AWS credentials:

```bash
cp .env.example .env    # then fill in AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION
```

`.env` is gitignored — never commit it. Credentials are resolved by the AWS
SDK's default provider chain, so they are never read from application config.

MongoDB defaults to the local container; set `MONGODB_URI` to an Atlas
`mongodb+srv://...` string to use Atlas instead.

For an offline demo, set `AWS_S3_ENDPOINT` and `AWS_S3_PATH_STYLE=true` to
point the same code at any S3-compatible endpoint.

## Run the whole stack

```bash
docker compose up -d --build
```

Frontend at http://localhost:8080. Service ports 8081–8087. Then generate the
corpus:

```bash
cd data-generator
mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.url=http://localhost:8081 --count=10000"
```

## Run just the infra (for IDE dev)

```bash
docker compose up -d postgres mongo elasticsearch kafka init-setup
```

Each service's `application.yml` defaults to `localhost` for all dependencies,
so the Spring Boot apps run from your IDE against the dockerized infra.

## Project layout

```
services/shared-contracts/   # Kafka event records + topic names + enums (BUILD FIRST)
services/service-commons/    # audit trail, actor context, resilient HTTP, S3 (BUILD SECOND)
services/<each>-service/     # independent Maven project, own pom.xml + Dockerfile
data-generator/              # Spring Boot CLI that posts 10k messages to ingestion
frontend/                    # Angular 22 standalone, signals, lazy routes
infra/docker/                # postgres init + kafka topic bootstrap
infra/k8s/                   # stretch-goal manifests
docs/                        # architecture.md, api-contracts.md
docker-compose.yml           # full stack, single command
```

## Common gotchas

- **Don't skip the library installs.** Install `shared-contracts` *then*
  `service-commons`, or services fail to resolve
  `com.smarsh.discoveryhub:shared-contracts` / `:service-commons:1.0.0-SNAPSHOT`.
- **`-parameters` compiler flag is required** (set in every service pom). Without
  it, Spring can't resolve `@PathVariable`/`@RequestParam` by name and you get
  confusing 404s from controllers.
- **Tests use H2 / mocked clients / an in-memory object store**, so no Postgres,
  Mongo, ES, Kafka or AWS account is needed to run `mvn verify`. The
  `@SpringBootTest` tests set `spring.kafka.bootstrap-servers=localhost:0` so
  the Kafka autoconfig creates beans without connecting.
- **audit-service runs `data-postgresql.sql` after Hibernate** (via
  `spring.jpa.defer-datasource-initialization=true`) to install the append-only
  triggers. Tests on H2 set `spring.sql.init.platform=h2` so the PL/pgSQL is
  skipped.
- **Don't use `when(...).thenThrow(...)` to re-stub a Mockito mock** that already
  has an `Answer`: `when()` invokes the existing stub with null arguments first.
  Use `doThrow(...).when(mock).method(...)`.
- **Hibernate needs mutable collections.** Passing an immutable `List.of()` into
  an entity's `@ElementCollection` setter fails at flush with
  `UnsupportedOperationException`, because merge clears the backing list. Copy
  into an `ArrayList` in the setter (see `HoldEntity.setCustodians`).
- **The async executors are injected, not created inline** (`ExportAsyncConfig`,
  `HoldAsyncConfig`). Tests supply an executor they control; overriding the bean
  needs `spring.main.allow-bean-definition-overriding=true`.
- **Never dispatch a background worker from inside a `@Transactional` method.**
  The worker starts before the commit, its own connection cannot see the new
  row, and it silently does nothing. This bit both `HoldService.placeHold`
  (holds stuck unresolved) and `ExportService.requestExport` (jobs stuck at
  QUEUED). Both now register a `TransactionSynchronization` and dispatch in
  `afterCommit`. It is a *race*, so it passes in small tests and fails under
  load — the worst kind.
- **Writes made during `afterCommit` are discarded without error.** Work that
  must persist from such a callback needs its own transaction; `HoldService`
  uses a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. Note that
  `@Transactional` does not help here at all, because these methods are reached
  by self-invocation and bypass the proxy.
- **Spring Data cannot build a record from a field projection.** A projection
  supplies nulls for unselected components, which fails on a record's canonical
  constructor (primitives). `LegalHoldLedger` reads ids into `org.bson.Document`
  instead. Mocked-`MongoTemplate` unit tests cannot catch this.
- **PostgreSQL rejects `:param IS NULL` guards in JPQL** with "could not
  determine data type of parameter" when the parameter is an unused `Instant`.
  H2 accepts it, so it only breaks against the real database. Use
  Specifications for dynamic filters — see `AuditLogSpecifications`.
- **Spring's SQL script runner splits on `;`** and does not understand PL/pgSQL
  dollar-quoting, so it cuts a trigger body in half. audit-service sets
  `spring.sql.init.separator: ';;'`.
- **The data-generator bounds its concurrency** (`--concurrency`, default 64).
  Firing all 10,000 requests at once overruns Tomcat's accept queue and ~14% of
  the corpus is refused; bounded, all 10,000 are accepted and it is *faster*
  (2s vs 53s) because nothing is thrashing.
- **archive-service sets Kafka listener concurrency to 3** to match the
  partition count. Spring defaults to 1, which serialises the whole corpus
  behind one thread — 13 msg/s versus 34 msg/s. Ordering is unaffected because
  messages are keyed by `sourceMessageId`.
- **search-service saved searches** are stored in an embedded H2 file
  (`./data/saved-searches`) so the service doesn't need its own Postgres.
- **Kafka image is `apache/kafka:3.7.0`, not `bitnami/kafka`** (Bitnami pulled
  its docker.io images). The official image drops its bundled
  `server.properties` defaults the moment any `KAFKA_*` env var is set, so the
  single-node-critical factors are set explicitly in `docker-compose.yml`:
  `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1`,
  `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1`,
  `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1`, `KAFKA_LOG_DIRS`. Without these the
  `__consumer_offsets` topic (RF default 3) can't be created on a 1-broker
  cluster and consumers can never join (FindCoordinator hangs, fetches return
  nothing, `message-archived` stays at 0). The KRaft `CLUSTER_ID` env var (not
  `KAFKA_CLUSTER_ID`) is what the image actually reads.
- **apache/kafka runs as non-root `appuser` (uid 1000)**, so the `kafka-data`
  named volume must be owned by uid 1000 or the broker can't write
  `meta.properties` and exits. This is now handled automatically by the
  `kafka-init` service in docker-compose, which chowns the volume before Kafka
  starts — `docker compose up` works first time on a clean machine. (The old
  manual fix was `docker run --rm -v discoveryhub_kafka-data:/data alpine
  chown -R 1000:1000 /data`.)
- **Work that must outlive a screen cannot live in a route component.** Every
  route is a lazy `loadComponent`, so navigating away destroys the component
  and `takeUntilDestroyed(this.destroyRef)` cancels its timers and polls
  silently. The demo disposition's countdown hit this: leaving the retention
  tab cancelled the deletion. It now lives in the root `DemoDispositionService`
  (`frontend/src/app/core/demo-disposition.ts`), which also mirrors the
  outstanding demo to localStorage and is injected by `App` so a reload on any
  screen resumes it.
- **ingestion-service and archive-service both serve `/api/v1/messages`**, so
  the browser cannot reach both by prefix. The frontend calls ingestion at
  `/api/v1/ingestion`; `proxy.conf.mjs` (`pathRewrite`) and nginx (a
  `proxy_pass` with a URI) both rewrite it back. Add new ingestion routes under
  that alias, not under `/api/v1/messages`.
- **Frontend builds on `node:22-alpine`**, not Node 20. The resolved Angular
  22.1.x deps require Node >= 22.22.3; on Node 20 `ng build` hard-fails with
  "Angular CLI requires a minimum Node.js version of v22.22.3". (README's
  "Node 20 LTS" note predates the resolved Angular versions.)
- **`infra/docker/setup.sh` runs under `/bin/bash`** (compose entrypoint),
  not `/bin/sh`. apache/kafka's `/bin/sh` is busybox ash, which rejects the
  bash array `TOPICS=(...)` with "syntax error: unexpected (".

## Conventions

- Group id `com.smarsh.discoveryhub`, version `1.0.0-SNAPSHOT` everywhere.
- Package per service: `com.smarsh.discoveryhub.<service>.*`.
- Kafka event records are Java records in `shared-contracts` — never duplicated.
- Each service has its own `AuditEventPublisher` that emits to the shared
  `audit-events` topic; no service writes to `audit_db` directly.
- Controllers use standalone-style tests where stubbing return values matters;
  otherwise `@WebMvcTest`/`@SpringBootTest` with `@MockBean`.
 
