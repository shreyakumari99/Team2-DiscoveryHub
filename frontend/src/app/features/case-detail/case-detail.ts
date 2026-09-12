import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import {
  Api,
  CaseItem,
  Custodian,
  EvidenceItem,
  HoldItem,
} from '../../core/api';

/** Whether a given evidence item is frozen, free, or could not be determined. */
export type EvidenceHoldState = 'HELD' | 'NOT_HELD' | 'UNKNOWN';

/** Case detail: lifecycle, custodians, evidence, holds (FR-2, FR-8.1). */
@Component({
  imports: [DatePipe],
  selector: 'app-case-detail',
  styleUrl: './case-detail.scss',
  templateUrl: './case-detail.html',
})
export class CaseDetail implements OnInit {
  /**
   * Ids per hold-state lookup. Keeps the query string well inside URL limits
   * on a case holding thousands of evidence items, matching the batch size
   * export-service uses against the same endpoint.
   */
  private static readonly HELD_LOOKUP_BATCH = 100;

  private readonly api = inject(Api);

  /** Bound from the route param /cases/:id */
  @Input() id = '';

  protected readonly case = signal<CaseItem | null>(null);
  protected readonly evidence = signal<EvidenceItem[]>([]);
  protected readonly holds = signal<HoldItem[]>([]);
  protected readonly allCustodians = signal<Custodian[]>([]);
  protected readonly heldMessages = signal<number | null>(null);

  /** Archive ids of this case's evidence that is currently frozen (FR-4.4). */
  protected readonly heldMessageIds = signal<Set<string>>(new Set());

  /** Set when the archive could not be asked, so the UI can say "unknown". */
  protected readonly heldStateUnavailable = signal(false);

  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly newMessageId = signal('');
  protected readonly custodianToAdd = signal('');

  /** A closed case is read-only: no new evidence, holds or exports (FR-2.5). */
  protected readonly readOnly = computed(() => this.case()?.state === 'CLOSED');

  /** Custodians already attached, resolved to full records for display. */
  protected readonly caseCustodians = computed(() => {
    const ids = this.case()?.custodianIds ?? [];
    return ids.map(
      (id) =>
        this.allCustodians().find((c) => c.id === id || c.email === id) ?? {
          id,
          name: id,
          email: id,
        },
    );
  });

  /** Custodians not yet on this case, for the add dropdown. */
  protected readonly availableCustodians = computed(() => {
    const attached = new Set(this.case()?.custodianIds ?? []);
    return this.allCustodians().filter(
      (c) => !attached.has(c.id) && !attached.has(c.email),
    );
  });

  ngOnInit(): void {
    this.load();
    this.api.listCustodians().subscribe({
      next: (c) => this.allCustodians.set(c),
      error: () => this.allCustodians.set([]),
    });
  }

  load(): void {
    this.api.getCase(this.id).subscribe({
      next: (c) => this.case.set(c),
      error: (e) => this.error.set(this.messageOf(e)),
    });
    this.api.listEvidence(this.id).subscribe({
      next: (e) => {
        this.evidence.set(e);
        this.refreshHeldState(e);
      },
      error: () => {
        this.evidence.set([]);
        this.refreshHeldState([]);
      },
    });
    this.api.listHolds(this.id).subscribe({
      next: (h) => this.holds.set(h),
      error: () => this.holds.set([]),
    });
    // FR-4.4: total held items for the case, deduplicated across holds.
    this.api.heldMessageCount(this.id).subscribe({
      next: (r) => this.heldMessages.set(r.heldMessages),
      error: () => this.heldMessages.set(null),
    });
  }

  /**
   * FR-4.4: hold status on *every* message, not only the case-level total.
   *
   * <p>The flag is read from archive-service, which owns it. case-service
   * stores nothing but the message id, and deriving "held" from this case's own
   * holds would under-report: a message frozen by a hold on a different case is
   * still protected from deletion, and labelling it unheld here would
   * misrepresent exactly the guarantee this screen exists to show.
   */
  private refreshHeldState(items: EvidenceItem[]): void {
    this.heldStateUnavailable.set(false);

    const ids = [...new Set(items.map((i) => i.messageId))];
    if (ids.length === 0) {
      this.heldMessageIds.set(new Set());
      return;
    }

    const batches: string[][] = [];
    for (let i = 0; i < ids.length; i += CaseDetail.HELD_LOOKUP_BATCH) {
      batches.push(ids.slice(i, i + CaseDetail.HELD_LOOKUP_BATCH));
    }

    forkJoin(batches.map((batch) => this.api.getMessages(batch))).subscribe({
      next: (responses) => {
        const held = new Set<string>();
        for (const messages of responses) {
          for (const message of messages) {
            if (message.held) {
              held.add(message.id);
            }
          }
        }
        this.heldMessageIds.set(held);
      },
      // Rendering "not held" for a message we could not ask about would be a
      // false statement about deletion protection, so the column says as much.
      error: () => {
        this.heldMessageIds.set(new Set());
        this.heldStateUnavailable.set(true);
      },
    });
  }

  /** Hold state of one evidence row, for the badge in the evidence table. */
  protected holdStateOf(messageId: string): EvidenceHoldState {
    if (this.heldStateUnavailable()) {
      return 'UNKNOWN';
    }
    return this.heldMessageIds().has(messageId) ? 'HELD' : 'NOT_HELD';
  }

  transition(to: CaseItem['state']): void {
    this.api.transitionCase(this.id, to).subscribe({
      next: (c) => {
        this.case.set(c);
        this.notice.set(
          to === 'CLOSED'
            ? 'Case closed. It is now read-only, and its holds have been released (FR-2.5, FR-4.5).'
            : `Case moved to ${to}.`,
        );
        this.load();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  /** FR-2.3: attach a custodian whose communications are in scope. */
  addCustodian(): void {
    const custodianId = this.custodianToAdd();
    if (!custodianId) return;
    this.api.addCustodians(this.id, [custodianId]).subscribe({
      next: (c) => {
        this.case.set(c);
        this.custodianToAdd.set('');
        this.notice.set('Custodian added to the case.');
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  addEvidence(): void {
    const messageId = this.newMessageId();
    if (!messageId) return;
    this.api.addEvidence(this.id, messageId).subscribe({
      next: (e) => {
        this.evidence.set(e);
        this.newMessageId.set('');
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  removeEvidence(item: EvidenceItem): void {
    this.api.removeEvidence(this.id, item.id).subscribe({
      next: () => this.load(),
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  stateBadgeClass(state: CaseItem['state'] | undefined): string {
    switch (state) {
      case 'ACTIVE':
        return 'active';
      case 'UNDER_REVIEW':
        return 'review';
      case 'CLOSED':
        return 'closed';
      default:
        return 'draft';
    }
  }

  /** The lifecycle is Draft → Active → Under Review → Closed (FR-2.2). */
  nextStates(): CaseItem['state'][] {
    switch (this.case()?.state) {
      case 'DRAFT':
        return ['ACTIVE'];
      case 'ACTIVE':
        return ['UNDER_REVIEW'];
      case 'UNDER_REVIEW':
        return ['CLOSED'];
      default:
        return [];
    }
  }

  private messageOf(error: unknown): string {
    const e = error as { error?: { message?: string }; message?: string };
    return e?.error?.message ?? e?.message ?? 'Request failed';
  }
}
