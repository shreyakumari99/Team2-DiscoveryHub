import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { CaseDetail } from './case-detail';

const CASE_ID = 'case-1';

/**
 * The case detail screen, and specifically FR-4.4: "the UI must show hold
 * status on every message and the total count of held items per case."
 *
 * <p>The case-level total comes straight from hold-retention-service. The
 * per-message state is the harder half: case-service stores only a message id,
 * so the flag has to be read from archive-service, which owns it. These tests
 * pin that lookup, and — just as importantly — pin that a failure to perform it
 * is reported as "unknown" rather than silently rendered as "not held".
 */
describe('CaseDetail', () => {
  let http: HttpTestingController;

  const caseRecord = {
    id: CASE_ID,
    name: 'Project Atlas',
    matterType: 'INVESTIGATION',
    owner: 'investigator@smarsh.com',
    state: 'ACTIVE',
    createdAt: '2026-01-01T10:00:00Z',
    custodianIds: [],
  };

  const evidence = [
    { id: 'ev-1', caseId: CASE_ID, messageId: 'msg-held', addedAt: '2026-01-01T11:00:00Z', source: 'search' },
    { id: 'ev-2', caseId: CASE_ID, messageId: 'msg-free', addedAt: '2026-01-01T11:01:00Z', source: 'search' },
  ];

  /**
   * Creates the component and answers every call it makes on init except the
   * archive hold-state lookup, which each test drives itself.
   */
  function createComponent(evidenceItems: unknown[] = evidence) {
    const fixture = TestBed.createComponent(CaseDetail);
    fixture.componentRef.setInput('id', CASE_ID);
    fixture.detectChanges();

    // Matched by predicate on `url`, because the string form of expectOne
    // compares against the URL *including* its query parameters.
    http.expectOne(`/api/v1/cases/${CASE_ID}`).flush(caseRecord);
    http.expectOne(`/api/v1/cases/${CASE_ID}/evidence`).flush(evidenceItems);
    http.expectOne((r) => r.url === '/api/v1/holds').flush([]);
    http
      .expectOne((r) => r.url === '/api/v1/holds/held-messages')
      .flush({ heldMessages: 1 });
    http.expectOne('/api/v1/custodians').flush([]);
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CaseDetail],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('marks evidence the archive reports as held, and leaves the rest free', () => {
    const fixture = createComponent();
    const detail = fixture.componentInstance as any;

    // The ids are read from the archive, which owns the flag — not derived
    // from this case's own holds.
    const lookup = http.expectOne((r) => r.url === '/api/v1/messages');
    expect(lookup.request.params.getAll('ids')).toEqual(['msg-held', 'msg-free']);
    lookup.flush([
      { id: 'msg-held', held: true },
      { id: 'msg-free', held: false },
    ]);

    expect(detail.holdStateOf('msg-held')).toBe('HELD');
    expect(detail.holdStateOf('msg-free')).toBe('NOT_HELD');
    http.verify();
  });

  /**
   * A message frozen by a hold belonging to a *different* case is still
   * protected from deletion. Because the flag is read from the archive rather
   * than inferred from this case's holds, that case is covered — this case has
   * no holds of its own at all here.
   */
  it('shows a message held by another case as held', () => {
    const fixture = createComponent();
    const detail = fixture.componentInstance as any;

    http
      .expectOne((r) => r.url === '/api/v1/messages')
      .flush([
        { id: 'msg-held', held: true },
        { id: 'msg-free', held: false },
      ]);

    expect(detail.holds()).toEqual([]);
    expect(detail.holdStateOf('msg-held')).toBe('HELD');
    http.verify();
  });

  /**
   * The important negative case. Rendering "not held" for a message we could
   * not ask about would be a false statement about deletion protection.
   */
  it('reports unknown rather than unheld when the archive cannot be reached', () => {
    const fixture = createComponent();
    const detail = fixture.componentInstance as any;

    http
      .expectOne((r) => r.url === '/api/v1/messages')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });

    expect(detail.holdStateOf('msg-held')).toBe('UNKNOWN');
    expect(detail.holdStateOf('msg-free')).toBe('UNKNOWN');
    // A failed lookup must not break the rest of the page.
    expect(detail.error()).toBeNull();
    http.verify();
  });

  /** The archive requires the `ids` parameter, so an empty case must not ask. */
  it('does not call the archive when the case has no evidence', () => {
    createComponent([]);

    http.expectNone((r) => r.url === '/api/v1/messages');
    http.verify();
  });
});
