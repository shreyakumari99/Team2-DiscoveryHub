package com.smarsh.discoveryhub.archive.domain;

import com.smarsh.discoveryhub.events.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A durably archived message. The {@code id} is the immutable identifier
 * assigned by archive-service on first storage (FR-1.5); it never changes.
 * {@code sourceMessageId} is the idempotency key with a unique index so that
 * re-submitting the same message can never create a duplicate (FR-1.6).
 *
 * <p><b>Legal hold (FR-4.5).</b> Protection is modelled as {@code holdIds} —
 * the set of <em>active holds currently covering this message</em> — not as a
 * single boolean. Overlapping holds are therefore correct by construction:
 * releasing one hold removes only its id, and the message stays protected for
 * as long as any other hold still covers it. {@code held} is a denormalized
 * projection of {@code !holdIds.isEmpty()}, kept because it is what the delete
 * check (FR-4.6) and the search index filter read on the hot path, and because
 * it keeps documents written before holds existed readable.
 */
@Document(collection = "messages")
public record ArchivedMessage(
        @Id String id,
        @Indexed(unique = true) String sourceMessageId,
        MessageType type,
        String subject,
        String body,
        Instant timestamp,
        String sender,
        List<String> participants,
        String threadId,
        List<String> attachmentObjectKeys,
        boolean hasAttachment,
        boolean held,
        @Indexed Set<String> holdIds,
        Instant archivedAt
) {

    /**
     * Normalizes nullable collections and keeps {@code held} consistent with
     * {@code holdIds} so the invariant "held &lt;=&gt; at least one active
     * hold" cannot be violated by a caller passing the two independently.
     * Documents persisted before {@code holdIds} existed deserialize with a
     * null set; for those the stored {@code held} flag is preserved.
     */
    public ArchivedMessage {
        attachmentObjectKeys = attachmentObjectKeys == null ? List.of() : List.copyOf(attachmentObjectKeys);
        participants = participants == null ? List.of() : List.copyOf(participants);
        if (holdIds == null) {
            holdIds = Set.of();
        } else {
            holdIds = new LinkedHashSet<>(holdIds);
            held = !holdIds.isEmpty();
        }
    }

    /** Factory for a message being archived for the first time — never held. */
    public static ArchivedMessage newlyArchived(String id,
                                                String sourceMessageId,
                                                MessageType type,
                                                String subject,
                                                String body,
                                                Instant timestamp,
                                                String sender,
                                                List<String> participants,
                                                String threadId,
                                                List<String> attachmentObjectKeys,
                                                Instant archivedAt) {
        List<String> keys = attachmentObjectKeys == null ? List.of() : attachmentObjectKeys;
        return new ArchivedMessage(id, sourceMessageId, type, subject, body, timestamp, sender,
                participants, threadId, keys, !keys.isEmpty(), false, Set.of(), archivedAt);
    }
}
