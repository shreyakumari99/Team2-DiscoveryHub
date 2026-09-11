import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Retention } from './retention';
import { DemoDispositionService } from '../../core/demo-disposition';

const RUNS_URL = '/api/v1/retention/disposition/runs';
const DISPOSITION_URL = '/api/v1/retention/disposition';

/**
 * The retention screen, and specifically how it behaves when a disposition run
 * outlives its own HTTP request.
 *
 * <p>Disposition deletes synchronously, so over a large corpus the request
 * exceeds the proxy read timeout and comes back as a gateway error while the
 * deletion carries on and commits server-side. Reporting that as a failure is
 * wrong twice over: the run did not fail, and the messages really were
 * deleted. These tests pin the distinction between "the request did not
 * finish" and "the work failed".
 */
describe('Retention', () => {
  let http: HttpTestingController;

  const existingRun = {
    id: 'run-1',
    startedAt: '2026-01-01T10:00:00Z',
    finishedAt: '2026-01-01T10:00:05Z',
    trigger: 'manual',
    deletedCount: 3,
    skippedHeldCount: 0,
    notFoundCount: 0,
    errorCount: 0,
  };

  const newRun = {
    id: 'run-2',
    startedAt: '2026-01-01T11:00:00Z',
    finishedAt: '2026-01-01T11:04:00Z',
    trigger: 'manual',
    deletedCount: 412,
    skippedHeldCount: 38,
    notFoundCount: 0,
    errorCount: 0,
  };

  /** Answers the two calls the component makes on init. */
  function createComponent(policies: unknown[] = []) {
    const fixture = TestBed.createComponent(Retention);
    fixture.detectChanges();
    http.expectOne('/api/v1/retention/policies').flush(policies);
    http.expectOne(RUNS_URL).flush([existingRun]);
    return fixture;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [Retention],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not report a gateway timeout as a failed run', () => {
    const fixture = createComponent();
    const retention = fixture.componentInstance as any;

    retention.runDisposition();
    http
      .expectOne(DISPOSITION_URL)
      .flush(null, { status: 504, statusText: 'Gateway Timeout' });

    // The deletion is still going: no error, and the button stays busy.
    expect(retention.error()).toBeNull();
    expect(retention.running()).toBe(true);
    expect(retention.pending()).toContain('Still running');

    http.verify();
  });

  it('picks the run up by polling once it is committed', () => {
    const fixture = createComponent();
    const retention = fixture.componentInstance as any;

    retention.runDisposition();
    http
      .expectOne(DISPOSITION_URL)
      .flush(null, { status: 504, statusText: 'Gateway Timeout' });

    // Still inside the transaction: the run is not visible to this connection.
    vi.advanceTimersByTime(3000);
    http.expectOne(RUNS_URL).flush([existingRun]);
    expect(retention.running()).toBe(true);
    expect(retention.pending()).not.toBeNull();

    // Committed. The counts arrive all at once, as the whole run.
    vi.advanceTimersByTime(3000);
    http.expectOne(RUNS_URL).flush([existingRun, newRun]);

    expect(retention.running()).toBe(false);
    expect(retention.pending()).toBeNull();
    expect(retention.error()).toBeNull();
    expect(retention.notice()).toBe(
      'Disposition complete: 412 deleted, 38 preserved by a legal hold.',
    );
    // Newest first, and the new run's per-message record is opened (FR-5.3).
    expect(retention.runs()[0].id).toBe('run-2');
    expect(retention.selectedRunId()).toBe('run-2');
    http.expectOne(`${RUNS_URL}/run-2/items`).flush([]);

    http.verify();
  });

  it('keeps waiting when a poll itself fails', () => {
    const fixture = createComponent();
    const retention = fixture.componentInstance as any;

    retention.runDisposition();
    http
      .expectOne(DISPOSITION_URL)
      .flush(null, { status: 504, statusText: 'Gateway Timeout' });

    vi.advanceTimersByTime(3000);
    http
      .expectOne(RUNS_URL)
      .flush(null, { status: 503, statusText: 'Service Unavailable' });

    // A failed poll says nothing about the run; it must not end the wait.
    expect(retention.running()).toBe(true);
    expect(retention.error()).toBeNull();

    vi.advanceTimersByTime(3000);
    http.expectOne(RUNS_URL).flush([existingRun, newRun]);
    expect(retention.running()).toBe(false);
    expect(retention.notice()).toContain('412 deleted');
    http.expectOne(`${RUNS_URL}/run-2/items`).flush([]);

    http.verify();
  });

  it('gives up rather than waiting forever', () => {
    const fixture = createComponent();
    const retention = fixture.componentInstance as any;

    retention.runDisposition();
    http
      .expectOne(DISPOSITION_URL)
      .flush(null, { status: 504, statusText: 'Gateway Timeout' });

    // Poll for the full 30-minute budget without the run ever appearing.
    for (let elapsed = 0; elapsed <= 30 * 60 * 1000; elapsed += 3000) {
      vi.advanceTimersByTime(3000);
      http.match(RUNS_URL).forEach((r) => r.flush([existingRun]));
    }

    expect(retention.running()).toBe(false);
    expect(retention.pending()).toBeNull();
    expect(retention.error()).toContain('not been recorded yet');

    http.verify();
  });

  /**
   * The demo disposition's own lifecycle is covered in
   * `core/demo-disposition.spec.ts`. What matters here is the screen's part of
   * it: that it starts the demo on the configured period, and that leaving the
   * screen does not cancel it.
   */
  describe('demo disposition', () => {
    const emailPolicy = { type: 'EMAIL', retentionMinutes: 1 };

    /**
     * The bug this pins: every route is a lazy `loadComponent`, so navigating
     * to another tab destroys this component. While the countdown lived here
     * it was torn down with it — the deletion never happened, and the demo
     * only worked if you stared at the retention screen for the whole
     * retention period, which is exactly what a presenter cannot do.
     */
    it('keeps counting down after the screen is destroyed', () => {
      const fixture = createComponent([emailPolicy]);
      const retention = fixture.componentInstance as any;

      retention.runDemoDisposition();
      const ingest = http.expectOne('/api/v1/ingestion');
      const sourceMessageId = ingest.request.body.sourceMessageId;
      ingest.flush({ sourceMessageId, status: 'ACCEPTED' });

      vi.advanceTimersByTime(2000);
      http
        .expectOne(`/api/v1/messages/by-source/${sourceMessageId}`)
        .flush({ id: 'archive-1', sourceMessageId, type: 'EMAIL' });

      // Navigate away: the router destroys the lazily loaded component.
      fixture.destroy();

      vi.advanceTimersByTime(60_000);
      http
        .expectOne((r) => r.method === 'DELETE')
        .flush({ id: 'archive-1', deleted: true });
      http
        .match((r) => r.url.startsWith('/api/v1/messages/by-source/'))
        .forEach((r) => r.flush(null, { status: 404, statusText: 'Not Found' }));

      // The demo survives the screen, so coming back shows the outcome.
      expect(TestBed.inject(DemoDispositionService).state()!.stage).toBe(
        'DELETED',
      );
    });

    it('refuses to run without a policy for the chosen type', () => {
      const fixture = createComponent([{ type: 'CHAT', retentionMinutes: 5 }]);
      const retention = fixture.componentInstance as any;

      retention.runDemoDisposition();

      expect(retention.demo()).toBeNull();
      expect(retention.error()).toContain('No EMAIL retention policy');
      http.verify();
    });
  });

  /**
   * The whole point of the timeout special-case is that it is a special case.
   * A rejection from the service itself is a real failure and must be shown.
   */
  it('still reports a genuine server error', () => {
    const fixture = createComponent();
    const retention = fixture.componentInstance as any;

    retention.runDisposition();
    http
      .expectOne(DISPOSITION_URL)
      .flush(
        { message: 'no retention policies configured' },
        { status: 500, statusText: 'Internal Server Error' },
      );

    expect(retention.running()).toBe(false);
    expect(retention.pending()).toBeNull();
    expect(retention.error()).toBe('no retention policies configured');

    // Nothing is being polled for.
    vi.advanceTimersByTime(9000);
    http.expectNone(RUNS_URL);

    http.verify();
  });
});
