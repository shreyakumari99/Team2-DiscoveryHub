# Architecture

This document explains the DiscoveryHub architecture, the design decisions and
trade-offs behind it, and how each functional and non-functional requirement is
satisfied. Expect to be asked "why X here and not Y?" — the rationale for each
major choice is recorded inline.

## 1. Topology

```
                          +-----------------+
                          |  data-generator |  (calls Ingestion REST like a client)
                          +-----------------+
                                  | POST /api/v1/messages
                                  v
+-------------------+     +-------------------+        +-------------------+
|    frontend       |---->| ingestion-service |------->|   Kafka topic     |
| (Angular / nginx) |     |  (stateless, 8081)|  pub   | message-ingested  |
+-------------------+     +-------------------+        +-------------------+
                                                                |
                                                                | consume
                                                                v
                                                +-----------------------------+
                                                |   archive-service (8082)    |
                                                |   MongoDB bodies + S3       |
                                                |   attachments. SOLE deleter.|
                                                |   OWNS legal-hold state.    |
                                                +-----------------------------+
                                    pub message-archived |
                                    pub message-hold-state|
                                                          v
                                                +-----------------------------+
                                                |    search-service (8083)    |
                                                |    Elasticsearch index      |
                                                +-----------------------------+

  case-service (8084, Postgres case_db)
       | pub case-events
       v
  hold-retention-service (8085, Postgres hold_db)
       |  calls Search to resolve scope -> pub hold-events -> archive applies the hold
       |  scheduled disposition job: calls Archive DELETE per expired msg (refused if held)

  export-service (8086, Postgres export_db + S3)
       |  calls Case for evidence (or Archive for a hold's scope) + Archive for
       |  content and attachment bytes -> zip + manifest + checksums

  audit-service (8087, Postgres audit_db, append-only)
       |  consumes the single shared audit-events topic that EVERY service publishes to
```

Every backend service owns its own data and is independently deployable (NFR-1).

## 2. Service responsibilities & data ownership

| Service | Owns | Key responsibilities | FRs |
| --- | --- | --- | --- |
| ingestion-service | nothing (stateless) | REST entry, validate, publish to Kafka | 1.4 |
| archive-service | MongoDB + S3 attachments bucket | durable store, immutable id, dedup, **legal-hold ledger**, sole deleter | 1.5, 1.6, 4.2, 4.5, 4.6 |
| search-service | Elasticsearch index (+ H2 for saved searches) | index, full-text search, filters, highlight, pagination, saved searches | 3 |
| case-service | Postgres `case_db` | case CRUD, lifecycle state machine, custodians, evidence | 2 |
| hold-retention-service | Postgres `hold_db` | async scope resolution, hold events, scheduled disposition + per-message record | 4, 5 |
| export-service | Postgres `export_db` + S3 exports bucket | async export jobs, zip + manifest + SHA-256 checksums, expiring links, verification | 6 |
| audit-service | Postgres `audit_db` (append-only) | consumes the shared audit-events topic | 7 |
| service-commons | nothing (library) | shared audit publisher, actor propagation, resilient HTTP, S3 abstraction | cross-cutting |
| shared-contracts | nothing (library) | Kafka event records, topic names, enums | cross-cutting |
| data-generator | nothing | builds the 10k+ corpus via Ingestion REST | 1.2 |
| frontend | nothing | Angular SPA: dashboard, cases, search, holds, exports, audit | 8 |

## 3. Key design decisions

### 3.1 Ingestion is deliberately stateless (FR-1.4)
The component that *accepts* a message is not the component that *stores* it.
Ingestion validates and publishes to Kafka; storage happens in archive-service.
This gives us:

- **Resiliency (NFR-2):** if archive-service is down, ingestion keeps accepting
  and Kafka buffers the backlog; archive drains it on recovery. The demo is
  literally `docker compose stop archive-service`, keep ingesting, then `start`
  and watch the messages appear in search.
