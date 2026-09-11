import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

/**
 * Backend base URLs. Each service runs on its own port; in development the
 * Angular dev-server proxy (proxy.conf.mjs) maps these prefixes to the right
 * port, and in Docker nginx does the same — so the app always talks to
 * relative `/api/...` paths and never needs to know a hostname.
 */
const CASES_URL = '/api/v1/cases';
const CUSTODIANS_URL = '/api/v1/custodians';
const SEARCH_URL = '/api/v1/search';
const SAVED_SEARCHES_URL = '/api/v1/saved-searches';
const HOLDS_URL = '/api/v1/holds';
const RETENTION_URL = '/api/v1/retention';
const EXPORTS_URL = '/api/v1/exports';
const AUDIT_URL = '/api/v1/audit';
const MESSAGES_URL = '/api/v1/messages';

/**
 * ingestion-service also serves `/api/v1/messages`, so it is addressed under a
 * prefix of its own that the dev-server proxy and nginx both rewrite back
 * (see proxy.conf.mjs and frontend/Dockerfile).
 */
const INGESTION_URL = '/api/v1/ingestion';

/** Shared DTOs mirroring the backend records. */
export interface CaseItem {
  id: string;
  name: string;
  description?: string;
  matterType: 'INVESTIGATION' | 'LITIGATION' | 'REGULATORY_INQUIRY';
  owner: string;
  state: 'DRAFT' | 'ACTIVE' | 'UNDER_REVIEW' | 'CLOSED';
  createdAt: string;
  custodianIds: string[];
}

export interface Custodian {
  id: string;
  name: string;
  email: string;
  department?: string;
}

export interface EvidenceItem {
  id: string;
  caseId: string;
  messageId: string;
  source?: string;
  addedAt: string;
}

export interface SearchHit {
  id: string;
  type: 'EMAIL' | 'CHAT';
  subject?: string;
  sender: string;
  timestamp: string;
  hasAttachment: boolean;
  held: boolean;
  score: number;
  highlight?: Record<string, string[]>;
}

export interface SearchResponse {
  query: string;
  page: number;
  size: number;
  totalHits: number;
  totalPages: number;
  hits: SearchHit[];
}

