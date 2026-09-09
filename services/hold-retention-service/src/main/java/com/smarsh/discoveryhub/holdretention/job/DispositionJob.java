package com.smarsh.discoveryhub.holdretention.job;

import com.smarsh.discoveryhub.holdretention.api.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled disposition job (FR-5.2). Runs on a cron that defaults to once an
 * hour; for the demo, set {@code disposition.cron} to a short value like
 * {@code 0/30 * * * * *} (every 30s) to observe disposition live.
 *
 * <p>Disabled by default in tests via {@code disposition.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "disposition.enabled", havingValue = "true", matchIfMissing = true)
public class DispositionJob {

    private static final Logger log = LoggerFactory.getLogger(DispositionJob.class);

    private final RetentionService retentionService;

    public DispositionJob(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "${disposition.cron:0 0 * * * *}")
    public void run() {
        log.info("Scheduled disposition run starting");
        try {
            retentionService.runDisposition("schedule");
        } catch (Exception e) {
            log.error("Scheduled disposition run failed", e);
        }
    }
}
