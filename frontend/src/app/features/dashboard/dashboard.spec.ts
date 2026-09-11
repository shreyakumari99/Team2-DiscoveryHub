import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Dashboard } from './dashboard';

/**
 * The home dashboard counts (FR-8.2), and how the page behaves when a service
 * backing one of them is down (NFR-2).
 */
describe('Dashboard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows every required system count', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();

    http
      .expectOne('/api/v1/messages/stats')
      .flush({ totalMessages: 10000, heldMessages: 42 });
    http.expectOne('/api/v1/cases').flush([
      { id: 'c1', state: 'ACTIVE' },
      { id: 'c2', state: 'CLOSED' },
      { id: 'c3', state: 'DRAFT' },
    ]);
    http.expectOne('/api/v1/holds').flush([
      { id: 'h1', active: true },
      { id: 'h2', active: false },
    ]);
    http.expectOne('/api/v1/exports').flush([
      { id: 'e1', status: 'COMPLETED' },
      { id: 'e2', status: 'FAILED' },
      { id: 'e3', status: 'COMPLETED' },
    ]);

    const dashboard = fixture.componentInstance as any;
    expect(dashboard.totalMessages()).toBe(10000);
    expect(dashboard.heldMessages()).toBe(42);
    // Closed cases are not "active".
    expect(dashboard.activeCases()).toBe(2);
    expect(dashboard.activeHolds()).toBe(1);
    expect(dashboard.exportsCompleted()).toBe(2);
    expect(dashboard.loading()).toBe(false);
  });

  /**
   * NFR-2: one service being down must not blank the page or leave it stuck
   * loading — the counts it owns show "—" and the rest stay accurate.
   */
  it('degrades one count at a time when a service is unavailable', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();

    http
      .expectOne('/api/v1/messages/stats')
      .error(new ProgressEvent('error'), { status: 503 });
    http.expectOne('/api/v1/cases').flush([{ id: 'c1', state: 'ACTIVE' }]);
    http.expectOne('/api/v1/holds').flush([]);
    http.expectOne('/api/v1/exports').flush([]);

    const dashboard = fixture.componentInstance as any;
    expect(dashboard.totalMessages()).toBeNull();
    expect(dashboard.activeCases()).toBe(1);
    expect(dashboard.unavailable()).toContain('archive');
    expect(dashboard.hasDegradedCounts()).toBe(true);
    // The regression that mattered: the spinner must always clear.
    expect(dashboard.loading()).toBe(false);
  });

  it('clears the loading state even when every service is down', () => {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();

    for (const url of [
      '/api/v1/messages/stats',
      '/api/v1/cases',
      '/api/v1/holds',
      '/api/v1/exports',
    ]) {
      http.expectOne(url).error(new ProgressEvent('error'), { status: 503 });
    }

    const dashboard = fixture.componentInstance as any;
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.unavailable()).toHaveLength(4);
  });
});
