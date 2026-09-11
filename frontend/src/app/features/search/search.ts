import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  Api,
  CaseItem,
  Custodian,
  SavedSearch,
  SearchCriteria,
  SearchHit,
  SearchResponse,
} from '../../core/api';

/**
 * Full-text search with filters, highlighting, pagination, saved searches and
 * bulk add-to-case (FR-3).
 */
@Component({
  imports: [DatePipe],
  selector: 'app-search',
  styleUrl: './search.scss',
  templateUrl: './search.html',
})
export class Search {
  private readonly api = inject(Api);

  protected readonly query = signal('');
  protected readonly typeFilter = signal<'BOTH' | 'EMAIL' | 'CHAT'>('BOTH');
  protected readonly onHoldOnly = signal(false);
  protected readonly hasAttachmentOnly = signal(false);
  protected readonly dateFrom = signal('');
  protected readonly dateTo = signal('');
  protected readonly selectedCustodians = signal<string[]>([]);
  protected readonly sort = signal<'relevance' | 'date'>('relevance');

  protected readonly response = signal<SearchResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  protected readonly custodians = signal<Custodian[]>([]);
  protected readonly cases = signal<CaseItem[]>([]);
  protected readonly savedSearches = signal<SavedSearch[]>([]);

  protected readonly addToCaseId = signal('');
  protected readonly saveAsName = signal('');
  protected readonly page = signal(0);
  protected readonly pageSize = 20;

  /** Cases that can still receive evidence — a closed case is read-only (FR-2.5). */
  protected readonly openCases = computed(() =>
    this.cases().filter((c) => c.state !== 'CLOSED'),
  );

  constructor() {
    this.api.listCustodians().subscribe({
      next: (c) => this.custodians.set(c),
      error: () => this.custodians.set([]),
    });
    this.api.listCases().subscribe({
      next: (c) => this.cases.set(c),
      error: () => this.cases.set([]),
    });
  }

  /** The current filter set, shared by search, "all ids" and save. */
  private criteria(withPaging = true): SearchCriteria {
    return {
      query: this.query() || undefined,
      types: this.typeFilter() === 'BOTH' ? undefined : [this.typeFilter()],
      custodians: this.selectedCustodians().length
        ? this.selectedCustodians()
        : undefined,
      onHold: this.onHoldOnly() ? true : undefined,
      hasAttachment: this.hasAttachmentOnly() ? true : undefined,
      dateFrom: this.toInstant(this.dateFrom()),
      dateTo: this.toInstant(this.dateTo(), true),
      sort: this.sort(),
      ...(withPaging ? { page: this.page(), size: this.pageSize } : {}),
    };
  }

  /**
   * The date inputs are plain dates; the backend filters on instants. The
   * upper bound is pushed to end-of-day so "to 5 March" includes 5 March.
   */
  private toInstant(value: string, endOfDay = false): string | undefined {
    if (!value) return undefined;
    return endOfDay
      ? new Date(`${value}T23:59:59.999Z`).toISOString()
      : new Date(`${value}T00:00:00.000Z`).toISOString();
  }

  run(): void {
    this.loading.set(true);
    this.error.set(null);
    this.notice.set(null);
    this.api.search(this.criteria()).subscribe({
      next: (r) => {
        this.response.set(r);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        this.loading.set(false);
      },
    });
  }

