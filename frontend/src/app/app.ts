import { Component, inject, signal } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { ActorService } from './core/actor';
import { DemoDispositionService } from './core/demo-disposition';

@Component({
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  private readonly actorService = inject(ActorService);

  /**
   * Injected for its constructor alone: it picks a demo disposition left
   * outstanding by a page reload back up. A root service is only created when
   * something first asks for it, so without this the deletion would resume
   * only once the retention screen happened to be opened — which is not where
   * a presenter reloading the dashboard would be standing.
   */
  private readonly demoDisposition = inject(DemoDispositionService);

  protected readonly title = signal('DiscoveryHub');

  /**
   * The investigator using the app. There is no login by design, but FR-7.2
   * still requires each audit entry to name an actor — so whoever is working
   * identifies themselves here, and it travels with every request.
   */
  protected readonly actor = this.actorService.name;

  protected readonly nav = [
    { path: '/dashboard', label: 'Dashboard' },
    { path: '/cases', label: 'Cases' },
    { path: '/search', label: 'Search' },
    { path: '/holds', label: 'Legal Holds' },
    { path: '/retention', label: 'Retention' },
    { path: '/exports', label: 'Export Jobs' },
    { path: '/audit', label: 'Audit Trail' },
  ];

  setActor(name: string): void {
    this.actorService.setName(name);
  }
}