- **Throughput:** ingestion scales horizontally without coordinating writes.

### 3.2 Idempotency via `sourceMessageId` (FR-1.6)
The ingestion event carries a client-supplied `sourceMessageId`. Archive looks
it up before storing and skips if present. The `messages` collection has a
unique index on that field, so even a race across partitions cannot create a
duplicate. Messages are also *keyed* by it on the Kafka topic, so duplicates
land on the same partition and are handled in order.

### 3.3 The immutable id is assigned by archive-service (FR-1.5)
Ingestion does not assign the id; archive does, on first durable storage. It is
a UUID and never changes. Everything downstream — the search index, evidence
items, holds, exports — references it.

### 3.4 archive-service is the sole deleter, and owns hold state (FR-4.6)
Only archive-service can delete a message. `DELETE /api/v1/messages/{id}`
checks the hold state and returns **409 Conflict** with `reason: LEGAL_HOLD` if
protected. The disposition job calls this endpoint; held messages come back 409
and are recorded as skipped. Centralising the guarantee in exactly one place —
rather than scattering it across services — is what makes it auditable.

### 3.5 Overlapping holds: a set of hold ids, not a boolean (FR-4.5)
Each archived message carries `holdIds`, the set of active holds covering it.
Placing a hold `$addToSet`s its id; releasing `$pull`s it. **A message loses
protection only when that set becomes empty.**

This is the correctness point the requirement is really testing. With a single
`held` boolean — the obvious first implementation — releasing hold A clears the
flag on messages that hold B still covers, and those messages become deletable
while a live legal hold applies to them. `LegalHoldLedgerTest` pins the
behaviour directly.

Two consequences follow from archive owning this rule:

- A RELEASED `HoldEvent` carries **no** message ids. Archive already knows which
  messages a hold covers, so it derives the release scope itself. Re-sending a
  resolved list would risk releasing a set that differs from the one applied.
- search-service does **not** consume `hold-events`. It consumes
  `message-hold-state`, which archive publishes *after* applying the change and
  which carries the resulting per-message state. If search re-derived `held`
  from a raw RELEASED event it would clear badges on messages that are still
  protected, and the index would contradict the archive.

### 3.6 Audit via a shared Kafka topic, and enforced append-only (FR-7)
Every service publishes `AuditEvent`s to one `audit-events` topic through the
shared `AuditTrail` in service-commons. audit-service is the only consumer and
the only writer to `audit_db`, so "no shared database schemas" holds end to end.

Append-only is enforced in three places, because a guarantee resting on nobody
writing the wrong line of Java is not a guarantee:

1. The REST API exposes no write verb at all.
2. The repository is only ever used to insert and read.
3. **The database rejects it.** `data-postgresql.sql` installs triggers that
   raise an exception on UPDATE, DELETE or TRUNCATE of `audit_log`. A trigger is
   used rather than `REVOKE` because the app role owns the table and an owner
   can always grant its own privileges back; a BEFORE trigger binds the owner too.

### 3.7 Export verifiability: two checksums, deliberately (FR-6.3, FR-6.5)
The requirement asks for a package that is both **reproducible** and
**verifiable**, and those pull in opposite directions — anything recording when
an export ran makes two exports of identical evidence differ. So the package
carries:

- `manifest.json` — deterministic only: one SHA-256 per item plus a
  `contentChecksum` over all of them. Export the same evidence twice and these
  are byte-identical. Zip entry timestamps are pinned for the same reason.
- `provenance.json` — the per-run facts (job id, when, who, what scope),
  excluded from the content checksum.

Tampering is detectable at both levels: altering a message changes its per-item
digest, and altering a digest in the manifest changes the content checksum.
`GET /exports/{id}/verify` re-downloads the package and re-computes everything
from its bytes, checking item digests, the manifest's own content checksum, and
the whole-zip digest against the value recorded when it was produced.

