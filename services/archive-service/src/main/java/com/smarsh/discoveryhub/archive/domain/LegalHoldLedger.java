package com.smarsh.discoveryhub.archive.domain;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Owns the legal-hold protection state of archived messages (FR-4.2, FR-4.5).
 *
 * <p>This is the only place in the platform that decides whether a message is
 * protected from deletion, which keeps the guarantee auditable and testable in
 * one unit rather than scattered across consumers. The Kafka consumer is a
 * thin adapter that translates an event into a call on this ledger, and
 * search-service does not re-derive the rule — it is told the outcome.
 *
 * <p><b>Overlapping holds.</b> Each message carries the set of active hold ids
 * covering it. Placing a hold adds its id ({@code $addToSet}); releasing pulls
 * it ({@code $pull}). A message loses protection only when that set becomes
 * empty, so two holds covering the same message behave correctly: releasing
 * one leaves the message protected by the other. The denormalized
 * {@code held} boolean is recomputed from the set, never set independently.
 */
@Component
public class LegalHoldLedger {

    private static final Logger log = LoggerFactory.getLogger(LegalHoldLedger.class);

    /** Cap on ids per {@code $in} clause, so a corpus-wide hold stays within Mongo's document limits. */
    private static final int BATCH = 1_000;

    private static final String COLLECTION = "messages";
    private static final String ID = "_id";
    private static final String HELD = "held";
    private static final String HOLD_IDS = "holdIds";
    /** Matches documents whose holdIds array has at least one element. */
    private static final String HOLD_IDS_FIRST = "holdIds.0";

    private final MongoTemplate mongoTemplate;

    public LegalHoldLedger(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * The messages whose protection state actually changed.
     *
     * @param protectedIds messages that became (or remained newly) protected
     * @param unprotectedIds messages that lost all protection
     * @param stillProtectedIds messages that kept protection because another
     *                          overlapping hold still covers them (FR-4.5)
     */
    public record HoldChange(List<String> protectedIds,
                             List<String> unprotectedIds,
                             List<String> stillProtectedIds) {

        static HoldChange none() {
            return new HoldChange(List.of(), List.of(), List.of());
        }
    }

    /**
     * Place {@code holdId} over the given messages. Idempotent: re-delivering
     * the same Kafka event adds nothing, because {@code $addToSet} on an
     * already-present id is a no-op.
     */
    public HoldChange apply(String holdId, Collection<String> messageIds) {
        Set<String> ids = distinct(messageIds);
        if (ids.isEmpty()) {
            return HoldChange.none();
        }
        Update update = new Update().addToSet(HOLD_IDS, holdId).set(HELD, true);
        for (List<String> batch : batches(ids)) {
            mongoTemplate.updateMulti(Query.query(Criteria.where(ID).in(batch)), update, ArchivedMessage.class);
        }
        log.info("Hold {} applied to {} messages", holdId, ids.size());
        return new HoldChange(List.copyOf(ids), List.of(), List.of());
    }

    /**
     * Release {@code holdId} everywhere it is recorded.
     *
     * <p>The scope is read from the ledger rather than from the event payload:
     * the messages a hold protects are exactly those carrying its id, so a
     * release needs no resolved id list from the producer and cannot drift
     * from what was actually applied.
     */
    public HoldChange release(String holdId) {
        List<String> covered = messagesCoveredBy(holdId);
        if (covered.isEmpty()) {
            log.info("Hold {} released but covered no messages", holdId);
            return HoldChange.none();
        }

        mongoTemplate.updateMulti(Query.query(Criteria.where(HOLD_IDS).is(holdId)),
                new Update().pull(HOLD_IDS, holdId), ArchivedMessage.class);

        Set<String> stillProtected = idsWithRemainingHolds(covered);
        List<String> unprotected = covered.stream().filter(id -> !stillProtected.contains(id)).toList();
        for (List<String> batch : batches(unprotected)) {
            mongoTemplate.updateMulti(Query.query(Criteria.where(ID).in(batch)),
                    new Update().set(HELD, false), ArchivedMessage.class);
        }

        log.info("Hold {} released from {} messages; {} lost protection, {} still covered by another hold",
                holdId, covered.size(), unprotected.size(), stillProtected.size());
        return new HoldChange(List.of(), unprotected, List.copyOf(stillProtected));
    }

    /** Message ids currently carrying {@code holdId}. */
    public List<String> messagesCoveredBy(String holdId) {
        return idsMatching(Query.query(Criteria.where(HOLD_IDS).is(holdId)));
    }

    /** Number of messages currently protected by at least one hold (FR-4.4). */
    public long heldCount() {
        return mongoTemplate.count(Query.query(Criteria.where(HELD).is(true)), ArchivedMessage.class);
    }

    // ---- helpers ---------------------------------------------------------

    private Set<String> idsWithRemainingHolds(List<String> candidates) {
        Set<String> remaining = new LinkedHashSet<>();
        for (List<String> batch : batches(candidates)) {
            remaining.addAll(idsMatching(
                    Query.query(Criteria.where(ID).in(batch).and(HOLD_IDS_FIRST).exists(true))));
        }
        return remaining;
    }

    /**
     * Read only the {@code _id} of matching documents.
     *
     * <p>Reads into {@link Document} rather than {@link ArchivedMessage}
     * deliberately. {@code ArchivedMessage} is a record, so Spring Data can
     * only build it through its canonical constructor — and a field projection
     * supplies nulls for everything it did not select, which fails on the
     * primitive components. Mapping to the raw document keeps the projection
     * (these queries can match the whole corpus, and pulling full message
     * bodies to collect ids would be wasteful) without that constraint.
     */
    private List<String> idsMatching(Query query) {
        query.fields().include(ID);
        return mongoTemplate.find(query, Document.class, COLLECTION).stream()
                .map(doc -> doc.getString(ID))
                .filter(Objects::nonNull)
                .toList();
    }

    private static Set<String> distinct(Collection<String> ids) {
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    private static List<List<String>> batches(Collection<String> ids) {
        List<String> all = new ArrayList<>(ids);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += BATCH) {
            out.add(all.subList(i, Math.min(all.size(), i + BATCH)));
        }
        return out;
    }
}
