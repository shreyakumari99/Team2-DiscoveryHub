# API Contracts

The canonical source of truth for the Kafka event shapes is the
`services/shared-contracts` module (Java records). This document mirrors them
for convenience and lists the REST endpoints each service exposes.

## 1. Kafka event contracts

All events are JSON, serialized with Spring Kafka's `JsonSerializer` and a
`JavaTimeModule`-aware `ObjectMapper` so `java.time.Instant` fields use ISO-8601.
Type headers carry the FQCN; consumers resolve the concrete type because every
event lives in `com.smarsh.discoveryhub.events` (in the `shared-contracts`
artifact on every classpath).

### MessageIngestedEvent — topic `message-ingested`
```json
{
  "sourceMessageId": "src-<uuid>",          // idempotency key (FR-1.6)
  "type": "EMAIL | CHAT",
  "subject": "Q3 earnings forecast",        // nullable for chat
  "body": "...",
  "timestamp": "2025-09-01T10:15:30Z",
  "sender": "alice.chen@smarsh.com",
  "participants": ["alice.chen@smarsh.com", "bob.patel@smarsh.com"],
  "threadId": "thread-12",                  // nullable
  "attachments": [                          // empty if none
    { "name": "forecast.xlsx", "contentType": "application/vnd...", "sizeBytes": 1024, "contentBase64": "..." }
  ]
}
```
Producer: ingestion-service. Consumer: archive-service.

### MessageArchivedEvent — topic `message-archived`
```json
{
  "messageId": "<immutable uuid>",          // assigned by archive (FR-1.5)
  "sourceMessageId": "src-<uuid>",
  "type": "EMAIL | CHAT",
  "subject": "...",
  "body": "...",
  "timestamp": "...",
  "sender": "...",
  "participants": ["..."],
  "threadId": "thread-12",                  // nullable
  "attachmentObjectKeys": ["attachments/<id>/forecast.xlsx"],
  "hasAttachment": true,
  "held": false,
  "archivedAt": "..."
}
```
Producer: archive-service. Consumer: search-service (indexes it → FR-1.7).

### CaseEvent — topic `case-events`
```json
{
  "caseId": "<uuid>",
  "type": "CREATED | UPDATED | TRANSITIONED | CLOSED | CUSTODIAN_ADDED | EVIDENCE_ADDED | EVIDENCE_REMOVED",
  "caseName": "Q3 Investigation",
  "state": "DRAFT | ACTIVE | UNDER_REVIEW | CLOSED",
  "occurredAt": "...",
  "details": { "from": "DRAFT", "to": "ACTIVE" }
}
```
Producer: case-service. Consumer: hold-retention-service (releases holds on
CLOSED).

### HoldEvent — topic `hold-events`
```json
{
  "holdId": "<uuid>",
  "caseId": "<uuid>",
  "type": "PLACED | RELEASED",
  "custodians": ["alice.chen@smarsh.com"],
  "dateFrom": "...",                        // nullable
  "dateTo": "...",                          // nullable
  "searchTerms": "bonus pool",              // nullable
  "messageIds": ["<immutable id>", "..."],  // resolved scope (PLACED); ALWAYS EMPTY for RELEASED
  "occurredAt": "..."
}
```
Producer: hold-retention-service. Consumer: archive-service.

A RELEASED event carries no message ids on purpose. Archive records which
messages each hold covers, so it derives the release scope itself; re-sending a
resolved list would risk releasing a set that differs from the one applied.

### MessageHoldStateEvent — topic `message-hold-state`
```json
{
  "holdId": "<uuid>",
  "caseId": "<uuid>",
  "messageIds": ["<immutable id>", "..."],  // batched, max 1000 per event
  "held": true,
  "occurredAt": "..."
}
```
Producer: archive-service, *after* it has applied a hold. Consumer:
search-service, which mirrors `held` into the index (FR-4.4).

This exists because archive owns the overlapping-hold rule (FR-4.5): a message
stays protected while *any* hold covers it. The event carries the **outcome**,
not the intent. If search re-derived `held` from a raw RELEASED `HoldEvent`, it
would clear the badge on messages a second hold still protects, and the index
would contradict the archive.

### ExportEvent — topic `export-events`
```json
{
  "jobId": "<uuid>",
  "caseId": "<uuid>",
  "type": "REQUESTED | STARTED | COMPLETED | FAILED",
  "status": "QUEUED | RUNNING | COMPLETED | FAILED",
  "occurredAt": "...",
  "details": { "items": "120", "checksum": "abc123..." }
}
```
Producer: export-service. (The UI polls the export REST API for status rather
than subscribing; this event is for cross-service awareness.)

### AuditEvent — topic `audit-events`
```json
{
  "eventId": "<uuid>",                      // dedup key (unique constraint in audit_log)
  "timestamp": "...",
  "actor": "investigator | <service>",
  "service": "case-service",
  "action": "CASE_CREATED | HOLD_PLACED | EXPORT_DOWNLOADED | DISPOSITION_RUN | ...",
  "entityType": "CASE | HOLD | MESSAGE | EXPORT | CUSTODIAN | EVIDENCE | ...",
  "entityId": "...",
  "caseId": "...",                          // nullable
  "beforeJson": "...",                      // nullable
  "afterJson": "..."                        // nullable
}
```
Producer: **every** service. Consumer: audit-service (the only writer to
`audit_db`).

