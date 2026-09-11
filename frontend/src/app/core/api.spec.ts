import { TestBed } from '@angular/core/testing';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Api } from './api';
import { ActorService, actorInterceptor } from './actor';

/**
 * The typed API client: that each call hits the endpoint the backend actually
 * exposes, that filters are encoded the way the services parse them, and that
 * every request carries the acting investigator (FR-7.2).
 */
describe('Api', () => {
  let api: Api;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([actorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(Api);
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(ActorService).reset();
  });

  afterEach(() => http.verify());

  it('stamps every backend call with the current investigator', () => {
    TestBed.inject(ActorService).setName('dana@smarsh.com');

    api.listCases().subscribe();

    const req = http.expectOne('/api/v1/cases');
    expect(req.request.headers.get('X-Actor')).toBe('dana@smarsh.com');
    req.flush([]);
  });

  it('omits empty filters instead of sending blanks', () => {
    api.search({ query: 'bonus', types: undefined, size: 20 }).subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/search');
    expect(req.request.params.get('query')).toBe('bonus');
    expect(req.request.params.has('types')).toBe(false);
    expect(req.request.params.get('size')).toBe('20');
    req.flush({
      query: 'bonus',
      page: 0,
      size: 20,
      totalHits: 0,
      totalPages: 0,
      hits: [],
    });
  });

  /**
   * Multi-valued filters must repeat the key, which is how Spring binds a
   * List parameter. Comma-joining would arrive as one custodian named
   * "a@x.com,b@x.com" and silently match nothing.
   */
  it('repeats the key for multi-valued filters', () => {
    api
      .search({ custodians: ['alice@smarsh.com', 'bob@smarsh.com'] })
      .subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/search');
    expect(req.request.params.getAll('custodians')).toEqual([
      'alice@smarsh.com',
      'bob@smarsh.com',
    ]);
    req.flush({
      query: '',
      page: 0,
      size: 20,
      totalHits: 0,
      totalPages: 0,
      hits: [],
    });
  });

  it('resolves every matching id for add-all-results', () => {
    api.searchIds({ query: 'bonus' }, 5000).subscribe((ids) => {
      expect(ids).toEqual(['m1', 'm2']);
    });

    const req = http.expectOne((r) => r.url === '/api/v1/search/ids');
    expect(req.request.params.get('limit')).toBe('5000');
    req.flush(['m1', 'm2']);
  });

  it('serializes a saved search as replayable criteria', () => {
    api.saveSearch('case-1', 'Q3 bonus', { query: 'bonus' }).subscribe();

    const req = http.expectOne('/api/v1/saved-searches');
    expect(req.request.body.caseId).toBe('case-1');
    expect(JSON.parse(req.request.body.queryJson).query).toBe('bonus');
    req.flush({});
  });

  it('requests a hold-scoped export with the hold in the scope expression', () => {
    api.requestExport({ caseId: 'case-1', scope: 'hold:hold-9' }).subscribe();

    const req = http.expectOne('/api/v1/exports');
    expect(req.request.body.scope).toBe('hold:hold-9');
    req.flush({});
  });

  it('verifies an export against its manifest', () => {
    api.verifyExport('job-1').subscribe((r) => expect(r.verified).toBe(true));

    const req = http.expectOne('/api/v1/exports/job-1/verify');
    expect(req.request.method).toBe('GET');
    req.flush({
      verified: true,
      itemsChecked: 4,
      contentChecksumMatches: true,
      packageChecksumMatches: true,
      problems: [],
    });
  });

  it('passes audit filters and pagination through', () => {
    api
      .searchAudit({ caseId: 'case-1', action: 'HOLD_PLACED', page: 2, size: 50 })
      .subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/audit');
    expect(req.request.params.get('caseId')).toBe('case-1');
    expect(req.request.params.get('action')).toBe('HOLD_PLACED');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ content: [], totalElements: 0, totalPages: 0, number: 2, size: 50 });
  });

  it('reads corpus counts from the archive, the system of record', () => {
    api.archiveStats().subscribe((s) => {
      expect(s.totalMessages).toBe(10000);
      expect(s.heldMessages).toBe(42);
    });

    http
      .expectOne('/api/v1/messages/stats')
      .flush({ totalMessages: 10000, heldMessages: 42 });
  });

  it('asks for the deduplicated held count for a case', () => {
    api.heldMessageCount('case-1').subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/holds/held-messages');
    expect(req.request.params.get('caseId')).toBe('case-1');
    req.flush({ heldMessages: 7 });
  });
});
