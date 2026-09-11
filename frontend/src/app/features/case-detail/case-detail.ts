import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  Api,
  CaseItem,
  Custodian,
  EvidenceItem,
  HoldItem,
} from '../../core/api';

/** Case detail: lifecycle, custodians, evidence, holds (FR-2, FR-8.1). */
@Component({
  imports: [DatePipe],
  selector: 'app-case-detail',
  styleUrl: './case-detail.scss',
  templateUrl: './case-detail.html',
})
export class CaseDetail implements OnInit {
  private readonly api = inject(Api);

  /** Bound from the route param /cases/:id */
  @Input() id = '';

  protected readonly case = signal<CaseItem | null>(null);
  protected readonly evidence = signal<EvidenceItem[]>([]);
  protected readonly holds = signal<HoldItem[]>([]);
  protected readonly allCustodians = signal<Custodian[]>([]);
  protected readonly heldMessages = signal<number | null>(null);
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
      next: (e) => this.evidence.set(e),
      error: () => this.evidence.set([]),
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
