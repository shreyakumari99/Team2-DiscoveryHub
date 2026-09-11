import { Component, inject, signal, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { Api, CaseItem } from '../../core/api';

/** Case list + create form (FR-2.1). */
@Component({
  imports: [RouterLink, DatePipe],
  selector: 'app-case-dashboard',
  styleUrl: './case-dashboard.scss',
  templateUrl: './case-dashboard.html',
})
export class CaseDashboard implements OnInit {
  private readonly api = inject(Api);

  protected readonly cases = signal<CaseItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly form = signal({
    name: '',
    description: '',
    matterType: 'INVESTIGATION' as CaseItem['matterType'],
    owner: 'investigator',
  });

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.listCases().subscribe({
      next: (c) => {
        this.cases.set(c);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(e.message);
        this.loading.set(false);
      },
    });
  }

  create(): void {
    const f = this.form();
    if (!f.name) return;
    this.api.createCase(f).subscribe({
      next: () => {
        this.form.set({
          name: '',
          description: '',
          matterType: 'INVESTIGATION',
          owner: 'investigator',
        });
        this.refresh();
      },
      error: (e) => this.error.set(e.message),
    });
  }

  stateBadgeClass(s: CaseItem['state']): string {
    return s === 'DRAFT'
      ? 'draft'
      : s === 'ACTIVE'
        ? 'active'
        : s === 'UNDER_REVIEW'
          ? 'review'
          : 'closed';
  }
}