Packages contain **messages and every attachment**. Export fetches attachment
bytes through archive-service's API rather than reading archive's bucket
directly, so the archive stays the only component that knows where message
content physically lives.

### 3.8 Retry safety and stuck jobs (FR-6.6)
Only a **FAILED** job may be retried. Retrying a completed one would produce a
second package of identical evidence under a different name, which is exactly
the ambiguity a chain of custody must not have. A retry is a new job with a new
id and therefore a new object key, so the failed run's partial output is
orphaned rather than overwritten.

A scheduled reaper fails exports left `RUNNING` by a restart. Without it such a
job would spin forever in the UI *and* be un-retryable, since retry only accepts
FAILED.

### 3.9 Disposition records what happened, per message (FR-5.3)
A disposition run writes a `DispositionRunItem` for every message it considered,
with the outcome (`DELETED`, `SKIPPED_HELD`, `NOT_FOUND`, `ERROR`) and the
retention cut-off that made it eligible. Counts alone are not a defensible
disposition record: when a regulator asks which communications were destroyed
and which were preserved by the hold, the answer has to be a list of ids.

### 3.10 Closed cases are read-only across services (FR-2.5)
case-service refuses evidence and custodian changes on a closed case. But the
rule is about the *case*, not about one service, so hold-retention and export
both check the case state before acting. Both **fail closed** if case-service is
unreachable: a read-only rule that lapses during an outage is not a rule.

### 3.11 Single Postgres instance, separate databases per service
The four Postgres-backed services share one Postgres *instance* but each
connects to its **own database** (`case_db`, `hold_db`, `export_db`,
`audit_db`), created by `infra/docker/init-postgres.sh`. No service reads
another's schema. For true isolation, split into four containers — the only
change is the connection URL.

### 3.12 service-commons: one implementation of each cross-cutting concern
Five services previously carried near-identical copies of an
`AuditEventPublisher`, and each hand-built its before/after JSON with string
concatenation — which produced invalid documents the moment a value contained a
quote (an export failure message, for instance). service-commons replaces them
with one `AuditTrail` behind an interface, a fluent `AuditRecord`, and Jackson
serialization. It also owns:

- `ServiceClients` — every inter-service `RestClient`, with bounded connect and
  read timeouts (see 5, NFR-2).
- `CurrentActor` + `ActorHeaderFilter` — the acting investigator, bound from the
  `X-Actor` header and forwarded across service hops.
- `ObjectStore` / `S3ObjectStore` — object storage behind an interface, so
  packaging and attachment logic is unit-testable with an in-memory
  implementation and no credentials.

## 4. Async messaging (Kafka)

| Topic | Producer(s) | Consumer(s) | Purpose |
| --- | --- | --- | --- |
| `message-ingested` | ingestion-service | archive-service | accepted message awaiting durable storage |
| `message-archived` | archive-service | search-service | message stored; gives the <30s searchability target (FR-1.7) |
| `case-events` | case-service | hold-retention-service | release holds when a case closes (FR-4.5) |
| `hold-events` | hold-retention-service | archive-service | apply/release a hold |
| `message-hold-state` | archive-service | search-service | the *resulting* per-message hold state (see 3.5) |
| `export-events` | export-service | (UI via polling) | job lifecycle (Queued→Running→Completed/Failed) |
| `audit-events` | **every** service | audit-service | the single audit stream |

Kafka runs in **KRaft mode** (no Zookeeper) to reduce moving parts. Topics are
pre-created by `infra/docker/setup.sh` with 3 partitions; auto-create is also on.
Messages are keyed by the relevant id so related events stay ordered on one
partition.

Serialization: Spring's `JsonSerializer`/`JsonDeserializer` with a
`JavaTimeModule`-aware `ObjectMapper` and type headers enabled. Every event type
lives in `shared-contracts` on every service's classpath, so consumers resolve
the concrete type from the header without needing the producer's packages.