/** Filters accepted by the search endpoints (FR-3.2). */
export interface SearchCriteria {
  query?: string;
  types?: string[];
  custodians?: string[];
  hasAttachment?: boolean;
  onHold?: boolean;
  dateFrom?: string;
  dateTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface SavedSearch {
  id: string;
  caseId: string;
  name: string;
  queryJson: string;
}

export interface HoldItem {
  id: string;
  caseId: string;
  active: boolean;
  searchTerms?: string;
  dateFrom?: string;
  dateTo?: string;
  messageCount: number;
  placedAt: string;
  releasedAt?: string;
  custodians: string[];
  /** False while the scope is still being resolved, or if resolution failed. */
  scopeResolved: boolean;
  scopeResolvedAt?: string;
  scopeFailureReason?: string;
}

export type ExportStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ExportJob {
  id: string;
  caseId: string;
  scope: string;
  status: ExportStatus;
  requestedBy?: string;
  requestedAt: string;
  startedAt?: string;
  completedAt?: string;
  itemCount: number;
  messageCount: number;
  packageObjectKey?: string;
  packageChecksum?: string;
  contentChecksum?: string;
  failureReason?: string;
}

/** Result of re-verifying a stored package against its manifest (FR-6.5). */
export interface VerificationResult {
  verified: boolean;
  itemsChecked: number;
  contentChecksumMatches: boolean;
  packageChecksumMatches: boolean;
  problems: string[];
}

export interface RetentionPolicy {
  type: 'EMAIL' | 'CHAT';
  retentionMinutes: number;
}

export interface DispositionRun {
  id: string;
  startedAt: string;
  finishedAt?: string;
  deletedCount: number;
  skippedHeldCount: number;
  notFoundCount: number;
  errorCount: number;
  trigger: string;
}

export type DispositionOutcome =
  | 'DELETED'
  | 'SKIPPED_HELD'
  | 'NOT_FOUND'
  | 'ERROR';

export interface DispositionRunItem {
  id: string;
  runId: string;
  messageId: string;
  messageType: 'EMAIL' | 'CHAT';
  outcome: DispositionOutcome;
  retentionCutoff: string;
  decidedAt: string;
}

export interface AuditEntry {
  dbId: string;
  eventId: string;
  timestamp: string;
  actor: string;
  service: string;
  action: string;
  entityType: string;
  entityId: string;
  caseId?: string;
  beforeJson?: string;
  afterJson?: string;
}

/** Spring Data's page envelope, as returned by the audit API. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AuditFilter {
  caseId?: string;
  action?: string;
  actor?: string;
  entityType?: string;
  entityId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** Corpus counts from the archive, the system of record (FR-8.2). */
export interface ArchiveStats {
  totalMessages: number;
  heldMessages: number;
}

/** A message as stored by archive-service; `id` is the archive's own id. */
export interface ArchivedMessage {
  id: string;
  sourceMessageId: string;
  type: 'EMAIL' | 'CHAT';
  subject?: string;
  sender: string;
  timestamp: string;
  held: boolean;
  archivedAt: string;
}

/** What ingestion accepts. `sourceMessageId` is the idempotency key (FR-1.6). */
export interface IngestionRequest {
  sourceMessageId: string;
  type: 'EMAIL' | 'CHAT';
  subject?: string;
  body: string;
  timestamp?: string;
  sender: string;
  participants?: string[];
  threadId?: string;
}

export interface IngestionResponse {
  sourceMessageId: string;
  status: string;
  message: string;
}

/** Central typed HTTP client for all backend services. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  /** Builds query params, dropping empties and expanding arrays into repeats. */
  private params(source: Record<string, unknown>): HttpParams {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(source)) {
      if (value === undefined || value === null || value === '') continue;
      if (Array.isArray(value)) {
        for (const item of value) params = params.append(key, String(item));
      } else {
        params = params.set(key, String(value));
      }
    }
    return params;
  }

  // ---- Cases ---------------------------------------------------------------
  listCases(): Observable<CaseItem[]> {
    return this.http.get<CaseItem[]>(CASES_URL);
  }
  getCase(id: string): Observable<CaseItem> {
    return this.http.get<CaseItem>(`${CASES_URL}/${id}`);
  }
  createCase(body: Partial<CaseItem>): Observable<CaseItem> {
    return this.http.post<CaseItem>(CASES_URL, body);
  }
  transitionCase(id: string, to: CaseItem['state']): Observable<CaseItem> {
    return this.http.post<CaseItem>(`${CASES_URL}/${id}/transition`, { to });
  }
  addCustodians(id: string, custodianIds: string[]): Observable<CaseItem> {
    return this.http.post<CaseItem>(`${CASES_URL}/${id}/custodians`, {
      custodianIds,
    });
  }
  addEvidence(id: string, messageId: string): Observable<EvidenceItem[]> {
    return this.http.post<EvidenceItem[]>(`${CASES_URL}/${id}/evidence`, {
      messageId,
    });
  }
  addEvidenceBulk(
    id: string,
    messageIds: string[],
  ): Observable<EvidenceItem[]> {
    return this.http.post<EvidenceItem[]>(`${CASES_URL}/${id}/evidence/bulk`, {
      messageIds,
    });
  }
  removeEvidence(caseId: string, evidenceId: string): Observable<void> {
    return this.http.delete<void>(
      `${CASES_URL}/${caseId}/evidence/${evidenceId}`,
    );
  }
  listEvidence(id: string): Observable<EvidenceItem[]> {
    return this.http.get<EvidenceItem[]>(`${CASES_URL}/${id}/evidence`);
  }
  listCustodians(): Observable<Custodian[]> {
    return this.http.get<Custodian[]>(CUSTODIANS_URL);
  }
  createCustodian(body: Partial<Custodian>): Observable<Custodian> {
    return this.http.post<Custodian>(CUSTODIANS_URL, body);
  }

  // ---- Search --------------------------------------------------------------
  search(criteria: SearchCriteria): Observable<SearchResponse> {
    return this.http.get<SearchResponse>(SEARCH_URL, {
      params: this.params({ ...criteria }),
    });
  }

  /**
   * Ids of every match, not just the current page — this is what makes
   * "add all results to case" act on the whole result set (FR-3.6).
   */
  searchIds(criteria: SearchCriteria, limit = 10000): Observable<string[]> {
    return this.http.get<string[]>(`${SEARCH_URL}/ids`, {
      params: this.params({ ...criteria, limit }),
    });
  }

  saveSearch(
    caseId: string,
    name: string,
    criteria: SearchCriteria,
  ): Observable<SavedSearch> {
    return this.http.post<SavedSearch>(SAVED_SEARCHES_URL, {
      caseId,
      name,
      queryJson: JSON.stringify(criteria),
    });
  }
  listSavedSearches(caseId: string): Observable<SavedSearch[]> {
    return this.http.get<SavedSearch[]>(SAVED_SEARCHES_URL, {
      params: { caseId },
    });
  }
  runSavedSearch(
    id: string,
    page = 0,
    size = 20,
  ): Observable<SearchResponse> {
    return this.http.post<SearchResponse>(
      `${SAVED_SEARCHES_URL}/${id}/run`,
      {},
      { params: this.params({ page, size }) },
    );
  }

  // ---- Ingestion -----------------------------------------------------------
  /** Submits one message. 202 only means accepted — archival is asynchronous. */
  ingestMessage(request: IngestionRequest): Observable<IngestionResponse> {
    return this.http.post<IngestionResponse>(INGESTION_URL, request);
  }

  // ---- Archive -------------------------------------------------------------
  archiveStats(): Observable<ArchiveStats> {
    return this.http.get<ArchiveStats>(`${MESSAGES_URL}/stats`);
  }

  /**
   * Resolves a producer's source id to the archived message. 404 until the
   * message has been consumed and stored, and 404 again once it is disposed
   * of — which is how a caller can watch both ends of a message's life.
   */
  getMessageBySource(sourceMessageId: string): Observable<ArchivedMessage> {
    return this.http.get<ArchivedMessage>(
      `${MESSAGES_URL}/by-source/${sourceMessageId}`,
    );
  }

  /** Deletes a message directly — refused with 409 if it is on hold (FR-4.6). */
  deleteMessage(id: string, reason = 'manual'): Observable<unknown> {
    return this.http.delete(`${MESSAGES_URL}/${id}`, { params: { reason } });
  }

  // ---- Holds ---------------------------------------------------------------
  listHolds(caseId?: string): Observable<HoldItem[]> {
    return this.http.get<HoldItem[]>(HOLDS_URL, {
      params: this.params({ caseId }),
    });
  }
  getHold(id: string): Observable<HoldItem> {
    return this.http.get<HoldItem>(`${HOLDS_URL}/${id}`);
  }
  placeHold(body: {
    caseId: string;
    custodians?: string[];
    dateFrom?: string;
    dateTo?: string;
    searchTerms?: string;
  }): Observable<HoldItem> {
    return this.http.post<HoldItem>(HOLDS_URL, body);
  }
  releaseHold(id: string, reason?: string): Observable<HoldItem> {
    return this.http.post<HoldItem>(
      `${HOLDS_URL}/${id}/release`,
      {},
      { params: this.params({ reason }) },
    );
  }
  /** Re-run a scope resolution that failed while search-service was down. */
  resolveHoldScope(id: string): Observable<HoldItem> {
    return this.http.post<HoldItem>(`${HOLDS_URL}/${id}/resolve-scope`, {});
  }
  activeHoldCount(caseId: string): Observable<number> {
    return this.http.get<number>(`${HOLDS_URL}/count`, { params: { caseId } });
  }
  /** Distinct held messages for a case, deduplicated across holds (FR-4.4). */
  heldMessageCount(caseId: string): Observable<{ heldMessages: number }> {
    return this.http.get<{ heldMessages: number }>(
      `${HOLDS_URL}/held-messages`,
      { params: { caseId } },
    );
  }

  // ---- Retention -----------------------------------------------------------
  listRetentionPolicies(): Observable<RetentionPolicy[]> {
    return this.http.get<RetentionPolicy[]>(`${RETENTION_URL}/policies`);
  }
  saveRetentionPolicy(policy: RetentionPolicy): Observable<RetentionPolicy> {
    return this.http.post<RetentionPolicy>(
      `${RETENTION_URL}/policies`,
      policy,
    );
  }
  runDisposition(): Observable<DispositionRun> {
    return this.http.post<DispositionRun>(`${RETENTION_URL}/disposition`, {});
  }
  listDispositionRuns(): Observable<DispositionRun[]> {
    return this.http.get<DispositionRun[]>(
      `${RETENTION_URL}/disposition/runs`,
    );
  }
  /** Per-message outcomes for a run; filter by SKIPPED_HELD for the FR-4.6 proof. */
  listDispositionRunItems(
    runId: string,
    outcome?: DispositionOutcome,
  ): Observable<DispositionRunItem[]> {
    return this.http.get<DispositionRunItem[]>(
      `${RETENTION_URL}/disposition/runs/${runId}/items`,
      { params: this.params({ outcome }) },
    );
  }

  // ---- Exports -------------------------------------------------------------
  listExports(caseId?: string): Observable<ExportJob[]> {
    return this.http.get<ExportJob[]>(EXPORTS_URL, {
      params: this.params({ caseId }),
    });
  }
  requestExport(body: {
    caseId: string;
    scope?: string;
    requestedBy?: string;
  }): Observable<ExportJob> {
    return this.http.post<ExportJob>(EXPORTS_URL, body);
  }
  getExport(id: string): Observable<ExportJob> {
    return this.http.get<ExportJob>(`${EXPORTS_URL}/${id}`);
  }
  exportDownloadUrl(id: string): Observable<{ url: string }> {
    return this.http.get<{ url: string }>(`${EXPORTS_URL}/${id}/download`);
  }
  verifyExport(id: string): Observable<VerificationResult> {
    return this.http.get<VerificationResult>(`${EXPORTS_URL}/${id}/verify`);
  }
  retryExport(id: string): Observable<ExportJob> {
    return this.http.post<ExportJob>(`${EXPORTS_URL}/${id}/retry`, {});
  }

  // ---- Audit ---------------------------------------------------------------
  searchAudit(filter: AuditFilter = {}): Observable<Page<AuditEntry>> {
    return this.http.get<Page<AuditEntry>>(AUDIT_URL, {
      params: this.params({ ...filter }),
    });
  }
  auditActions(): Observable<string[]> {
    return this.http.get<string[]>(`${AUDIT_URL}/actions`);
  }
}