## 2. REST endpoints

All services serve JSON on their respective ports. Default ports (docker-compose
maps them 1:1 to the host):

| Service | Port |
| --- | --- |
| ingestion-service | 8081 |
| archive-service | 8082 |
| search-service | 8083 |
| case-service | 8084 |
| hold-retention-service | 8085 |
| export-service | 8086 |
| audit-service | 8087 |
| frontend | 8080 |

**`X-Actor` header.** Every endpoint accepts an optional `X-Actor` header
naming the investigator making the request. The UI sends it on every call, and
services forward it on inter-service hops, so one action stays attributable end
to end in the audit trail (FR-7.2). When absent, the action is attributed to
the service — correct for genuinely unattended work like the disposition job.

### ingestion-service
| Method | Path | Body / Params | Returns |
| --- | --- | --- | --- |
| POST | `/api/v1/messages` | `IngestionRequest` | 202 `IngestionResponse` |

`IngestionRequest` = the fields of `MessageIngestedEvent` (the client supplies
`sourceMessageId`, `type`, `subject`, `body`, `timestamp`, `sender`,
`participants`, `threadId`, `attachments`).

### archive-service
| Method | Path | Returns |
| --- | --- | --- |
| GET | `/api/v1/messages/{id}` | 200 `ArchivedMessage` (404 if missing) |
| GET | `/api/v1/messages?ids=a,b,c` | 200 `ArchivedMessage[]` — bulk fetch; unknown ids are omitted, not an error |
| GET | `/api/v1/messages/{id}/attachments/{index}` | 200 raw bytes (`application/octet-stream`); 404 if out of range — FR-6.3 |
| GET | `/api/v1/messages/by-hold/{holdId}` | 200 `string[]` — ids frozen by that hold, backs hold-scoped export (FR-6.1) |
| GET | `/api/v1/messages/stats` | 200 `{ totalMessages, heldMessages }` — FR-8.2 |
| POST | `/api/v1/messages/held-count` | body `{ holdIds: [...] }` → `{ heldMessages }`, deduplicated across overlapping holds — FR-4.4 |
| DELETE | `/api/v1/messages/{id}?reason=disposition` | 200 `{id,deleted,reason}`; **409** if held (`reason: LEGAL_HOLD`) — FR-4.6 |

`ArchivedMessage` carries `holdIds` — the set of active holds covering it —
alongside the denormalized `held` boolean. `held` is always
`!holdIds.isEmpty()`; the two cannot disagree (FR-4.5).

A missing message is **404**; a malformed request is **400**. These are kept
apart because the disposition job treats 404 as "already gone", and collapsing
the two would let a genuine bug be recorded as a successful cleanup.

### search-service
| Method | Path | Query params | Returns |
| --- | --- | --- | --- |
| GET | `/api/v1/search` | `query, dateFrom, dateTo, types, custodians, hasAttachment, onHold, page, size, sort, caseId` | 200 `SearchResponse` |
| GET | `/api/v1/search/ids` | same filters, plus `limit` (default/max 10000) | 200 `string[]` — every matching id, not just a page (FR-3.6, FR-4.1) |
| POST | `/api/v1/saved-searches` | `SaveSearchRequest` | 200 `SavedSearch`; **400** if the criteria could not be replayed |
| GET | `/api/v1/saved-searches?caseId=` | — | 200 `SavedSearch[]` |
| GET | `/api/v1/saved-searches/{id}` | — | 200 `SavedSearch` / 404 |
| POST | `/api/v1/saved-searches/{id}/run` | `page, size` (optional overrides) | 200 `SearchResponse` — server-side re-run (FR-3.5) |

`SearchResponse = { query, page, size, totalHits, totalPages, hits: SearchHit[] }`
where `SearchHit = { id, type, subject, sender, timestamp, hasAttachment, held, score, highlight }`.

Multi-valued filters (`types`, `custodians`) repeat the key —
`?custodians=a@x.com&custodians=b@x.com` — and are **OR**-ed. A custodian
matches if they sent the message or took part in it. Elasticsearch is
unavailable → **503**, not 500, so the UI can offer a retry.

