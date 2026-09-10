package com.smarsh.discoveryhub.holdretention.domain;

import com.smarsh.discoveryhub.events.MessageType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The fate of a single message in a disposition run (FR-5.3).
 *
 * <p>Counts alone are not enough for a defensible disposition record: when a
 * regulator asks "which communications were destroyed, and which were
 * preserved because of the hold?", the answer has to be a list of message
 * ids, not a number. This is that list, and like the audit log it is only ever
 * inserted, never updated.
 */
@Entity
@Table(name = "disposition_run_items", indexes = {
        @Index(name = "idx_disposition_item_run", columnList = "runId"),
        @Index(name = "idx_disposition_item_outcome", columnList = "outcome")
})
public class DispositionRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String runId;
    private String messageId;

    @Enumerated(EnumType.STRING)
    private MessageType messageType;

    @Enumerated(EnumType.STRING)
    private DispositionOutcome outcome;

    /** The retention cut-off that made this message eligible. */
    private Instant retentionCutoff;

    private Instant decidedAt;

    protected DispositionRunItem() {
    }

    public DispositionRunItem(String runId, String messageId, MessageType messageType,
                              DispositionOutcome outcome, Instant retentionCutoff) {
        this.runId = runId;
        this.messageId = messageId;
        this.messageType = messageType;
        this.outcome = outcome;
        this.retentionCutoff = retentionCutoff;
        this.decidedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getMessageId() {
        return messageId;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public DispositionOutcome getOutcome() {
        return outcome;
    }

    public Instant getRetentionCutoff() {
        return retentionCutoff;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
