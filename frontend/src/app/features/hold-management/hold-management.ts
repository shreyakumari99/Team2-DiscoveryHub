import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { interval } from 'rxjs';
import { Api, CaseItem, Custodian, HoldItem } from '../../core/api';

/**
 * Hold management: place, watch scope resolution, release (FR-4).
 *
 * <p>Placing a hold returns immediately and the scope is resolved in the
 * background (FR-4.3), so the list polls until every hold has a resolved
 * scope — otherwise a hold would sit at "0 messages" on screen until the user
 * happened to refresh, which looks exactly like a hold that matched nothing.
 */
@Component({
  imports: [DatePipe],
  selector: 'app-hold-management',
  styleUrl: './hold-management.scss',
  templateUrl: './hold-management.html',
})
export class HoldManagement implements OnInit {
  private static readonly POLL_INTERVAL_MS = 2000;

  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly holds = signal<HoldItem[]>([]);
  protected readonly cases = signal<CaseItem[]>([]);
  protected readonly custodians = signal<Custodian[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly filterCaseId = signal('');

  protected readonly form = signal({
    caseId: '',
    custodians: [] as string[],
    searchTerms: '',
    dateFrom: '',
    dateTo: '',
  });

  /** Holds still waiting on scope resolution — drives the poll and the badge. */
  protected readonly resolvingHolds = computed(() =>
    this.holds().filter(
      (h) => h.active && !h.scopeResolved && !h.scopeFailureReason,
    ),
  );

  protected readonly openCases = computed(() =>
    this.cases().filter((c) => c.state !== 'CLOSED'),
  );

  ngOnInit(): void {
    this.refresh();
    this.api.listCases().subscribe({
      next: (c) => this.cases.set(c),
      error: () => this.cases.set([]),
    });
    this.api.listCustodians().subscribe({
      next: (c) => this.custodians.set(c),
      error: () => this.custodians.set([]),
    });

    interval(HoldManagement.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.resolvingHolds().length > 0) {
          this.refresh(true);
        }
      });
  }

  refresh(quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.api.listHolds(this.filterCaseId() || undefined).subscribe({
      next: (h) => {
        this.holds.set(h);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        this.loading.set(false);
      },
    });
  }

  toggleCustodian(email: string, checked: boolean): void {
    this.form.update((f) => ({
      ...f,
      custodians: checked
        ? [...new Set([...f.custodians, email])]
        : f.custodians.filter((c) => c !== email),
    }));
  }

  place(): void {
    const f = this.form();
    if (!f.caseId) {
      this.error.set('Choose a case to place the hold on.');
      return;
    }
    this.error.set(null);
    this.api
      .placeHold({
        caseId: f.caseId,
        custodians: f.custodians,
        searchTerms: f.searchTerms || undefined,
        dateFrom: this.toInstant(f.dateFrom),
        dateTo: this.toInstant(f.dateTo, true),
      })
      .subscribe({
        next: () => {
          this.notice.set(
            'Hold placed. Scope is resolving in the background — the message count will appear shortly.',
          );
          this.form.set({
            caseId: '',
            custodians: [],
            searchTerms: '',
            dateFrom: '',
            dateTo: '',
          });
          this.refresh();
        },
        error: (e) => this.error.set(this.messageOf(e)),
      });
  }

  release(hold: HoldItem): void {
    this.api.releaseHold(hold.id, 'manual').subscribe({
      next: () => {
        this.notice.set(
          'Hold released. Messages still covered by another hold stay protected (FR-4.5).',
        );
        this.refresh();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  /** Re-run a scope resolution that failed, e.g. while search was restarting. */
  resolveScope(hold: HoldItem): void {
    this.api.resolveHoldScope(hold.id).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  private toInstant(value: string, endOfDay = false): string | undefined {
    if (!value) return undefined;
    return endOfDay
      ? new Date(`${value}T23:59:59.999Z`).toISOString()
      : new Date(`${value}T00:00:00.000Z`).toISOString();
  }

  private messageOf(error: unknown): string {
    const e = error as { error?: { message?: string }; message?: string };
    return e?.error?.message ?? e?.message ?? 'Request failed';
  }
}
