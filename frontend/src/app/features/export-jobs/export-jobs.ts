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
import {
  Api,
  CaseItem,
  ExportJob,
  HoldItem,
  VerificationResult,
} from '../../core/api';

/**
 * Export jobs: request, watch progress live, download, verify and retry (FR-6).
 *
 * <p>Job status is polled while anything is in flight (FR-8.1 "export jobs
 * with live status"). Export work happens on a background thread in the
 * service, so without polling a job would sit at QUEUED on screen until the
 * user thought to press refresh.
 */
@Component({
  imports: [DatePipe],
  selector: 'app-export-jobs',
  styleUrl: './export-jobs.scss',
  templateUrl: './export-jobs.html',
})
export class ExportJobs implements OnInit {
  private static readonly POLL_INTERVAL_MS = 2000;

  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly jobs = signal<ExportJob[]>([]);
  protected readonly cases = signal<CaseItem[]>([]);
  protected readonly holds = signal<HoldItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly filterCaseId = signal('');

  protected readonly form = signal({ caseId: '', scope: 'evidence' });
  protected readonly verification = signal<
    Record<string, VerificationResult>
  >({});

  /** True while any job is still running — drives the "live" indicator. */
  protected readonly hasActiveJobs = computed(() =>
    this.jobs().some((j) => j.status === 'QUEUED' || j.status === 'RUNNING'),
  );

  protected readonly openCases = computed(() =>
    this.cases().filter((c) => c.state !== 'CLOSED'),
  );

  /** Active holds on the selected case, offered as export scopes (FR-6.1). */
  protected readonly holdsForSelectedCase = computed(() =>
    this.holds().filter(
      (h) => h.active && h.caseId === this.form().caseId,
    ),
  );

  ngOnInit(): void {
    this.refresh();
    this.api.listCases().subscribe({
      next: (c) => this.cases.set(c),
      error: () => this.cases.set([]),
    });
    this.api.listHolds().subscribe({
      next: (h) => this.holds.set(h),
      error: () => this.holds.set([]),
    });

    // Poll only while something is actually in flight, so an idle screen is
    // not generating traffic forever.
    interval(ExportJobs.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.hasActiveJobs()) {
          this.refresh(true);
        }
      });
  }

  /** @param quiet true for background polls, so the list does not flicker */
  refresh(quiet = false): void {
    if (!quiet) {
      this.loading.set(true);
    }
    this.api.listExports(this.filterCaseId() || undefined).subscribe({
      next: (jobs) => {
        this.jobs.set(jobs);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        // Always clear the spinner, including on failure — otherwise the
        // screen is stuck on "Loading…" whenever the service is down.
        this.loading.set(false);
      },
    });
  }

  request(): void {
    const { caseId, scope } = this.form();
    if (!caseId) {
      this.error.set('Choose a case to export.');
      return;
    }
    this.error.set(null);
    this.api.requestExport({ caseId, scope }).subscribe({
      next: (job) => {
        this.notice.set(
          `Export ${job.id.slice(0, 8)} queued — status updates below.`,
        );
        this.form.set({ caseId: '', scope: 'evidence' });
        this.refresh();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  download(job: ExportJob): void {
    if (job.status !== 'COMPLETED') return;
    this.api.exportDownloadUrl(job.id).subscribe({
      next: (r) => window.open(r.url, '_blank'),
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  /** FR-6.5: recompute checksums against the manifest on demand. */
  verify(job: ExportJob): void {
    if (job.status !== 'COMPLETED') return;
    this.api.verifyExport(job.id).subscribe({
      next: (result) =>
        this.verification.update((all) => ({ ...all, [job.id]: result })),
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  verificationFor(job: ExportJob): VerificationResult | undefined {
    return this.verification()[job.id];
  }

  /** FR-6.6: only a failed job may be retried. */
  retry(job: ExportJob): void {
    if (job.status !== 'FAILED') return;
    this.api.retryExport(job.id).subscribe({
      next: (retried) => {
        this.notice.set(`Retrying as new job ${retried.id.slice(0, 8)}.`);
        this.refresh();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  statusBadge(status: ExportJob['status']): string {
    if (status === 'COMPLETED') return 'ok';
    if (status === 'FAILED') return 'fail';
    return 'run';
  }

  private messageOf(error: unknown): string {
    const e = error as { error?: { message?: string }; message?: string };
    return e?.error?.message ?? e?.message ?? 'Request failed';
  }
}
