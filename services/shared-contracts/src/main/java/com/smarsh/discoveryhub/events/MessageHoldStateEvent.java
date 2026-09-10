package com.smarsh.discoveryhub.events;

import java.time.Instant;
import java.util.List;

/**
 * Published by <strong>archive-service</strong> onto
 * {@link Topics#MESSAGE_HOLD_STATE} after it has applied a {@link HoldEvent}
 * to the archive, and consumed by <strong>search-service</strong> to keep the
 * per-message hold badge and the on-hold filter in sync (FR-4.4).
 *
 * <p>This event carries the <em>outcome</em>, not the intent. Archive owns the
 * overlapping-hold rule (a message is protected while any hold still covers
 * it — FR-4.5); search-service must not re-derive it from a raw PLACED or
 * RELEASED event, or the two stores would disagree whenever holds overlap.
 * Large scopes are emitted in batches, so several events may describe one hold.
 *
 * @param holdId     the hold whose application produced this state change
 * @param caseId     the case the hold belongs to
 * @param messageIds messages whose state is described by {@code held}
 * @param held       the resulting protection state for those messages
 * @param occurredAt when archive applied the change
 */
public record MessageHoldStateEvent(
        String holdId,
        String caseId,
        List<String> messageIds,
        boolean held,
        Instant occurredAt
) {
}
