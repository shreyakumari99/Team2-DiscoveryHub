import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { interval } from 'rxjs';
import {
  Api,
  DispositionOutcome,
  DispositionRun,
  DispositionRunItem,
  RetentionPolicy,
} from '../../core/api';
import { DemoStage, DemoDispositionService } from '../../core/demo-disposition';

/**
 * Retention policies and disposition runs (FR-5), and the screen where the
 * legal-hold guarantee is demonstrated.
 *
 * <p>This is the FR-4.6 proof in the UI: set a retention period short enough
 * that messages expire, run a disposition, and read back exactly which
 * messages were deleted and which were preserved because a hold covered them.
 * "412 deleted, 38 skipped" is a summary; the per-message list is the evidence.
 */
@Component({
  imports: [DatePipe],
  selector: 'app-retention',
  styleUrl: './retention.scss',
  templateUrl: './retention.html',
})
export class Retention implements OnInit {
  private static readonly POLL_INTERVAL_MS = 3000;

  /**
   * How long to keep waiting for a run that outlived its HTTP request. Matches
   * the proxy read timeout, past which something is genuinely wrong.
   */
  private static readonly MAX_WAIT_MS = 30 * 60 * 1000;

  private readonly api = inject(Api);
  private readonly destroyRef = inject(DestroyRef);
  private readonly demoService = inject(DemoDispositionService);

  protected readonly policies = signal<RetentionPolicy[]>([]);
  protected readonly runs = signal<DispositionRun[]>([]);
  protected readonly loading = signal(true);
  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  /** Set while a run is known to be in progress but its request has gone. */
  protected readonly pending = signal<string | null>(null);

  /** Per-message outcomes for whichever run is expanded. */
  protected readonly selectedRunId = signal<string | null>(null);
  protected readonly runItems = signal<DispositionRunItem[]>([]);
  protected readonly itemFilter = signal<DispositionOutcome | ''>('');

  protected readonly policyForm = signal<{
    type: 'EMAIL' | 'CHAT';
    retentionMinutes: number;
  }>({ type: 'EMAIL', retentionMinutes: 5 });

