import { Injectable, signal } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';

const STORAGE_KEY = 'discoveryhub.actor';
const DEFAULT_ACTOR = 'investigator@smarsh.com';

/**
 * The acting investigator, held in module state so the interceptor and the
 * service always agree.
 *
 * localStorage is only ever touched through the guarded helpers below: it is
 * unavailable in private browsing, under server-side rendering, and in a bare
 * test environment, and an exception there would take down every HTTP call the
 * interceptor touches. Persistence is a convenience; the in-memory value is the
 * source of truth.
 */
let currentActor = DEFAULT_ACTOR;

function readStoredActor(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

function writeStoredActor(actor: string): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, actor);
  } catch {
    // Persistence is best-effort; the session still works without it.
  }
}

currentActor = readStoredActor() ?? DEFAULT_ACTOR;

/**
 * Who is using the application.
 *
 * The platform has no login by design — the spec describes a single
 * Investigator / Compliance Officer persona and says the app opens directly to
 * the working screens. But FR-7.2 still requires every audit entry to name an
 * actor, and "case-api" is not an answer anyone can act on. So the investigator
 * identifies themselves once and that name travels with every request.
 */
@Injectable({ providedIn: 'root' })
export class ActorService {
  readonly name = signal<string>(currentActor);

  setName(name: string): void {
    const trimmed = name.trim() || DEFAULT_ACTOR;
    currentActor = trimmed;
    this.name.set(trimmed);
    writeStoredActor(trimmed);
  }

  /** Resets to the default. Used by tests to isolate from each other. */
  reset(): void {
    currentActor = DEFAULT_ACTOR;
    this.name.set(DEFAULT_ACTOR);
    writeStoredActor(DEFAULT_ACTOR);
  }
}

/**
 * Attaches the current investigator to every backend call as `X-Actor`.
 *
 * Doing it here rather than at each call site means a newly added request
 * cannot forget to identify its actor, and the services forward the header on
 * to each other, so one action stays attributable end to end.
 */
export const actorInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-Actor': currentActor } }));
};
