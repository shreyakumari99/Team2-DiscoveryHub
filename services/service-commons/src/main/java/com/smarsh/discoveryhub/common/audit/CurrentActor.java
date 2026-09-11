package com.smarsh.discoveryhub.common.audit;

import java.util.Optional;

/**
 * The investigator responsible for the request currently being handled
 * (FR-7.2 "each audit entry: ... actor ...").
 *
 * <p>The platform has no login by design ("no login is required" — the spec's
 * single-persona model), so the acting user arrives as an {@code X-Actor}
 * header set by the UI. Holding it here rather than passing an {@code actor}
 * argument through every service method keeps the domain signatures about the
 * domain, and means a newly added action cannot forget to record who did it.
 *
 * <p>Scoped to the handling thread and always cleared by
 * {@link com.smarsh.discoveryhub.common.web.ActorHeaderFilter}, so an actor
 * can never leak from one request into the next on a pooled thread. Work that
 * continues on a background thread (an export job, a hold scope resolution)
 * deliberately sees no actor and is attributed to the service instead — the
 * audit trail should not claim a user was present when they had already gone.
 */
public final class CurrentActor {

    private static final ThreadLocal<String> ACTOR = new ThreadLocal<>();

    private CurrentActor() {
    }

    public static void set(String actor) {
        if (actor == null || actor.isBlank()) {
            ACTOR.remove();
        } else {
            ACTOR.set(actor);
        }
    }

    public static Optional<String> get() {
        return Optional.ofNullable(ACTOR.get());
    }

    public static void clear() {
        ACTOR.remove();
    }

    /**
     * Run {@code work} attributed to {@code actor}, restoring the previous
     * value afterwards. Used when a background task genuinely acts on behalf
     * of the user who scheduled it.
     */
    public static void runAs(String actor, Runnable work) {
        String previous = ACTOR.get();
        set(actor);
        try {
            work.run();
        } finally {
            set(previous);
        }
    }
}
