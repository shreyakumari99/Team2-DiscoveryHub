package com.smarsh.discoveryhub.cases.api;

import com.smarsh.discoveryhub.events.CaseState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The case lifecycle state machine (FR-2.2).
 *
 * <pre>
 *   DRAFT -&gt; ACTIVE -&gt; UNDER_REVIEW -&gt; CLOSED
 * </pre>
 * Any transition not in this table is rejected. The table is data so it is
 * trivial to extend (e.g. allow ACTIVE -&gt; CLOSED to close early) without
 * touching the service code.
 */
public final class CaseStateMachine {

    private static final Map<CaseState, Set<CaseState>> ALLOWED = new EnumMap<>(CaseState.class);

    static {
        ALLOWED.put(CaseState.DRAFT, EnumSet.of(CaseState.ACTIVE));
        ALLOWED.put(CaseState.ACTIVE, EnumSet.of(CaseState.UNDER_REVIEW));
        ALLOWED.put(CaseState.UNDER_REVIEW, EnumSet.of(CaseState.CLOSED));
        ALLOWED.put(CaseState.CLOSED, EnumSet.noneOf(CaseState.class)); // terminal
    }

    private CaseStateMachine() {
    }

    public static boolean canTransition(CaseState from, CaseState to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(CaseState.class)).contains(to);
    }

    public static void check(CaseState from, CaseState to) {
        if (!canTransition(from, to)) {
            throw new IllegalCaseTransitionException("(unknown)", from, to);
        }
    }
}
