import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { DemoDispositionService } from './demo-disposition';

const STORAGE_KEY = 'discoveryhub.demo-disposition';

/**
 * The demo disposition: one generated message, deleted when the retention
 * period its policy configures has elapsed.
 *
 * <p>It lives in a root service rather than on the retention screen because
 * the wait has to survive navigation — a lazy route component is destroyed as
 * soon as you switch tabs, which used to cancel the deletion silently. The
 * test that pins that behaviour is in the retention screen's own spec; these
 * cover the lifecycle itself.
 */
describe('DemoDispositionService', () => {
  let http: HttpTestingController;
  let service: DemoDispositionService;

  /**
   * The runner provides no localStorage — the same situation as private
   * browsing, which is why the service only ever touches it through guarded
   * helpers. Stub one in so the persistence behaviour can be asserted at all.
   */
  let stored: Record<string, string>;

  beforeEach(() => {
    vi.useFakeTimers();
    stored = {};
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => stored[key] ?? null,
      setItem: (key: string, value: string) => (stored[key] = value),
      removeItem: (key: string) => delete stored[key],
    });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(DemoDispositionService);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  /** Ingests the demo message and gets it archived, returning its source id. */
  function ingestAndArchive(): string {
    service.start('EMAIL', 1);

    const ingest = http.expectOne('/api/v1/ingestion');
    const sourceMessageId = ingest.request.body.sourceMessageId;
    expect(ingest.request.body.type).toBe('EMAIL');
    ingest.flush({ sourceMessageId, status: 'ACCEPTED', message: 'ok' });

    // Ingestion is asynchronous: the archive does not know the message yet.
    vi.advanceTimersByTime(2000);
    http
      .expectOne(`/api/v1/messages/by-source/${sourceMessageId}`)
      .flush(null, { status: 404, statusText: 'Not Found' });
    expect(service.state()!.stage).toBe('INGESTING');

    vi.advanceTimersByTime(2000);
    http
      .expectOne(`/api/v1/messages/by-source/${sourceMessageId}`)
      .flush({ id: 'archive-1', sourceMessageId, type: 'EMAIL' });

    return sourceMessageId;
  }

  it('waits out the policy period, then deletes only its own message', () => {
    const sourceMessageId = ingestAndArchive();

    expect(service.state()!.stage).toBe('WAITING');
    expect(service.state()!.messageId).toBe('archive-1');
    vi.advanceTimersByTime(30_000);
    expect(service.remainingSeconds()).toBeGreaterThan(0);
    http.expectNone((r) => r.method === 'DELETE');

    vi.advanceTimersByTime(30_000);
    const del = http.expectOne((r) => r.method === 'DELETE');
    expect(del.request.url).toBe('/api/v1/messages/archive-1');
    expect(del.request.params.get('reason')).toBe('demo-disposition');
    del.flush({ id: 'archive-1', deleted: true });

    // The deletion is confirmed by reading the message back, not assumed.
    http
      .expectOne(`/api/v1/messages/by-source/${sourceMessageId}`)
      .flush(null, { status: 404, statusText: 'Not Found' });

    expect(service.state()!.stage).toBe('DELETED');
    expect(service.state()!.detail).toContain('no longer holds');
    http.verify();
  });

  /** FR-4.6 still applies to the demo: a held message is not deleted. */
  it('reports a held message as preserved, not as a failure', () => {
    ingestAndArchive();
    vi.advanceTimersByTime(60_000);

    http
      .expectOne((r) => r.method === 'DELETE')
      .flush({ reason: 'LEGAL_HOLD' }, { status: 409, statusText: 'Conflict' });

    expect(service.state()!.stage).toBe('HELD');
    expect(service.state()!.detail).toContain('409');
    http.verify();
  });

  it('gives up if the message is never archived', () => {
    service.start('EMAIL', 1);
    http.expectOne('/api/v1/ingestion').flush({ status: 'ACCEPTED' });

    for (let elapsed = 0; elapsed <= 62_000; elapsed += 2000) {
      vi.advanceTimersByTime(2000);
      http
        .match((r) => r.url.startsWith('/api/v1/messages/by-source/'))
        .forEach((r) => r.flush(null, { status: 404, statusText: 'Not' }));
    }

    expect(service.state()!.stage).toBe('FAILED');
    expect(service.state()!.detail).toContain('never archived');
    http.verify();
  });

  /**
   * A reload ends the application, and with it every timer. The outstanding
   * demo is mirrored to localStorage so the deletion still happens — otherwise
   * refreshing the page mid-demo leaves a message that nothing will ever
   * collect, and the demo appears to have done nothing.
   */
  describe('across a reload', () => {
    it('persists an in-flight demo and resumes its countdown', () => {
      ingestAndArchive();
      expect(JSON.parse(stored[STORAGE_KEY]).stage).toBe('WAITING');

      // A new application: same storage, fresh service, no timers carried over.
      const resumed = freshService();
      expect(resumed.state()!.messageId).toBe('archive-1');

      vi.advanceTimersByTime(60_000);
      http.expectOne((r) => r.method === 'DELETE').flush({ deleted: true });
      expect(resumed.state()!.stage).toBe('DELETED');
    });

    it('treats an already-deleted message as done, not as an error', () => {
      ingestAndArchive();
      vi.advanceTimersByTime(60_000);
      // The delete goes out, and the reload happens before its reply lands.
      http.expectOne((r) => r.method === 'DELETE');

      const resumed = freshService();
      http
        .expectOne((r) => r.method === 'DELETE')
        .flush(null, { status: 404, statusText: 'Not Found' });

      expect(resumed.state()!.stage).toBe('DELETED');
    });

    it('does not restore a demo that has already finished', () => {
      ingestAndArchive();
      vi.advanceTimersByTime(60_000);
      http.expectOne((r) => r.method === 'DELETE').flush({ deleted: true });
      http
        .match((r) => r.url.startsWith('/api/v1/messages/by-source/'))
        .forEach((r) => r.flush(null, { status: 404, statusText: 'Not' }));

      expect(stored[STORAGE_KEY]).toBeUndefined();
      expect(freshService().state()).toBeNull();
    });
  });

  /** Builds the service the way a page load would: from storage alone. */
  function freshService(): DemoDispositionService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(DemoDispositionService);
  }
});
