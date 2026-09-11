package com.smarsh.discoveryhub.archive.api;

import com.smarsh.discoveryhub.archive.domain.ArchivedMessage;
import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger;
import com.smarsh.discoveryhub.archive.domain.MessageRepository;
import com.smarsh.discoveryhub.archive.storage.AttachmentStore;
import com.smarsh.discoveryhub.common.audit.AuditRecord;
import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.MessageDeletedEvent;
import com.smarsh.discoveryhub.events.Topics;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Archive REST API — the durable record of every message.
 *
 * <pre>
 *   GET    /api/v1/messages/{id}                     one message
 *   GET    /api/v1/messages/by-source/{sourceId}     one message, by its producer's id
 *   GET    /api/v1/messages?ids=a,b,c                bulk fetch (used by export)
 *   GET    /api/v1/messages/{id}/attachments/{n}     raw attachment bytes (FR-6.3)
 *   GET    /api/v1/messages/stats                    corpus + hold counts (FR-8.2)
 *   POST   /api/v1/messages/held-count               distinct held count for a set of holds (FR-4.4)
 *   DELETE /api/v1/messages/{id}                     refused with 409 if held (FR-4.6)
 * </pre>
 *
 * <p>The {@code DELETE} endpoint is the sole place a message can be removed,
 * and it refuses held messages — this is the proof for FR-4.6. The
 * hold-retention disposition job calls it for each expired message; held ones
 * come back 409 and are recorded as skipped.
 */
@RestController
@RequestMapping("/api/v1/messages")
public class ArchiveController {

    private final MessageRepository repository;
    private final LegalHoldLedger holdLedger;
    private final AttachmentStore storage;
    private final AuditTrail auditTrail;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ArchiveController(MessageRepository repository,
                             LegalHoldLedger holdLedger,
                             AttachmentStore storage,
                             AuditTrail auditTrail,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.holdLedger = holdLedger;
        this.storage = storage;
        this.auditTrail = auditTrail;
        this.kafkaTemplate = kafkaTemplate;
    }

    @GetMapping("/{id}")
    public ArchivedMessage getMessage(@PathVariable String id) {
        return repository.findById(id)
                .orElseThrow(() -> new MessageNotFoundException(id));
    }

    /**
     * Look a message up by the source id its producer gave it, rather than by
     * the archive id assigned here.
     *
     * <p>Ingestion is asynchronous and answers 202 with only the source id, so
     * a caller that has just submitted a message has no way to name it
     * afterwards. This is that mapping, and a 404 means "not archived yet, or
     * no longer archived" — which is exactly what the demo disposition polls
     * for, before and after the deletion.
     */
    @GetMapping("/by-source/{sourceMessageId}")
    public ArchivedMessage getMessageBySource(@PathVariable String sourceMessageId) {
        return repository.findBySourceMessageId(sourceMessageId)
                .orElseThrow(() -> new MessageNotFoundException(sourceMessageId));
    }

    /**
     * Bulk fetch. Export packages routinely contain thousands of messages;
     * fetching them one HTTP call at a time made an export O(n) round trips
     * and turned a slow archive into a stalled export job.
     *
     * <p>Unknown ids are omitted rather than failing the batch, so a message
     * disposed of between evidence selection and export does not abort it.
     */
    @GetMapping
    public List<ArchivedMessage> getMessages(@RequestParam List<String> ids) {
        return repository.findAllById(ids);
    }

    /**
     * Stream one attachment's bytes. Export-service uses this instead of being
     * given credentials to archive's bucket, so the archive stays the only
     * component that knows where message content physically lives.
     *
     * @param index position in the message's {@code attachmentObjectKeys}
     */
    @GetMapping("/{id}/attachments/{index}")
    public ResponseEntity<byte[]> getAttachment(@PathVariable String id, @PathVariable int index) {
        ArchivedMessage message = getMessage(id);
        List<String> keys = message.attachmentObjectKeys();
        if (index < 0 || index >= keys.size()) {
            throw new MessageNotFoundException(id + " attachment " + index);
        }
        String key = keys.get(index);
        byte[] bytes = storage.getAttachment(key);
        String filename = key.substring(key.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }

    /**
     * Ids of every message currently frozen by {@code holdId} (FR-6.1).
     *
     * <p>Export-service uses this to package "a hold's full scope". It reads
     * what the hold actually froze rather than re-running the hold's original
     * search, which would resolve against today's corpus and could produce a
     * different set than the one placed under hold.
     */
    @GetMapping("/by-hold/{holdId}")
    public List<String> messagesUnderHold(@PathVariable String holdId) {
        return holdLedger.messagesCoveredBy(holdId);
    }

    /** Corpus-level counts for the dashboard (FR-8.2). */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "totalMessages", repository.count(),
                "heldMessages", holdLedger.heldCount());
    }

    public record HeldCountRequest(List<String> holdIds) {
    }

    /**
     * Distinct number of messages covered by any of {@code holdIds} (FR-4.4).
     *
     * <p>A POST because the id list can be long, and because summing each
     * hold's own message count would double-count messages that two
     * overlapping holds both cover.
     */
    @PostMapping("/held-count")
    public Map<String, Long> heldCount(@RequestBody HeldCountRequest request) {
        long count = request.holdIds() == null ? 0
                : request.holdIds().stream()
                        .flatMap(holdId -> holdLedger.messagesCoveredBy(holdId).stream())
                        .distinct()
                        .count();
        return Map.of("heldMessages", count);
    }

    /**
     * Delete a message. Refused with 409 if the message is on hold (FR-4.6).
     *
     * <p>On success, announces the deletion on {@link Topics#MESSAGE_DELETED} so
     * search-service drops the document. On refusal, the blocked attempt is
     * itself audited.
     *
     * @param reason optional - e.g. "disposition" when called by the retention job
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteMessage(@PathVariable String id,
                                             @RequestParam(required = false) String reason) {
        ArchivedMessage message = repository.findById(id)
                .orElseThrow(() -> new MessageNotFoundException(id));

        String actor = reason != null ? reason : "api";

        if (message.held()) {
            // The single most important check in the platform. The refusal is
            // recorded before it is thrown: an attempt to destroy evidence that
            // is under legal hold is exactly what a chain of custody has to be
            // able to show, and the trail would otherwise be silent about it.
            auditTrail.record(AuditRecord.action("MESSAGE_DELETE_BLOCKED")
                    .on("MESSAGE", id)
                    .by(actor)
                    .before("held", true)
                    .after("deleted", false));
            throw new MessageHeldException(id);
        }

        repository.deleteById(id);
        // Best-effort attachment cleanup; never blocks a successful delete.
        for (String key : message.attachmentObjectKeys()) {
            storage.removeAttachment(key);
        }

        // Tell search-service the message is gone. Published only after the
        // delete has actually happened, so the index is never emptied for a
        // message that survived; keyed by id so repeated deletes of the same
        // message stay ordered on one partition.
        kafkaTemplate.send(Topics.MESSAGE_DELETED, id,
                new MessageDeletedEvent(id, actor, Instant.now()));

        auditTrail.record(AuditRecord.action("MESSAGE_DELETED")
                .on("MESSAGE", id)
                .by(actor)
                .before("held", false)
                .after("deleted", true));

        return Map.of("id", id, "deleted", true, "reason", actor);
    }
}