  searchOnEnter(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      this.page.set(0);
      this.run();
    }
  }

  resetFilters(): void {
    this.query.set('');
    this.typeFilter.set('BOTH');
    this.onHoldOnly.set(false);
    this.hasAttachmentOnly.set(false);
    this.dateFrom.set('');
    this.dateTo.set('');
    this.selectedCustodians.set([]);
    this.sort.set('relevance');
    this.page.set(0);
  }

  toggleCustodian(email: string, checked: boolean): void {
    this.selectedCustodians.update((current) =>
      checked
        ? [...new Set([...current, email])]
        : current.filter((c) => c !== email),
    );
  }

  nextPage(): void {
    this.page.update((p) => p + 1);
    this.run();
  }

  prevPage(): void {
    this.page.update((p) => Math.max(0, p - 1));
    this.run();
  }

  /** FR-3.6: add the messages on this page. */
  addPageToCase(): void {
    const hits = this.response()?.hits ?? [];
    this.addToCase(
      hits.map((h) => h.id),
      `page of ${hits.length}`,
    );
  }

  /**
   * FR-3.6 stretch: add every match, not just the page. The ids come from the
   * backend rather than from walking pages client-side, so the set added is
   * exactly the set the filters describe.
   */
  addAllResultsToCase(): void {
    const caseId = this.addToCaseId();
    if (!caseId) {
      this.error.set('Choose a case first.');
      return;
    }
    this.loading.set(true);
    this.api.searchIds(this.criteria(false)).subscribe({
      next: (ids) => {
        this.loading.set(false);
        this.addToCase(ids, `all ${ids.length}`);
      },
      error: (e) => {
        this.loading.set(false);
        this.error.set(this.messageOf(e));
      },
    });
  }

  private addToCase(messageIds: string[], description: string): void {
    const caseId = this.addToCaseId();
    if (!caseId) {
      this.error.set('Choose a case first.');
      return;
    }
    if (messageIds.length === 0) {
      this.error.set('There are no results to add.');
      return;
    }
    this.error.set(null);
    this.api.addEvidenceBulk(caseId, messageIds).subscribe({
      next: () =>
        this.notice.set(
          `Added ${description} messages to case as evidence.`,
        ),
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  /** FR-3.5: save this query against a case so it can be re-run later. */
  saveSearch(): void {
    const caseId = this.addToCaseId();
    const name = this.saveAsName().trim();
    if (!caseId || !name) {
      this.error.set('Choose a case and give the search a name.');
      return;
    }
    this.api.saveSearch(caseId, name, this.criteria(false)).subscribe({
      next: () => {
        this.notice.set(`Saved search "${name}".`);
        this.saveAsName.set('');
        this.loadSavedSearches();
      },
      error: (e) => this.error.set(this.messageOf(e)),
    });
  }

  loadSavedSearches(): void {
    const caseId = this.addToCaseId();
    if (!caseId) {
      this.savedSearches.set([]);
      return;
    }
    this.api.listSavedSearches(caseId).subscribe({
      next: (s) => this.savedSearches.set(s),
      error: () => this.savedSearches.set([]),
    });
  }

  onCaseChange(caseId: string): void {
    this.addToCaseId.set(caseId);
    this.loadSavedSearches();
  }

  /** FR-3.5: re-run a saved search server-side. */
  runSavedSearch(saved: SavedSearch): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.runSavedSearch(saved.id, 0, this.pageSize).subscribe({
      next: (r) => {
        this.applyCriteria(saved.queryJson);
        this.page.set(0);
        this.response.set(r);
        this.loading.set(false);
        this.notice.set(`Re-ran saved search "${saved.name}".`);
      },
      error: (e) => {
        this.error.set(this.messageOf(e));
        this.loading.set(false);
      },
    });
  }

  /** Reflect a saved search's filters back into the form so they are visible. */
  private applyCriteria(queryJson: string): void {
    try {
      const c = JSON.parse(queryJson) as SearchCriteria;
      this.query.set(c.query ?? '');
      this.typeFilter.set(
        c.types?.length === 1 ? (c.types[0] as 'EMAIL' | 'CHAT') : 'BOTH',
      );
      this.selectedCustodians.set(c.custodians ?? []);
      this.onHoldOnly.set(c.onHold === true);
      this.hasAttachmentOnly.set(c.hasAttachment === true);
      this.sort.set((c.sort as 'relevance' | 'date') ?? 'relevance');
      this.dateFrom.set(c.dateFrom ? c.dateFrom.substring(0, 10) : '');
      this.dateTo.set(c.dateTo ? c.dateTo.substring(0, 10) : '');
    } catch {
      // The results are still valid even if the form cannot be repopulated.
    }
  }

  /** Render a highlighted fragment (Elasticsearch escapes the surrounding text). */
  highlightHtml(fragments: string[] | undefined): string {
    return fragments?.[0] ?? '';
  }

  bodyHighlight(h: SearchHit): string {
    return this.highlightHtml(h.highlight?.['body']);
  }

  subjectHighlight(h: SearchHit): string {
    return this.highlightHtml(h.highlight?.['subject']);
  }

  /** Prefer the backend's message over Angular's generic "Http failure" text. */
  private messageOf(error: unknown): string {
    const e = error as { error?: { message?: string }; message?: string };
    return e?.error?.message ?? e?.message ?? 'Request failed';
  }
}
