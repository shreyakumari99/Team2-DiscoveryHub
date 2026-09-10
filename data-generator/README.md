# Data Generator

Builds the 10,000+ fake message corpus by calling the ingestion-service REST
API repeatedly, **exactly like a real client would** (FR-1.2). It owns no
database and no state — it just generates messages and POSTs them.

## What it generates

- **20+ fictional employees** (custodians) across Compliance, Trading, Research,
  Legal, etc. (see `Custodians.java`).
- **Both communication types**: email and chat (~60/40).
- **Realistic subjects, bodies, timestamps, participants, threads** — content
  includes searchable terms for the demo (e.g. "Q3 forecast", "bonus pool",
  "compliance review", "TechCo").
- **Timestamps spread over ~2 years** so date-range filters and retention are
  meaningful.
- **~6% of messages carry attachments** (satisfies the "at least 5%" rule,
  FR-1.3) across five types (xlsx, pdf, csv, png, docx).

### Known limitations

Two things are thinner than the requirement implies, and are worth knowing
before someone asks in a demo:

- **Threads are random buckets, not conversations.** Messages share a
  `threadId` drawn from 50 values, but there are no reply chains, no "Re:"
  subjects, and no consistent participants within a thread. The attribute
  exists and is searchable; the realism does not.
- **Attachments are 200–1000 bytes and never more than one per message.** The
  types vary, the sizes barely do.

## Run it

Start the stack first (at least ingestion-service + Kafka):

```bash
docker compose up -d ingestion-service kafka
```

Then run the generator:

```bash
cd data-generator
mvn spring-boot:run -Dspring-boot.run.arguments="--ingestion.url=http://localhost:8081 --count=10000"
```

You should see:

```
Corpus generation complete: 10000 accepted, 0 failed, 602 with attachments, 0 retried, in 2s
```

### Why the concurrency is bounded

Requests go out on virtual threads, but only `--concurrency` of them are in
flight at once (default 64). Firing all 10,000 simultaneously — the obvious
implementation — opens ten thousand connections, overruns the server's accept
queue, and roughly **14% of the corpus is refused**. The failures look like a
platform fault but are self-inflicted load.

Bounded, every message is accepted *and it is faster*: 2 seconds versus 53,
because nothing is thrashing. Lower it (`--concurrency=16`) on a slower
machine.

Transient failures are retried once. That is safe because ingestion is
idempotent on `sourceMessageId` (FR-1.6), so a redelivery can never create a
duplicate — which also means re-running the whole generator is harmless.

### What happens next

Because ingestion-service is stateless and publishes to Kafka, the messages
flow on into archive-service → search-service asynchronously. Ingestion itself
takes seconds; the pipeline then drains at roughly 34 messages/second, so a
full 10,000-message corpus is completely searchable about **5 minutes** after
the generator finishes. Individual messages are searchable within a couple of
seconds of being archived (FR-1.7).

Run this *before* a demo, not during it.

## Tests

```bash
mvn test
```

`MessageFactoryTest` verifies uniqueness, both message types, and the
attachment ratio without hitting any external service.
