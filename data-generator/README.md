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
  FR-1.3) of varied types/sizes (xlsx, pdf, csv, png, docx).

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
Corpus generation complete: 10000 accepted, 0 failed, ~600 with attachments, in <Ns
```

Because ingestion-service is stateless and publishes to Kafka, the messages
flow on into archive-service → search-service asynchronously. Searchable
within ~30s of generation completing (FR-1.7).

## Tests

```bash
mvn test
```

`MessageFactoryTest` verifies uniqueness, both message types, and the
attachment ratio without hitting any external service.