  /**
   * The demo disposition: one generated message, and only that message.
   *
   * <p>Its state is read from a root service rather than held here, because a
   * lazy route component is destroyed the moment you navigate away and the
   * countdown has to outlive that — see {@link DemoDispositionService}.
   */
  protected readonly demoType = signal<'EMAIL' | 'CHAT'>('EMAIL');
  protected readonly demo = this.demoService.state;
  protected readonly demoRemainingSeconds = this.demoService.remainingSeconds;

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.listRetentionPolicies().subscribe({
      next: (p) => {
        this.policies.set(p);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        this.loading.set(false);
      },
    });
    this.api.listDispositionRuns().subscribe({
      next: (r) => this.setRuns(r),
      error: () => this.runs.set([]),
    });
  }

  /** Newest run first — the one you just triggered is the one you want. */
  private setRuns(runs: DispositionRun[]): void {
    this.runs.set(
      [...runs].sort((a, b) => b.startedAt.localeCompare(a.startedAt)),
    );
  }

  /** FR-5.1: retention is configurable per communication type, in minutes. */
  savePolicy(): void {
    const policy = this.policyForm();
    this.api.saveRetentionPolicy(policy).subscribe({
      next: () => {
        this.notice.set(
          `${policy.type} retention set to ${policy.retentionMinutes} minutes.`,
        );
        this.refresh();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  /** FR-5.2: run a disposition pass now, rather than waiting for the schedule. */
  runDisposition(): void {
    this.running.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.pending.set(null);

    // Which runs existed before we started, so we can spot the new one.
    const knownRunIds = new Set(this.runs().map((r) => r.id));

    this.api.runDisposition().subscribe({
      next: (run) => {
        this.running.set(false);
        this.notice.set(this.completionNotice(run));
        this.refresh();
        this.showItems(run);
      },
      error: (e) => {
        if (this.isInconclusive(e)) {
          this.awaitRunAfterTimeout(knownRunIds);
          return;
        }
        this.running.set(false);
        this.error.set(this.messageOf(e));
      },
    });
  }

  /**
   * Disposing of a large corpus can take longer than the proxy's read timeout,
   * which drops the connection and surfaces as a gateway error. The deletion
   * carries on server-side regardless, so this is "still running", not
   * "failed" — keep the button busy and poll the runs list until the new run
   * appears.
   *
   * <p>There is no progress to report while we wait: RetentionService is
   * `@Transactional`, so the run row, its per-message items and its counts all
   * become visible to other connections in one go, at commit.
   */
  private awaitRunAfterTimeout(knownRunIds: Set<string>): void {
    this.pending.set(
      'Still running. Deleting a large corpus takes longer than the request ' +
        'is allowed to stay open, so the result is being waited for — this ' +
        'page will update on its own when the run finishes.',
    );

    const startedWaitingAt = Date.now();
    const poll = interval(Retention.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.api.listDispositionRuns().subscribe({
          next: (runs) => {
            const fresh = runs.find((r) => !knownRunIds.has(r.id));
            if (fresh) {
              poll.unsubscribe();
              this.running.set(false);
              this.pending.set(null);
              this.setRuns(runs);
              this.notice.set(this.completionNotice(fresh));
              this.showItems(fresh);
            } else if (Date.now() - startedWaitingAt > Retention.MAX_WAIT_MS) {
              poll.unsubscribe();
              this.running.set(false);
              this.pending.set(null);
              this.error.set(
                'The disposition run has not been recorded yet. It may still ' +
                  'be in progress — check the service logs, and refresh this ' +
                  'page to pick the run up when it lands.',
              );
            }
          },
          // A failed poll is not a failed run; try again on the next tick.
          error: () => undefined,
        });
      });
  }

  // ---- Demo disposition ----------------------------------------------------

  /**
   * Starts a demo disposition on the period this type's policy configures.
   *
   * <p>The work itself belongs to {@link DemoDispositionService}: it has to
   * carry on while the presenter is on another screen, and anything owned by
   * this component stops the moment the route changes.
   */
  runDemoDisposition(): void {
    const type = this.demoType();
    const policy = this.policies().find((p) => p.type === type);
    if (!policy) {
      this.error.set(
        `No ${type} retention policy is configured, so there is no period for ` +
          'the demo to wait out. Save one above first.',
      );
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.demoService.start(type, policy.retentionMinutes);
  }

  cancelDemo(): void {
    this.demoService.cancel();
  }

  /** True while a demo is mid-flight, whichever screen started it. */
  protected demoRunning(): boolean {
    return this.demoService.running();
  }

  /** mm:ss left before the demo message passes its retention cut-off. */
  protected demoCountdown(): string {
    const total = this.demoRemainingSeconds();
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  }

  protected demoStageLabel(stage: DemoStage): string {
    switch (stage) {
      case 'INGESTING':
        return 'Generating the message…';
      case 'WAITING':
        return 'Archived — waiting out the retention period';
      case 'DELETING':
        return 'Retention period elapsed — deleting';
      case 'DELETED':
        return 'Disposed of';
      case 'HELD':
        return 'Preserved by a legal hold';
      case 'FAILED':
        return 'Failed';
    }
  }

  protected demoStageBadge(stage: DemoStage): string {
    switch (stage) {
      case 'DELETED':
        return 'closed';
      case 'HELD':
        return 'held';
      case 'FAILED':
        return 'fail';
      default:
        return 'run live';
    }
  }

  /**
   * Whether an error means "the request did not complete" rather than "the
   * work failed". A gateway timeout or a dropped connection says nothing about
   * what the server is doing; a 4xx or a 500 from the service itself does.
   */
  private isInconclusive(error: unknown): boolean {
    const status = (error as { status?: number })?.status;
    return status === 0 || status === 408 || status === 504;
  }

  private completionNotice(run: DispositionRun): string {
    return (
      `Disposition complete: ${run.deletedCount} deleted, ` +
      `${run.skippedHeldCount} preserved by a legal hold.`
    );
  }

  /** FR-5.3: the per-message record of what a run did. */
  showItems(run: DispositionRun): void {
    if (this.selectedRunId() === run.id) {
      this.selectedRunId.set(null);
      this.runItems.set([]);
      return;
    }
    this.selectedRunId.set(run.id);
    this.loadItems();
  }

  filterItems(outcome: DispositionOutcome | ''): void {
    this.itemFilter.set(outcome);
    this.loadItems();
  }

  private loadItems(): void {
    const runId = this.selectedRunId();
    if (!runId) return;
    this.api
      .listDispositionRunItems(runId, this.itemFilter() || undefined)
      .subscribe({
        next: (items) => this.runItems.set(items),
        error: (e) => this.error.set(this.messageOf(e)),
      });
  }

  outcomeBadge(outcome: DispositionOutcome): string {
    switch (outcome) {
      case 'DELETED':
        return 'closed';
      case 'SKIPPED_HELD':
        return 'held';
      case 'ERROR':
        return 'fail';
      default:
        return '';
    }
  }

  private messageOf(error: unknown): string {
    const e = error as { error?: { message?: string }; message?: string };
    return e?.error?.message ?? e?.message ?? 'Request failed';
  }
}
