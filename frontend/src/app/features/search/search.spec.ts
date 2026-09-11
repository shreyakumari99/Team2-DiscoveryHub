import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Search } from './search';

/**
 * Search screen behaviour (FR-3): filters reaching the backend intact, and the
 * two bulk add-to-case paths — this page, and every matching result.
 */
describe('Search', () => {
  let http: HttpTestingController;

  const emptyResults = {
    query: '',
    page: 0,
    size: 20,
    totalHits: 0,
    totalPages: 0,
    hits: [],
  };

  /** Answers the reference-data calls the component makes on construction. */
  function flushReferenceData(): void {
    http.expectOne('/api/v1/custodians').flush([
      { id: 'c1', name: 'Alice Chen', email: 'alice@smarsh.com' },
      { id: 'c2', name: 'Bob Ray', email: 'bob@smarsh.com' },
    ]);
    http.expectOne('/api/v1/cases').flush([
      { id: 'case-1', name: 'Q3', state: 'ACTIVE', custodianIds: [] },
      { id: 'case-2', name: 'Old', state: 'CLOSED', custodianIds: [] },
    ]);
  }

  function createComponent() {
    const fixture = TestBed.createComponent(Search);
    flushReferenceData();
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Search],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends every active filter to the backend', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.query.set('bonus');
    search.typeFilter.set('EMAIL');
    search.onHoldOnly.set(true);
    search.hasAttachmentOnly.set(true);
    search.dateFrom.set('2024-01-01');
    search.dateTo.set('2024-06-30');
    search.selectedCustodians.set(['alice@smarsh.com']);
    search.run();

    const req = http.expectOne((r) => r.url === '/api/v1/search');
    const params = req.request.params;
    expect(params.get('query')).toBe('bonus');
    expect(params.getAll('types')).toEqual(['EMAIL']);
    expect(params.get('onHold')).toBe('true');
    expect(params.get('hasAttachment')).toBe('true');
    expect(params.getAll('custodians')).toEqual(['alice@smarsh.com']);
    // The upper bound covers the whole of the chosen day.
    expect(params.get('dateFrom')).toContain('2024-01-01');
    expect(params.get('dateTo')).toContain('2024-06-30T23:59:59');
    req.flush(emptyResults);
  });

  it('omits filters that are switched off', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.query.set('bonus');
    search.run();

    const req = http.expectOne((r) => r.url === '/api/v1/search');
    expect(req.request.params.has('onHold')).toBe(false);
    expect(req.request.params.has('hasAttachment')).toBe(false);
    expect(req.request.params.has('types')).toBe(false);
    req.flush(emptyResults);
  });

  /** FR-3.6: add the messages shown on this page. */
  it('adds the current page of results to a case', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.response.set({
      ...emptyResults,
      totalHits: 2,
      hits: [
        { id: 'm1', type: 'EMAIL', sender: 'a', timestamp: '', hasAttachment: false, held: false, score: 1 },
        { id: 'm2', type: 'EMAIL', sender: 'b', timestamp: '', hasAttachment: false, held: false, score: 1 },
      ],
    });
    search.addToCaseId.set('case-1');
    search.addPageToCase();

    const req = http.expectOne('/api/v1/cases/case-1/evidence/bulk');
    expect(req.request.body.messageIds).toEqual(['m1', 'm2']);
    req.flush([]);
  });

  /**
   * FR-3.6 stretch: "add all results" must add every match, not the twenty on
   * screen — so it asks the backend to resolve the full id set first.
   */
  it('adds every matching result, not just the visible page', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.query.set('bonus');
    search.addToCaseId.set('case-1');
    search.response.set({
      ...emptyResults,
      totalHits: 500,
      hits: [{ id: 'm1', type: 'EMAIL', sender: 'a', timestamp: '', hasAttachment: false, held: false, score: 1 }],
    });
    search.addAllResultsToCase();

    const idsReq = http.expectOne((r) => r.url === '/api/v1/search/ids');
    // Paging must not narrow the set being resolved.
    expect(idsReq.request.params.has('page')).toBe(false);
    idsReq.flush(['m1', 'm2', 'm3']);

    const bulkReq = http.expectOne('/api/v1/cases/case-1/evidence/bulk');
    expect(bulkReq.request.body.messageIds).toEqual(['m1', 'm2', 'm3']);
    bulkReq.flush([]);
  });

  it('refuses to add results without a case, rather than silently doing nothing', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.response.set({ ...emptyResults, hits: [{ id: 'm1' }] });
    search.addPageToCase();

    expect(search.error()).toContain('case');
  });

  /** FR-2.5: a closed case is read-only, so it is not offered as a target. */
  it('only offers cases that can still receive evidence', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    expect(search.openCases().map((c: { id: string }) => c.id)).toEqual([
      'case-1',
    ]);
  });

  /** FR-3.5: re-running a saved search replays it server-side. */
  it('re-runs a saved search and reflects its filters back into the form', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.runSavedSearch({
      id: 'ss-1',
      caseId: 'case-1',
      name: 'Q3 bonus',
      queryJson: JSON.stringify({ query: 'bonus', types: ['EMAIL'] }),
    });

    const req = http.expectOne((r) =>
      r.url.startsWith('/api/v1/saved-searches/ss-1/run'),
    );
    expect(req.request.method).toBe('POST');
    req.flush({ ...emptyResults, query: 'bonus', totalHits: 3 });

    expect(search.query()).toBe('bonus');
    expect(search.typeFilter()).toBe('EMAIL');
    expect(search.response().totalHits).toBe(3);
  });

  it('clears every filter on reset', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.query.set('bonus');
    search.onHoldOnly.set(true);
    search.selectedCustodians.set(['alice@smarsh.com']);
    search.page.set(3);
    search.resetFilters();

    expect(search.query()).toBe('');
    expect(search.onHoldOnly()).toBe(false);
    expect(search.selectedCustodians()).toEqual([]);
    expect(search.page()).toBe(0);
  });

  it('toggles custodians without duplicating them', () => {
    const fixture = createComponent();
    const search = fixture.componentInstance as any;

    search.toggleCustodian('alice@smarsh.com', true);
    search.toggleCustodian('alice@smarsh.com', true);
    expect(search.selectedCustodians()).toEqual(['alice@smarsh.com']);

    search.toggleCustodian('alice@smarsh.com', false);
    expect(search.selectedCustodians()).toEqual([]);
  });
});
