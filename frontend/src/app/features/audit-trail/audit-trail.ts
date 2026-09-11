import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Api, AuditEntry, AuditFilter } from '../../core/api';

/**
 * Audit trail viewer — the chain of custody (FR-7.4).
 *
 * <p>Filterable per case and across the system by action, actor, entity and
 * date range, and paginated: the audit log grows with every search and every
 * archived message, so fetching all of it is not an option.
 */
@Component({
  imports: [DatePipe],
  selector: 'app-audit-trail',
  styleUrl: './audit-trail.scss',
  templateUrl: './audit-trail.html',
})
export class AuditTrail implements OnInit {
  private readonly api = inject(Api);

  protected readonly entries = signal<AuditEntry[]>([]);
  protected readonly actions = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly filterCaseId = signal('');
  protected readonly filterAction = signal('');
  protected readonly filterActor = signal('');
  protected readonly filterEntityType = signal('');
  protected readonly dateFrom = signal('');
  protected readonly dateTo = signal('');

  protected readonly page = signal(0);
  protected readonly pageSize = 50;
  protected readonly totalEntries = signal(0);
  protected readonly totalPages = signal(0);

  /** Which entry's before/after detail is expanded. */
  protected readonly expanded = signal<string | null>(null);

  protected readonly hasFilters = computed(
    () =>
      !!this.filterCaseId() ||
      !!this.filterAction() ||
      !!this.filterActor() ||
      !!this.filterEntityType() ||
      !!this.dateFrom() ||
      !!this.dateTo(),
  );

  ngOnInit(): void {
    this.refresh();
    this.api.auditActions().subscribe({
      next: (a) => this.actions.set(a),
      error: () => this.actions.set([]),
    });
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);

    const filter: AuditFilter = {
      caseId: this.filterCaseId() || undefined,
      action: this.filterAction() || undefined,
      actor: this.filterActor() || undefined,
      entityType: this.filterEntityType() || undefined,
      from: this.toInstant(this.dateFrom()),
      to: this.toInstant(this.dateTo(), true),
      page: this.page(),
      size: this.pageSize,
    };

    this.api.searchAudit(filter).subscribe({
      next: (p) => {
        this.entries.set(p.content);
        this.totalEntries.set(p.totalElements);
        this.totalPages.set(p.totalPages);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        this.loading.set(false);
      },
    });
  }

  applyFilters(): void {
    this.page.set(0);
    this.refresh();
  }

  clearFilters(): void {
    this.filterCaseId.set('');
    this.filterAction.set('');
    this.filterActor.set('');
    this.filterEntityType.set('');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.applyFilters();
  }

  nextPage(): void {
    this.page.update((p) => p + 1);
    this.refresh();
  }

  prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
    this.refresh();
  }

  toggleDetail(entry: AuditEntry): void {
    this.expanded.update((id) => (id === entry.eventId ? null : entry.eventId));
  }

  isExpanded(entry: AuditEntry): boolean {
    return this.expanded() === entry.eventId;
  }

  hasDetail(entry: AuditEntry): boolean {
    return !!entry.beforeJson || !!entry.afterJson;
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
