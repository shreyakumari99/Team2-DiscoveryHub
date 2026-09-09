package com.smarsh.discoveryhub.holdretention.messaging;

import com.smarsh.discoveryhub.events.CaseEvent;
import com.smarsh.discoveryhub.events.CaseState;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.holdretention.api.HoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link Topics#CASE_EVENTS}. When a case is CLOSED, automatically
 * releases all of its active holds (FR-4.5 — releasing a hold when a case
 * closes). Other case event types are logged for traceability.
 */
@Component
public class CaseEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(CaseEventConsumer.class);

    private final HoldService holdService;

    public CaseEventConsumer(HoldService holdService) {
        this.holdService = holdService;
    }

    @KafkaListener(topics = Topics.CASE_EVENTS, groupId = "hold-retention-service")
    public void onCaseEvent(CaseEvent event) {
        log.info("CaseEvent caseId={} type={} state={}", event.caseId(), event.type(), event.state());
        if ("CLOSED".equals(event.type()) || event.state() == CaseState.CLOSED) {
            holdService.releaseHoldsForCase(event.caseId(), "case-closed");
        }
    }
}
