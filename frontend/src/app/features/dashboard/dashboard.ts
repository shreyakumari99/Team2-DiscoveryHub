import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { Api } from '../../core/api';

/**
 * Home dashboard with system counts: total messages, messages under hold,
 * active cases, active holds and completed exports (FR-8.2).
 *
 * <p>Each count is fetched independently and degrades to "—" on its own if
 * that service is unavailable, so one service being down leaves the rest of
 * the dashboard readable rather than blanking the page (NFR-2).
 */
@Component({
  imports: [RouterLink],
  selector: 'app-dashboard',
  styleUrl: './dashboard.scss',
  templateUrl: './dashboard.html',
})
export class Dashboard implements OnInit {
  private readonly api = inject(Api);

  protected readonly totalMessages = signal<number | null>(null);
  protected readonly heldMessages = signal<number | null>(null);
  protected readonly activeCases = signal<number | null>(null);
  protected readonly activeHolds = signal<number | null>(null);
  protected readonly exportsCompleted = signal<number | null>(null);
  protected readonly loading = signal(true);

  /** Which counts could not be loaded, so the page can say so plainly. */
  protected readonly unavailable = signal<string[]>([]);

  protected readonly hasDegradedCounts = computed(
    () => this.unavailable().length > 0,
  );

  ngOnInit(): void {
    // forkJoin so "loading" ends when every call has settled. Previously it
    // was tied to one request completing, and an error on that request left
    // the spinner up forever.
    forkJoin({
      stats: this.api.archiveStats().pipe(catchError(() => of(null))),
      cases: this.api.listCases().pipe(catchError(() => of(null))),
      holds: this.api.listHolds().pipe(catchError(() => of(null))),
      exports: this.api.listExports().pipe(catchError(() => of(null))),
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe(({ stats, cases, holds, exports }) => {
        const missing: string[] = [];

        if (stats) {
          this.totalMessages.set(stats.totalMessages);
          this.heldMessages.set(stats.heldMessages);
        } else {
          missing.push('archive');
        }

        if (cases) {
          this.activeCases.set(cases.filter((c) => c.state !== 'CLOSED').length);
        } else {
          missing.push('cases');
        }

        if (holds) {
          this.activeHolds.set(holds.filter((h) => h.active).length);
        } else {
          missing.push('holds');
        }

        if (exports) {
          this.exportsCompleted.set(
            exports.filter((e) => e.status === 'COMPLETED').length,
          );
        } else {
          missing.push('exports');
        }

        this.unavailable.set(missing);
      });
  }
}
