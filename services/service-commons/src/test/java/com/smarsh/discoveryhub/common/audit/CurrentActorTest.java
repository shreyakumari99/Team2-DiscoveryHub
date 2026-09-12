package com.smarsh.discoveryhub.common.audit;

import com.smarsh.discoveryhub.common.web.ActorHeaderFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who an action is attributed to (FR-7.2), and the filter that establishes it.
 *
 * <p>The platform has no login by design, so the acting investigator arrives as
 * an {@code X-Actor} header and is held in a thread-local for the duration of
 * the request. That design has one serious hazard: servlet threads are pooled,
 * so an actor left behind after a request would be attributed to whoever used
 * that thread next. These tests exist mainly to pin that it cannot happen.
 */
class CurrentActorTest {

    @AfterEach
    void clearActor() {
        CurrentActor.clear();
    }

    @Test
    void holdsAndReturnsTheCurrentActor() {
        CurrentActor.set("investigator@smarsh.com");

        assertThat(CurrentActor.get()).contains("investigator@smarsh.com");
    }

    @Test
    void isEmptyWhenNoActorHasBeenSet() {
        assertThat(CurrentActor.get()).isEmpty();
    }

    /**
     * A blank header is the same as no header. Recording an audit entry whose
     * actor is an empty string would be worse than recording the service name,
     * because it looks like a person who cannot be identified.
     */
    @Test
    void treatsBlankAsAbsent() {
        CurrentActor.set("");
        assertThat(CurrentActor.get()).isEmpty();

        CurrentActor.set("   ");
        assertThat(CurrentActor.get()).isEmpty();

        CurrentActor.set(null);
        assertThat(CurrentActor.get()).isEmpty();
    }

    @Test
    void clearingRemovesTheActor() {
        CurrentActor.set("alice@smarsh.com");

        CurrentActor.clear();

        assertThat(CurrentActor.get()).isEmpty();
    }

    /**
     * The actor is per-thread. Background work — an export job, a hold scope
     * resolution — runs on its own thread and deliberately sees no actor, so it
     * is attributed to the service instead. The audit trail should not claim a
     * user was present when they had already gone.
     */
    @Test
    void isScopedToTheCurrentThread() throws Exception {
        CurrentActor.set("investigator@smarsh.com");

        try (var executor = Executors.newSingleThreadExecutor()) {
            boolean seenOnOtherThread = executor
                    .submit(() -> CurrentActor.get().isPresent())
                    .get();
            assertThat(seenOnOtherThread).isFalse();
        }

        // ...and the calling thread still has it.
        assertThat(CurrentActor.get()).contains("investigator@smarsh.com");
    }

    /** {@code runAs} is how background work adopts an actor captured earlier. */
    @Test
    void runAsAppliesAnActorForTheDurationOfSomeWork() {
        CurrentActor.runAs("bob@smarsh.com", () ->
                assertThat(CurrentActor.get()).contains("bob@smarsh.com"));

        assertThat(CurrentActor.get()).isEmpty();
    }

    // ---- the filter ------------------------------------------------------

    @Test
    void theFilterBindsTheHeaderForTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cases");
        request.addHeader(ActorHeaderFilter.ACTOR_HEADER, "investigator@smarsh.com");

        String[] seen = new String[1];
        new ActorHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen[0] = CurrentActor.get().orElse(null));

        assertThat(seen[0]).isEqualTo("investigator@smarsh.com");
    }

    /**
     * The one that matters. Threads are pooled, so if the filter did not clear
     * the actor afterwards, the next request handled by this thread would be
     * silently attributed to the previous investigator.
     */
    @Test
    void theFilterAlwaysClearsTheActorAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cases");
        request.addHeader(ActorHeaderFilter.ACTOR_HEADER, "investigator@smarsh.com");

        new ActorHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { /* handled */ });

        assertThat(CurrentActor.get()).isEmpty();
    }

    /** Even when the request blows up, the actor must not survive it. */
    @Test
    void theFilterClearsTheActorEvenWhenHandlingFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cases");
        request.addHeader(ActorHeaderFilter.ACTOR_HEADER, "investigator@smarsh.com");
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, "irrelevant");

        try {
            new ActorHeaderFilter().doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException("handler blew up");
                    });
        } catch (Exception expected) {
            // The failure propagates; what matters is the cleanup below.
        }

        assertThat(CurrentActor.get()).isEmpty();
    }

    /** No header simply means no actor, and the request proceeds regardless. */
    @Test
    void aRequestWithoutTheHeaderHasNoActor() throws Exception {
        boolean[] ran = new boolean[1];

        new ActorHeaderFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/v1/cases"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    ran[0] = true;
                    assertThat(CurrentActor.get()).isEmpty();
                });

        assertThat(ran[0]).isTrue();
    }
}
