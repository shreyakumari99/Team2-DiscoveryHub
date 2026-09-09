package com.smarsh.discoveryhub.holdretention.api;

import com.smarsh.discoveryhub.holdretention.domain.DispositionOutcome;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRun;
import com.smarsh.discoveryhub.holdretention.domain.DispositionRunItem;
import com.smarsh.discoveryhub.holdretention.domain.HoldEntity;
import com.smarsh.discoveryhub.holdretention.domain.RetentionPolicy;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Hold &amp; retention REST API (FR-4, FR-5).
 *
 * <pre>
 *   POST   /api/v1/holds                        place a hold; returns 202, scope resolves async
 *   GET    /api/v1/holds?caseId=                list holds, optionally per case
 *   GET    /api/v1/holds/{id}                   one hold, including scope-resolution status
 *   POST   /api/v1/holds/{id}/release           release a hold
 *   POST   /api/v1/holds/{id}/resolve-scope     re-run a scope resolution that failed
 *   GET    /api/v1/holds/count?caseId=          active hold count for a case
 *   GET    /api/v1/holds/held-messages?caseId=  distinct held messages for a case (FR-4.4)
 *   GET    /api/v1/retention/policies           list retention policies
 *   POST   /api/v1/retention/policies           create/update a policy (FR-5.1)
 *   POST   /api/v1/retention/disposition        trigger a disposition run on demand
 *   GET    /api/v1/retention/disposition/runs   past runs (FR-5.3)
 *   GET    /api/v1/retention/disposition/runs/{id}/items   per-message outcomes (FR-5.3)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class HoldRetentionController {

    private final HoldService holdService;
    private final RetentionService retentionService;

    public HoldRetentionController(HoldService holdService, RetentionService retentionService) {
        this.holdService = holdService;
        this.retentionService = retentionService;
    }

    /**
     * 202 Accepted, not 200: the hold exists, but its scope is still being
     * resolved in the background (FR-4.3). Saying "OK" would imply the
     * messages are already frozen.
     */
    @PostMapping("/holds")
    public ResponseEntity<HoldEntity> placeHold(@Valid @RequestBody PlaceHoldRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(holdService.placeHold(request));
    }

    @GetMapping("/holds")
    public List<HoldEntity> listHolds(@RequestParam(required = false) String caseId) {
        return holdService.listHolds(caseId);
    }

    @GetMapping("/holds/{id}")
    public HoldEntity getHold(@PathVariable String id) {
        return holdService.getHold(id);
    }

    @PostMapping("/holds/{id}/release")
    public HoldEntity releaseHold(@PathVariable String id,
                                  @RequestParam(required = false) String reason) {
        return holdService.releaseHold(id, reason);
    }

    /**
     * Re-run scope resolution for a hold whose first attempt failed (for
     * example because search-service was restarting). Without this a hold
     * could stay active but protect nothing, with no way to fix it.
     */
    @PostMapping("/holds/{id}/resolve-scope")
    public ResponseEntity<HoldEntity> resolveScope(@PathVariable String id) {
        holdService.resolveScopeAndPublish(id);
        return ResponseEntity.accepted().body(holdService.getHold(id));
    }

    @GetMapping("/holds/count")
    public long activeHoldCount(@RequestParam String caseId) {
        return holdService.activeHoldCount(caseId);
    }

    /** FR-4.4: the total count of held items for a case, deduplicated across overlapping holds. */
    @GetMapping("/holds/held-messages")
    public Map<String, Long> heldMessageCount(@RequestParam String caseId) {
        return Map.of("heldMessages", holdService.heldMessageCount(caseId));
    }

    @GetMapping("/retention/policies")
    public List<RetentionPolicy> listPolicies() {
        return retentionService.listPolicies();
    }

    @PostMapping("/retention/policies")
    public RetentionPolicy savePolicy(@RequestBody RetentionPolicy policy) {
        return retentionService.savePolicy(policy);
    }

    @PostMapping("/retention/disposition")
    public DispositionRun runDisposition() {
        return retentionService.runDisposition("manual");
    }

    @GetMapping("/retention/disposition/runs")
    public List<DispositionRun> listRuns() {
        return retentionService.listRuns();
    }

    /**
     * The per-message record of a run (FR-5.3). Filter by {@code outcome} —
     * {@code SKIPPED_HELD} is the list that proves the legal hold blocked
     * deletion, message by message.
     */
    @GetMapping("/retention/disposition/runs/{runId}/items")
    public List<DispositionRunItem> runItems(@PathVariable String runId,
                                             @RequestParam(required = false) DispositionOutcome outcome) {
        return retentionService.runItems(runId, outcome);
    }
}
