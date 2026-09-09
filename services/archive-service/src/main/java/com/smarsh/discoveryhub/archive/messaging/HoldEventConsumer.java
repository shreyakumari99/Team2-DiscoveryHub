package com.smarsh.discoveryhub.archive.messaging;

import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger;
import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger.HoldChange;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.HoldEvent;
import com.smarsh.discoveryhub.events.MessageHoldStateEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Consumes {@link Topics#HOLD_EVENTS} and applies the deletion protection to
 * the archive, then announces the resulting per-message state on
 * {@link Topics#MESSAGE_HOLD_STATE} so search-service can mirror it (FR-4.4).
 *
 * <p>This class is deliberately thin: the overlapping-hold rule lives in
 * {@link LegalHoldLedger}, which is where it can be unit-tested without Kafka.
 * The consumer only translates between the event contract and the ledger.
 */
@Component
public class HoldEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HoldEventConsumer.class);
    private static final String PLACED = "PLACED";
    /** Keeps each announcement well under the broker's max message size. */
    private static final int ANNOUNCE_BATCH = 1_000;

    private final LegalHoldLedger ledger;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AuditTrail auditTrail;

    public HoldEventConsumer(LegalHoldLedger ledger,
                             KafkaTemplate<String, Object> kafkaTemplate,
                             AuditTrail auditTrail) {
        this.ledger = ledger;
        this.kafkaTemplate = kafkaTemplate;
        this.auditTrail = auditTrail;
    }

    @KafkaListener(topics = Topics.HOLD_EVENTS, groupId = "archive-service")
    public void onHoldEvent(HoldEvent event) {
        boolean placing = PLACED.equalsIgnoreCase(event.type());

        // A release carries no ids by design — the ledger knows which messages
        // the hold covers. Only a placement depends on the resolved scope.
        if (placing && (event.messageIds() == null || event.messageIds().isEmpty())) {
            log.info("HoldEvent {} PLACED with an empty scope; nothing to protect", event.holdId());
            return;
        }

        HoldChange change = placing
                ? ledger.apply(event.holdId(), event.messageIds())
                : ledger.release(event.holdId());

        announce(event, change.protectedIds(), true);
        announce(event, change.unprotectedIds(), false);

        auditTrail.record(AuditRecord.action(placing ? "HOLD_APPLIED" : "HOLD_RELEASED")
                .on("HOLD", event.holdId())
                .by("hold-retention-service")
                .inCase(event.caseId())
                .after("protected", change.protectedIds().size())
                .after("unprotected", change.unprotectedIds().size())
                .after("stillHeldByOtherHold", change.stillProtectedIds().size()));
    }

    private void announce(HoldEvent event, List<String> messageIds, boolean held) {
        for (int i = 0; i < messageIds.size(); i += ANNOUNCE_BATCH) {
            List<String> batch = messageIds.subList(i, Math.min(messageIds.size(), i + ANNOUNCE_BATCH));
            kafkaTemplate.send(Topics.MESSAGE_HOLD_STATE, event.holdId(), new MessageHoldStateEvent(
                    event.holdId(), event.caseId(), List.copyOf(batch), held, Instant.now()));
        }
    }
}
