import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ActorService } from './core/actor';

/**
 * The application shell: navigation and the investigator identity that every
 * audit entry is attributed to (FR-8.3, FR-7.2).
 */
describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      // The shell resumes an outstanding demo disposition on startup, which
      // needs an HTTP client even when there is nothing to resume.
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    TestBed.inject(ActorService).reset();
  });

  it('creates the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('renders the brand and every navigation destination', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('.brand')?.textContent).toContain(
      'DiscoveryHub',
    );

    const labels = Array.from(compiled.querySelectorAll('.app-nav a')).map(
      (a) => a.textContent?.trim(),
    );
    // FR-8.1 requires all of these screens to be reachable.
    expect(labels).toEqual([
      'Dashboard',
      'Cases',
      'Search',
      'Legal Holds',
      'Retention',
      'Export Jobs',
      'Audit Trail',
    ]);
  });

  it('shows the current investigator and persists a change', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    fixture.componentInstance.setActor('dana@smarsh.com');
    await fixture.whenStable();

    expect(TestBed.inject(ActorService).name()).toBe('dana@smarsh.com');
  });

  it('falls back to the default investigator rather than an empty actor', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    fixture.componentInstance.setActor('   ');

    // An audit entry attributed to "" would be worse than useless.
    expect(TestBed.inject(ActorService).name()).toBe('investigator@smarsh.com');
  });
});
