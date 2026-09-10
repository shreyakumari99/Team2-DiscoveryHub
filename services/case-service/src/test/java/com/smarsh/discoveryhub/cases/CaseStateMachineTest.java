package com.smarsh.discoveryhub.cases;

import com.smarsh.discoveryhub.cases.api.CaseStateMachine;
import com.smarsh.discoveryhub.cases.api.IllegalCaseTransitionException;
import com.smarsh.discoveryhub.events.CaseState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the case lifecycle state machine (FR-2.2). No Spring
 * context required.
 */
class CaseStateMachineTest {

    @Test
    void allowsForwardTransitions() {
        assertTrue(CaseStateMachine.canTransition(CaseState.DRAFT, CaseState.ACTIVE));
        assertTrue(CaseStateMachine.canTransition(CaseState.ACTIVE, CaseState.UNDER_REVIEW));
        assertTrue(CaseStateMachine.canTransition(CaseState.UNDER_REVIEW, CaseState.CLOSED));
    }

    @Test
    void rejectsBackwardAndSkippedTransitions() {
        assertFalse(CaseStateMachine.canTransition(CaseState.ACTIVE, CaseState.DRAFT));
        assertFalse(CaseStateMachine.canTransition(CaseState.DRAFT, CaseState.CLOSED)); // skip
        assertFalse(CaseStateMachine.canTransition(CaseState.UNDER_REVIEW, CaseState.ACTIVE));
    }

    @Test
    void closedIsTerminal() {
        for (CaseState target : CaseState.values()) {
            assertFalse(CaseStateMachine.canTransition(CaseState.CLOSED, target));
        }
    }

    @Test
    void checkThrowsForInvalidTransition() {
        assertThrows(IllegalCaseTransitionException.class,
                () -> CaseStateMachine.check(CaseState.DRAFT, CaseState.CLOSED));
    }
}
