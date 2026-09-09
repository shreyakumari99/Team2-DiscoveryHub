package com.smarsh.discoveryhub.archive;

import com.mongodb.client.result.UpdateResult;
import com.smarsh.discoveryhub.archive.domain.ArchivedMessage;
import com.smarsh.discoveryhub.archive.domain.LegalHoldLedger;
import com.smarsh.discoveryhub.events.MessageType;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-4.5 — overlapping holds.
 *
 * <p>The rule under test: a message is protected while <em>any</em> hold
 * covers it, so releasing one hold must not unprotect a message that a second
 * hold still covers. The previous implementation stored a single boolean and
 * failed exactly this case — releasing hold A made messages that hold B still
 * covered deletable again.
 */
class LegalHoldLedgerTest {

    private MongoTemplate mongoTemplate;
    private LegalHoldLedger ledger;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.updateMulti(any(), any(Update.class), eq(ArchivedMessage.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        ledger = new LegalHoldLedger(mongoTemplate);
    }

    /**
     * The ledger reads ids as raw documents, not as {@code ArchivedMessage}:
     * a field projection cannot build a record, whose canonical constructor
     * needs every component. See {@code LegalHoldLedger.idsMatching}.
     */
    private Document idDocument(String id) {
        return new Document("_id", id);
    }

    @Test
    void placingAHoldAddsItsIdAndMarksTheMessageHeld() {
        ledger.apply("hold-a", List.of("m1", "m2"));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateMulti(any(), update.capture(), eq(ArchivedMessage.class));

        Document doc = update.getValue().getUpdateObject();
        // $addToSet, not $set: re-delivery of the same event must be a no-op.
        assertThat(doc.get("$addToSet", Document.class).getString("holdIds")).isEqualTo("hold-a");
        assertThat(doc.get("$set", Document.class).getBoolean("held")).isTrue();
    }

    @Test
    void placingAHoldWithNoResolvedScopeTouchesNothing() {
        LegalHoldLedger.HoldChange change = ledger.apply("hold-a", List.of());

        assertThat(change.protectedIds()).isEmpty();
        verify(mongoTemplate, never()).updateMulti(any(), any(Update.class), eq(ArchivedMessage.class));
    }

    /**
     * The core FR-4.5 case: m1 is covered by hold-a and hold-b, m2 only by
     * hold-a. Releasing hold-a must unprotect m2 and leave m1 protected.
     */
    @Test
    void releasingOneOfTwoOverlappingHoldsKeepsTheMessageProtected() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("messages")))
                // 1st find: messages carrying hold-a
                .thenReturn(List.of(idDocument("m1"), idDocument("m2")))
                // 2nd find: of those, the ones that still carry another hold
                .thenReturn(List.of(idDocument("m1")));

        LegalHoldLedger.HoldChange change = ledger.release("hold-a");

        assertThat(change.unprotectedIds()).containsExactly("m2");
        assertThat(change.stillProtectedIds()).containsExactly("m1");

        // Two updates: the $pull, then held=false for m2 only.
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, times(2))
                .updateMulti(queries.capture(), updates.capture(), eq(ArchivedMessage.class));

        Document pull = updates.getAllValues().get(0).getUpdateObject();
        assertThat(pull.get("$pull", Document.class).getString("holdIds")).isEqualTo("hold-a");

        Document unheld = updates.getAllValues().get(1).getUpdateObject();
        assertThat(unheld.get("$set", Document.class).getBoolean("held")).isFalse();
        // ...and it is scoped to m2, never to m1.
        assertThat(queries.getAllValues().get(1).getQueryObject().toJson()).contains("m2").doesNotContain("m1");
    }

    @Test
    void releasingTheLastHoldUnprotectsEveryMessageItCovered() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("messages")))
                .thenReturn(List.of(idDocument("m1"), idDocument("m2")))
                .thenReturn(List.of());

        LegalHoldLedger.HoldChange change = ledger.release("hold-a");

        assertThat(change.unprotectedIds()).containsExactly("m1", "m2");
        assertThat(change.stillProtectedIds()).isEmpty();
    }

    @Test
    void releasingAHoldThatCoveredNothingIsHarmless() {
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("messages"))).thenReturn(List.of());

        LegalHoldLedger.HoldChange change = ledger.release("hold-a");

        assertThat(change.unprotectedIds()).isEmpty();
        verify(mongoTemplate, never()).updateMulti(any(), any(Update.class), eq(ArchivedMessage.class));
    }

    /**
     * The domain invariant that makes the whole thing safe: {@code held} can
     * never disagree with {@code holdIds}, whatever a caller passes.
     */
    @Test
    void heldFlagIsAlwaysDerivedFromTheHoldSet() {
        ArchivedMessage claimsNotHeld = new ArchivedMessage("m1", "src-1", MessageType.EMAIL, "s", "b",
                Instant.now(), "a@b.com", List.of(), "t", List.of(), false,
                false, Set.of("hold-a"), Instant.now());
        assertThat(claimsNotHeld.held()).isTrue();

        ArchivedMessage claimsHeld = new ArchivedMessage("m2", "src-2", MessageType.EMAIL, "s", "b",
                Instant.now(), "a@b.com", List.of(), "t", List.of(), false,
                true, Set.of(), Instant.now());
        assertThat(claimsHeld.held()).isFalse();
    }
}
