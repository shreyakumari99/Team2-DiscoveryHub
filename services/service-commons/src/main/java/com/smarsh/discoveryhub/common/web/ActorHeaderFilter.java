package com.smarsh.discoveryhub.common.web;

import com.smarsh.discoveryhub.common.audit.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the {@code X-Actor} request header to {@link CurrentActor} for the
 * duration of the request, so every audit entry raised while handling it names
 * the investigator responsible (FR-7.2).
 *
 * <p>The header is also forwarded on inter-service calls, so an action that
 * fans out (a search that resolves a hold scope, an export that reads a case)
 * is attributed to the same person end to end rather than to whichever service
 * happened to make the call.
 */
public class ActorHeaderFilter extends OncePerRequestFilter {

    public static final String ACTOR_HEADER = "X-Actor";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CurrentActor.set(request.getHeader(ACTOR_HEADER));
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Threads are pooled: never let an actor leak into the next request.
            CurrentActor.clear();
        }
    }
}
