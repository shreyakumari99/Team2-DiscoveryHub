import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { actorInterceptor } from './core/actor';

/**
 * Root application config. Wires up routing (with component input binding so
 * route params land as @inputs), the global HttpClient used by the core API
 * service, and browser error listeners.
 *
 * The actor interceptor stamps every backend call with the current
 * investigator, so the audit trail records who did what (FR-7.2).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([actorInterceptor])),
  ],
};
