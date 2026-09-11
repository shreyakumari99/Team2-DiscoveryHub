import { Routes } from '@angular/router';

/**
 * Top-level routes — one per screen (FR-8.1). Each maps to a standalone feature
 * component. `case-detail` takes an id path parameter that component input
 * binding turns into an @input.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./features/dashboard/dashboard').then((m) => m.Dashboard),
    title: 'Dashboard',
  },
  {
    path: 'cases',
    loadComponent: () =>
      import('./features/case-dashboard/case-dashboard').then(
        (m) => m.CaseDashboard,
      ),
    title: 'Cases',
  },
  {
    path: 'cases/:id',
    loadComponent: () =>
      import('./features/case-detail/case-detail').then((m) => m.CaseDetail),
    title: 'Case Detail',
  },
  {
    path: 'search',
    loadComponent: () =>
      import('./features/search/search').then((m) => m.Search),
    title: 'Search',
  },
  {
    path: 'holds',
    loadComponent: () =>
      import('./features/hold-management/hold-management').then(
        (m) => m.HoldManagement,
      ),
    title: 'Legal Holds',
  },
  {
    path: 'retention',
    loadComponent: () =>
      import('./features/retention/retention').then((m) => m.Retention),
    title: 'Retention',
  },
  {
    path: 'exports',
    loadComponent: () =>
      import('./features/export-jobs/export-jobs').then((m) => m.ExportJobs),
    title: 'Export Jobs',
  },
  {
    path: 'audit',
    loadComponent: () =>
      import('./features/audit-trail/audit-trail').then((m) => m.AuditTrail),
    title: 'Audit Trail',
  },
  { path: '**', redirectTo: 'dashboard' },
];
