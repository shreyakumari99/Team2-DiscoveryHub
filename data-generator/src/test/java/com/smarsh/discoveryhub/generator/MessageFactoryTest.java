package com.smarsh.discoveryhub.generator;

import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import com.smarsh.discoveryhub.events.MessageType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the message factory produces a varied, well-formed corpus: unique
 * ids, both types, realistic participant counts, and ~5%+ attachments (FR-1.2,
 * FR-1.3).
 */
class MessageFactoryTest {

    @Test
    void producesUniqueIdsAndBothTypesOver1000Messages() {
        MessageFactory factory = new MessageFactory(123L);
        Set<String> ids = new HashSet<>();
        boolean sawEmail = false;
        boolean sawChat = false;
        int withAttachment = 0;

        for (int i = 0; i < 1000; i++) {
            MessageIngestedEvent m = factory.newMessage();
            assertTrue(ids.add(m.sourceMessageId()), "sourceMessageId must be unique");
            assertNotNull(m.timestamp());
            assertNotNull(m.sender());
            assertFalse(m.participants().isEmpty());
            if (m.type() == MessageType.EMAIL) {
                sawEmail = true;
                assertNotNull(m.subject());
            } else {
                sawChat = true;
            }
            if (!m.attachments().isEmpty()) {
                withAttachment++;
            }
        }

        assertTrue(sawEmail, "corpus must include emails");
        assertTrue(sawChat, "corpus must include chats");
        // ~6% expected; assert at least a few over 1000 to confirm the path works.
        assertTrue(withAttachment > 10, "should produce a non-trivial number of attachments, got " + withAttachment);
        assertEquals(1000, ids.size());
    }

    @Test
    void producesAtLeast20Custodians() {
        assertTrue(Custodians.ALL.size() >= 20, "FR-1.2 requires at least 20 custodians");
    }
}
