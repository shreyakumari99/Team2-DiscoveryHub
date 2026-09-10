package com.smarsh.discoveryhub.holdretention;

import com.smarsh.discoveryhub.common.audit.AuditTrail;
import com.smarsh.discoveryhub.events.HoldEvent;
import com.smarsh.discoveryhub.events.Topics;
import com.smarsh.discoveryhub.holdretention.api.ArchiveServiceClient;
import com.smarsh.discoveryhub.holdretention.api.CaseServiceClient;
import com.smarsh.discoveryhub.holdretention.api.HoldService;
import com.smarsh.discoveryhub.holdretention.api.PlaceHoldRequest;
import com.smarsh.discoveryhub.holdretention.api.SearchServiceClient;
import com.smarsh.discoveryhub.holdretention.domain.HoldEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HoldService} against an in-memory H2 database (FR-4).
 *
 * <p>Scope resolution runs on the calling thread here (see
 * {@link SynchronousExecutorConfig}) so the assertions are deterministic;
 * in production it is a virtual thread, which is what keeps placement
 * non-blocking (FR-4.3).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hold-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.kafka.bootstrap-servers=localhost:0",
        "disposition.enabled=false",
        // Lets SynchronousExecutorConfig replace the virtual-thread executor.
        "spring.main.allow-bean-definition-overriding=true"
})
class HoldServiceTest {

    /** Runs hold scope resolution inline so tests do not race a background thread. */
    @TestConfiguration
    static class SynchronousExecutorConfig {
        @Bean
        @Primary
        Executor holdScopeExecutor() {
            return Runnable::run;
        }
    }

    @Autowired
    private HoldService holdService;

    @MockBean
    private SearchServiceClient searchClient;

    @MockBean
    private CaseServiceClient caseClient;

    @MockBean
    private ArchiveServiceClient archiveClient;

    @MockBean
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private AuditTrail auditTrail;

    @BeforeEach
    void setUp() {
        when(caseClient.isClosed(any())).thenReturn(false);
        when(searchClient.resolveScope(any(), any(), any(), any())).thenReturn(List.of());
    }

    private HoldEntity place(String caseId, List<String> custodians) {
        return holdService.placeHold(new PlaceHoldRequest(caseId, custodians, null, null, null));
    }

    /**
     * Regression for the {@code LazyInitializationException} on
     * {@code GET /api/v1/holds}: with {@code open-in-view=false} the custodian
     * collection must be initialized inside the transaction.
     */
    @Test
    void listHoldsInitializesCustodiansOutsideTheSession() {
        place("case-1", List.of("cust-1", "cust-2"));

        HoldEntity mine = holdService.listHolds("case-1").stream()
                .filter(h -> "case-1".equals(h.getCaseId()))
                .findFirst()
                .orElseThrow();
        assertThat(mine.getCustodians()).containsExactly("cust-1", "cust-2");
    }

    @Test
    void listAllHoldsInitializesCustodians() {
        place("case-a", List.of("cust-a"));

        HoldEntity mine = holdService.listHolds(null).stream()
                .filter(h -> "case-a".equals(h.getCaseId()))
                .findFirst()
                .orElseThrow();
        assertThat(mine.getCustodians()).containsExactly("cust-a");
    }

    /** FR-4.1 / FR-4.3: the hold exists immediately, and its scope resolves after. */
    @Test
    void placingAHoldResolvesItsScopeAndPublishesTheMessageIds() {
        when(searchClient.resolveScope(any(), any(), any(), any()))
                .thenReturn(List.of("msg-1", "msg-2", "msg-3"));

        HoldEntity hold = place("case-scope", List.of("alice@smarsh.com"));

        HoldEntity resolved = holdService.getHold(hold.getId());
        assertThat(resolved.getMessageCount()).isEqualTo(3);
        assertThat(resolved.isScopeResolved()).isTrue();

        HoldEvent event = capturePublishedEvent();
        assertThat(event.type()).isEqualTo("PLACED");
        assertThat(event.messageIds()).containsExactly("msg-1", "msg-2", "msg-3");
    }

    /**
     * A hold whose scope could not be resolved must not look like a hold that
     * matched nothing — otherwise an outage silently produces a legal hold
     * protecting zero messages.
     */
    @Test
    void aFailedScopeResolutionIsRecordedRatherThanLookingLikeAnEmptyScope() {
        when(searchClient.resolveScope(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("search-service unavailable"));

        HoldEntity hold = place("case-broken", List.of("alice@smarsh.com"));

        HoldEntity stored = holdService.getHold(hold.getId());
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.isScopeResolved()).isFalse();
        assertThat(stored.getScopeFailureReason()).contains("search-service unavailable");
    }

    /**
     * FR-4.5: the release event carries no message ids. Archive knows which
     * messages the hold froze and only unprotects those no other hold covers;
     * re-deriving the list here could release a different set.
     */
    @Test
    void releasingAHoldPublishesNoMessageIds() {
        HoldEntity hold = place("case-release", List.of("alice@smarsh.com"));

        HoldEntity released = holdService.releaseHold(hold.getId(), "manual");

        assertThat(released.isActive()).isFalse();
        assertThat(released.getReleasedAt()).isNotNull();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce())
                .send(eq(Topics.HOLD_EVENTS), any(), captor.capture());
        HoldEvent release = captor.getAllValues().stream()
                .map(HoldEvent.class::cast)
                .filter(e -> "RELEASED".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertThat(release.messageIds()).isEmpty();
    }

    @Test
    void releasingAnAlreadyReleasedHoldIsHarmless() {
        HoldEntity hold = place("case-twice", List.of("alice@smarsh.com"));
        holdService.releaseHold(hold.getId(), "manual");

        HoldEntity again = holdService.releaseHold(hold.getId(), "manual");

        assertThat(again.isActive()).isFalse();
    }

    /** FR-2.5: a closed case is read-only, and that includes new holds. */
    @Test
    void aHoldCannotBePlacedOnAClosedCase() {
        when(caseClient.isClosed("case-closed")).thenReturn(true);

        assertThatThrownBy(() -> place("case-closed", List.of("alice@smarsh.com")))
                .hasMessageContaining("read-only");
    }

    /** Closing a case releases its holds (FR-4.5). */
    @Test
    void closingACaseReleasesEveryActiveHoldOnIt() {
        place("case-closing", List.of("alice@smarsh.com"));
        place("case-closing", List.of("bob@smarsh.com"));

        int released = holdService.releaseHoldsForCase("case-closing", "case-closed");

        assertThat(released).isEqualTo(2);
        assertThat(holdService.activeHoldCount("case-closing")).isZero();
    }

    /**
     * FR-4.4: the per-case held total is asked of the archive, so two
     * overlapping holds do not double-count the messages they share.
     */
    @Test
    void heldMessageCountIsDeduplicatedAcrossOverlappingHolds() {
        when(archiveClient.heldMessageCount(any())).thenReturn(7L);
        place("case-overlap", List.of("alice@smarsh.com"));
        place("case-overlap", List.of("bob@smarsh.com"));

        assertThat(holdService.heldMessageCount("case-overlap")).isEqualTo(7L);
    }

    @Test
    void aCaseWithNoActiveHoldsHasNoHeldMessages() {
        assertThat(holdService.heldMessageCount("case-with-nothing")).isZero();
    }

    private HoldEvent capturePublishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce())
                .send(eq(Topics.HOLD_EVENTS), any(), captor.capture());
        return (HoldEvent) captor.getValue();
    }
}
