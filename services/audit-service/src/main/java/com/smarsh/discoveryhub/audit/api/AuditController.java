package com.smarsh.discoveryhub.audit.api;

import com.smarsh.discoveryhub.audit.domain.AuditLogEntry;
import com.smarsh.discoveryhub.audit.domain.AuditLogRepository;
import com.smarsh.discoveryhub.audit.domain.AuditLogSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Audit trail REST API (FR-7.4).
 *
 * <pre>
 *   GET /api/v1/audit?caseId=&amp;action=&amp;actor=&amp;entityType=&amp;entityId=&amp;from=&amp;to=&amp;page=&amp;size=
 *   GET /api/v1/audit/actions   distinct action names, for the UI filter
 *   GET /api/v1/audit/summary   counts for the dashboard
 * </pre>
 *
 * <p><b>Read-only by design (FR-7.3):</b> there is no POST, PUT, PATCH or
 * DELETE here, and there never should be. Entries arrive only via the Kafka
 * consumer, and the database role additionally has UPDATE and DELETE revoked
 * on the table, so the trail cannot be rewritten even by accident.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1_000;

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Filtered, paginated view of the trail. Every filter is optional; with
     * none supplied this is the system-wide view.
     *
     * @param from inclusive lower bound on entry timestamp
     * @param to   inclusive upper bound on entry timestamp
     */
    @GetMapping
    public Page<AuditLogEntry> search(@RequestParam(required = false) String caseId,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String actor,
                                      @RequestParam(required = false) String entityType,
                                      @RequestParam(required = false) String entityId,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return repository.findAll(
                AuditLogSpecifications.matching(caseId, action, actor, entityType, entityId, from, to),
                PageRequest.of(pageOrDefault(page), sizeOrDefault(size),
                        Sort.by(Sort.Direction.DESC, "timestamp")));
    }

    @GetMapping("/actions")
    public List<String> actions() {
        return repository.findDistinctActions();
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return Map.of("totalEntries", repository.count());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int pageOrDefault(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private static int sizeOrDefault(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
