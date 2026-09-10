package com.smarsh.discoveryhub.holdretention.domain;

import com.smarsh.discoveryhub.events.MessageType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A configurable retention period per communication type (FR-5.1). The period
 * is stored in minutes (not years) so the demo can use short values and observe
 * disposition within the demo window.
 */
@Entity
@Table(name = "retention_policies")
public class RetentionPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    private MessageType type;

    /** Retention in minutes. e.g. emails = 7*365*24*60 (~3.7M), chats = 3*365*24*60. */
    private long retentionMinutes;

    public RetentionPolicy() {
    }

    public RetentionPolicy(MessageType type, long retentionMinutes) {
        this.type = type;
        this.retentionMinutes = retentionMinutes;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public long getRetentionMinutes() {
        return retentionMinutes;
    }

    public void setRetentionMinutes(long retentionMinutes) {
        this.retentionMinutes = retentionMinutes;
    }
}