### case-service
| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/v1/cases` | `CreateCaseRequest` | 200 `CaseEntity` |
| GET | `/api/v1/cases` | — | `CaseEntity[]` |
| GET | `/api/v1/cases/{id}` | — | `CaseEntity` / 404 |
| POST | `/api/v1/cases/{id}` | `UpdateCaseRequest` | `CaseEntity` |
| POST | `/api/v1/cases/{id}/transition` | `TransitionRequest { to }` | `CaseEntity`; **409** if illegal (FR-2.2) or closed (FR-2.5) |
| POST | `/api/v1/cases/{id}/custodians` | `AddCustodiansRequest` | `CaseEntity` |
| POST | `/api/v1/cases/{id}/evidence` | `AddEvidenceRequest` | `EvidenceItem[]` |
| POST | `/api/v1/cases/{id}/evidence/bulk` | `AddEvidenceBulkRequest` | `EvidenceItem[]` (FR-3.6) |
| GET | `/api/v1/cases/{id}/evidence` | — | `EvidenceItem[]` |
| DELETE | `/api/v1/cases/{caseId}/evidence/{evidenceId}` | — | 204 |
| POST | `/api/v1/custodians` | `CustodianEntity` | `CustodianEntity` |
| GET | `/api/v1/custodians` | — | `CustodianEntity[]` |

### hold-retention-service
| Method | Path | Body / Params | Returns |
| --- | --- | --- | --- |
| POST | `/api/v1/holds` | `PlaceHoldRequest` | **202** `HoldEntity` — scope resolves async (FR-4.3); **409** if the case is closed (FR-2.5) |
| GET | `/api/v1/holds?caseId=` | — | `HoldEntity[]` |
| GET | `/api/v1/holds/{id}` | — | `HoldEntity` / 404 |
| POST | `/api/v1/holds/{id}/release?reason=` | — | `HoldEntity` |
| POST | `/api/v1/holds/{id}/resolve-scope` | — | 202 `HoldEntity` — re-run a resolution that failed |
| GET | `/api/v1/holds/count?caseId=` | — | `number` — active holds on the case |
| GET | `/api/v1/holds/held-messages?caseId=` | — | `{ heldMessages }` — distinct messages held, deduplicated across overlapping holds (FR-4.4) |
| GET | `/api/v1/retention/policies` | — | `RetentionPolicy[]` |
| POST | `/api/v1/retention/policies` | `RetentionPolicy` | `RetentionPolicy` (FR-5.1) |
| POST | `/api/v1/retention/disposition` | — | `DispositionRun` (FR-5 trigger) |
| GET | `/api/v1/retention/disposition/runs` | — | `DispositionRun[]` |
| GET | `/api/v1/retention/disposition/runs/{runId}/items` | `outcome` (optional) | `DispositionRunItem[]` (FR-5.3) |

`HoldEntity` includes `scopeResolved`, `scopeResolvedAt` and
`scopeFailureReason`. An unresolved hold is active but protects nothing yet —
that has to be visible, because a hold silently stuck at zero messages looks
identical to a hold that correctly matched nothing.

`DispositionRunItem = { runId, messageId, messageType, outcome, retentionCutoff, decidedAt }`
where `outcome` is `DELETED | SKIPPED_HELD | NOT_FOUND | ERROR`. Filtering by
`SKIPPED_HELD` gives the message-by-message proof that the legal hold blocked
deletion — a count alone is not a defensible disposition record.

### export-service
| Method | Path | Body | Returns |
| --- | --- | --- | --- |
| POST | `/api/v1/exports` | `CreateExportRequest { caseId, scope, requestedBy }` | 202 `ExportJob` (FR-6.2); **409** if the case is closed (FR-2.5); **400** on an unknown scope |
| GET | `/api/v1/exports?caseId=` | — | `ExportJob[]` |
| GET | `/api/v1/exports/{id}` | — | `ExportJob` / 404 |
| GET | `/api/v1/exports/{id}/download` | — | `{ url }` — expiring presigned S3 link (FR-6.4); **409** unless COMPLETED |
| GET | `/api/v1/exports/{id}/verify` | — | 200 `VerificationResult` (FR-6.5) |
| POST | `/api/v1/exports/{id}/retry` | — | 202 `ExportJob` (the **new** job); **409** unless the job is FAILED (FR-6.6) |

`scope` is `evidence` (default) or `hold:<holdId>` for a hold's full scope.

`VerificationResult = { verified, itemsChecked, contentChecksumMatches, packageChecksumMatches, problems[] }`.
It answers **200 either way** — a failed verification is a legitimate,
reportable finding, not a server error, so the UI can list exactly which item
does not match.

`ExportJob` carries two checksums, and they answer different questions:
`packageChecksum` covers the exact zip bytes and detects tampering with the
artifact; `contentChecksum` covers only the packaged evidence and is stable
across runs, so re-exporting the same evidence is provably the same evidence.

### audit-service
| Method | Path | Params | Returns |
| --- | --- | --- | --- |
| GET | `/api/v1/audit` | `caseId, action, actor, entityType, entityId, from, to, page, size` — all optional | `Page<AuditEntry>` (FR-7.4) |
| GET | `/api/v1/audit/actions` | — | `string[]` — distinct action names, for the UI filter |
| GET | `/api/v1/audit/summary` | — | `{ totalEntries }` |

Results are **paginated** (default 100, max 1000) and newest-first. The audit
log is the fastest-growing table in the system — every search and every
archived message adds a row — so returning all of it is not an option.

> There is deliberately **no** POST/PUT/DELETE on `/api/v1/audit`. Nor is there
> an update path anywhere in the service: the Kafka consumer only ever inserts.
> The database enforces this independently — triggers on `audit_log` reject
> UPDATE, DELETE and TRUNCATE outright, so the trail holds even against a
> future bug or a hand-typed query (FR-7.3).