## 5. Non-functional requirements

- **NFR-1 (architecture):** 7 backend services + a frontend; each owns its data;
  no shared schemas; REST + Kafka between them.
- **NFR-2 (resiliency):**
  - Kafka buffers during a downstream outage (3.1).
  - **Every** inter-service client is built by `ServiceClients` with a 2s connect
    and 10s read timeout. This matters more than it sounds: the JDK HTTP client
    has *no* read timeout by default, so a downstream service that accepts the
    connection but never answers would hang the calling thread forever — a
    disposition run or export job would stall indefinitely instead of failing
    and being retried.
  - resilience4j circuit breakers and retries wrap those calls. Retries are
    limited to transient transport failures; a 404 or a 409 is a valid answer,
    not a sign of ill health, so both are excluded from breaker statistics.
    Deleting is deliberately **not** retried — it is not safely repeatable and
    the disposition record must reflect exactly what the archive said.
  - Dependency failures answer **503**, not 500, so the UI can say "retry
    shortly" rather than reporting a defect.
  - The dashboard loads each count independently: one service being down leaves
    the rest of the page accurate.
- **NFR-3 (scale):** 10k+ corpus; Elasticsearch returns searches well inside 2s;
  bulk hold completes asynchronously without timing out the UI (FR-4.3).
- **NFR-4 (deployment):** `docker compose up -d --build` starts everything.
  Kubernetes manifests in `infra/k8s/` are the stretch goal.

## 6. Technology choices — the "why X not Y" answers

| Concern | Choice | Why this and not the alternative |
| --- | --- | --- |
| Messaging | Kafka | Durable, replayable, partitioned. The buffering is exactly what gives us NFR-2. RabbitMQ would also work but lacks the durable replay that makes "archive was down, now catch up" trivial. |
| Document store (Archive) | MongoDB | Message bodies are flexible documents, and the hold-id set is a natural array with `$addToSet`/`$pull` operating atomically per document — which is what makes overlapping holds correct without a transaction. Postgres JSONB would work but forces more schema. |
| Search | Elasticsearch | Purpose-built full-text with highlighting, filters and fast pagination — exactly FR-3. Postgres full-text search works but its highlighting and relevance tuning are weaker. |
| Object storage | Amazon S3 (AWS SDK v2) | Durable, versionable, and presigned URLs give FR-6.4's expiring links for free. Access is behind an `ObjectStore` interface, and `aws.s3.endpoint` can target any S3-compatible endpoint, so nothing is locked to one provider. |
| RDBMS | PostgreSQL | ACID plus real constraints. The audit append-only guarantee is enforced with database triggers, which needs a database that can do that properly. |
| Resilience | resilience4j | Lightweight, annotation-driven, and works with plain `RestClient`. A service mesh would also solve it but is far too much machinery for a laptop demo. |
| Frontend | Angular | Standalone components, signals and the router map 1:1 onto the FR-8 screens, with strong typing across a large API surface. |
| Language/build | Java 21 + Maven | Records give concise DTOs; virtual threads give cheap async (hold resolution, export jobs) without reactive complexity. |

## 7. Known limitations

- **No authentication.** The spec says no login is required, so the acting
  investigator is supplied by the UI as an `X-Actor` header rather than proven.
  It is honest about what it is: an attribution mechanism, not a security
  control. Adding real auth means populating `CurrentActor` from an
  authenticated principal — one class changes.
- **Attachments are held in memory during export.** Fine for the demo corpus
  (sub-kilobyte files); a production system would stream them into the zip.
- **Hold scope is resolved once.** A message ingested *after* a hold is placed
  is not retroactively covered. Real e-discovery systems re-evaluate active
  holds on ingest; that would be an archive-side check against stored hold
  criteria.
- **Elasticsearch query building is unit-tested against a captured query, not a
  live cluster.** A Testcontainers test would be stronger.
