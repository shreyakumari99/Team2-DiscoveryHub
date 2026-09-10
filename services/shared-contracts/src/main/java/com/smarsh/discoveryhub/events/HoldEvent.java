package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

/**
 * Published by <strong>hold-retention-service</strong> onto
 * {@link Topics#HOLD_EVENTS}. Consumed by <strong>archive-service</strong>,
 * which applies/releases the deletion protection on the affected messages.
 *
 * <p>For a PLACED event, {@code messageIds} is the already-resolved scope
 * (the hold service called search-service to find matching ids) so archive
 * can flip the held flag synchronously per message without re-querying.
 *
 * <p>For a RELEASED event {@code messageIds} is empty and must stay unused:
 * the messages a hold protects are exactly those recorded against it in the
 * archive, so re-sending a resolved list would risk releasing a scope that
 * differs from the one actually applied. Archive derives it instead.
 *
 * @param holdId      the hold
 * @param caseId      the case the hold belongs to
 * @param type        PLACED or RELEASED
 * @param custodians  custodians in scope
 * @param dateFrom    scope start (inclusive), nullable
 * @param dateTo      scope end (inclusive), nullable
 * @param searchTerms optional free-text scope, nullable
 * @param messageIds  resolved message ids in scope (PLACED); always empty for RELEASED
 * @param occurredAt  when the event happened
 */
public record HoldEvent(
        String holdId,
        String caseId,
        String type,
        List<String> custodians,
        Instant dateFrom,
        Instant dateTo,
        String searchTerms,
        List<String> messageIds,
        Instant occurredAt
) {
}
