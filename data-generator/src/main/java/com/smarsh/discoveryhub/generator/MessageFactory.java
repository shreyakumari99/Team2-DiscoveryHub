package com.smarsh.discoveryhub.generator;

import com.smarsh.discoveryhub.events.AttachmentPayload;
import com.smarsh.discoveryhub.events.MessageIngestedEvent;
import com.smarsh.discoveryhub.events.MessageType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Produces one realistic-looking fake message at a time.
 *
 * <p>The content is deterministic-ish given a seed so the corpus is varied but
 * contains recognizable threads and terms an investigator can search for during
 * the demo (e.g. "bonus pool", "Q3 forecast", "compliance review"). ~5% of
 * messages carry an attachment (FR-1.3).
 */
public class MessageFactory {

    private static final String[] EMAIL_SUBJECTS = {
            "Q3 earnings forecast", "Bonus pool discussion", "Compliance review - urgent",
            "Trade desk daily summary", "Client onboarding - ACME Corp",
            "Re: research note on TechCo", "FW: regulatory filing draft",
            "Lunch next week?", "Travel approval needed", "Budget reallocation"
    };

    private static final String[] EMAIL_BODIES = {
            "Team, please review the attached Q3 forecast before the board meeting. The numbers look strong but we need to stress-test the assumptions on revenue growth.",
            "Hi all, following up on the bonus pool discussion from last week. HR has asked for our recommendations by Friday. Let's keep this confidential for now.",
            "This is a reminder that all trading desk communications are subject to compliance review. Please ensure all chats are retained per policy.",
            "Daily P&L summary attached. Notable positions: long TechCo 50k shares, short EnergyCorp 20k. Reach me on chat if anything looks off.",
            "ACME Corp onboarding is proceeding. KYC checks complete; awaiting legal sign-off on the account agreement. Target go-live next Monday.",
            "Adding to the research note on TechCo: Q2 results beat consensus, guidance raised. Maintaining BUY. See attachment for the updated model.",
            "Please review the attached draft regulatory filing. Comments due to Legal by EOD. This is privileged and confidential.",
            "Are you free for lunch next Thursday? Wanted to talk through the new wealth management initiative off the record.",
            "I need travel approval for the New York client visit on the 14th. Estimated costs in the attached spreadsheet.",
            "Given the Q3 miss, proposing we reallocate 15% of the marketing budget to digital. Thoughts?"
    };

    private static final String[] CHAT_BODIES = {
            "hey did you see the Q3 numbers? looking rough",
            "btw compliance wants all chats logged now",
            "can you ping me when the bonus pool decision lands",
            "the TechCo trade is up 8% today, nice",
            "ACME onboarding delayed again, legal is swamped",
            "quick one - is the research note public yet?",
            "thx for the heads up on the filing, will review tonight",
            "lunch thursday works for me, book it",
            "travel approved, send the spreadsheet",
            "budget reallocation sounds good, let's discuss in the standup"
    };

    private static final String[] ATTACHMENT_NAMES = {
            "forecast.xlsx", "bonus_pool.xlsx", "compliance_checklist.pdf",
            "daily_pnl.csv", "acme_kyc.pdf", "techco_model.xlsx",
            "filing_draft.pdf", "travel_costs.xlsx", "budget_plan.xlsx",
            "screenshot.png", "meeting_notes.docx", "contract_v3.pdf"
    };

    private static final String[] ATTACHMENT_TYPES = {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/pdf", "text/csv", "image/png",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    };

    private final Random random;
    private final Instant baseTime;

    public MessageFactory(long seed) {
        this.random = new Random(seed);
        // Spread messages over the last ~2 years so retention/demo filters are meaningful.
        this.baseTime = Instant.now().minus(730, ChronoUnit.DAYS);
    }

    public MessageIngestedEvent newMessage() {
        MessageType type = random.nextDouble() < 0.6 ? MessageType.EMAIL : MessageType.CHAT;
        Custodians.Custodian sender = Custodians.ALL.get(random.nextInt(Custodians.ALL.size()));

        int participants = 1 + random.nextInt(4);
        List<String> participantsList = new java.util.ArrayList<>();
        participantsList.add(sender.email());
        while (participantsList.size() < participants) {
            Custodians.Custodian other = Custodians.ALL.get(random.nextInt(Custodians.ALL.size()));
            if (!participantsList.contains(other.email())) {
                participantsList.add(other.email());
            }
        }

        Instant timestamp = baseTime.plus(random.nextLong(730L * 24 * 60 * 60), ChronoUnit.SECONDS);

        String subject = null;
        String body;
        if (type == MessageType.EMAIL) {
            subject = EMAIL_SUBJECTS[random.nextInt(EMAIL_SUBJECTS.length)];
            body = EMAIL_BODIES[random.nextInt(EMAIL_BODIES.length)];
        } else {
            body = CHAT_BODIES[random.nextInt(CHAT_BODIES.length)];
        }

        String threadId = "thread-" + random.nextInt(50);

        List<AttachmentPayload> attachments = List.of();
        if (random.nextDouble() < 0.06) { // ~6% -> satisfies "at least 5%" (FR-1.3)
            String name = ATTACHMENT_NAMES[random.nextInt(ATTACHMENT_NAMES.length)];
            String contentType = ATTACHMENT_TYPES[random.nextInt(ATTACHMENT_TYPES.length)];
            byte[] bytes = syntheticContent(name, random);
            String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            attachments = List.of(new AttachmentPayload(name, contentType, bytes.length, base64));
        }

        return new MessageIngestedEvent(
                "src-" + UUID.randomUUID(),
                type,
                subject,
                body,
                timestamp,
                sender.email(),
                participantsList,
                threadId,
                attachments
        );
    }

    private static byte[] syntheticContent(String name, Random random) {
        // A few hundred bytes of recognizable text so attachments are non-trivial.
        int len = 200 + random.nextInt(800);
        StringBuilder sb = new StringBuilder();
        sb.append("Synthetic attachment: ").append(name).append("\n\n");
        while (sb.length() < len) {
            sb.append("This is generated content for the DiscoveryHub demo corpus. ");
            sb.append("It exists so attachments have realistic sizes and checksums. ");
        }
        return sb.substring(0, len).getBytes();
    }
}
