#!/bin/bash
# One-shot bootstrap: creates Kafka topics with sensible partition/replication
# settings. Runs in the init-setup container (apache/kafka image).
# Auto-create is enabled on the broker, but pre-creating guarantees the
# partition count and keeps the demo deterministic.
set -e

BOOTSTRAP="kafka:9092"
PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
REPLICATION="${KAFKA_TOPIC_REPLICATION:-1}"

# name:partitions:replication-factor
TOPICS=(
  "message-ingested:${PARTITIONS}:${REPLICATION}"
  "message-archived:${PARTITIONS}:${REPLICATION}"
  "case-events:${PARTITIONS}:${REPLICATION}"
  "hold-events:${PARTITIONS}:${REPLICATION}"
  # Authoritative per-message hold state, emitted by archive-service after it
  # applies a hold, and consumed by search-service to mirror the flag.
  "message-hold-state:${PARTITIONS}:${REPLICATION}"
  # Emitted by archive-service once a message is actually gone, and consumed by
  # search-service to drop the document. Pre-created like the rest: relying on
  # broker auto-create gave it 1 partition instead of 3, and it would vanish
  # entirely if auto-create were ever turned off.
  "message-deleted:${PARTITIONS}:${REPLICATION}"
  "export-events:${PARTITIONS}:${REPLICATION}"
  "audit-events:${PARTITIONS}:${REPLICATION}"
)

echo ">>> Waiting for Kafka at ${BOOTSTRAP} ..."
until /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  sleep 2
done
echo ">>> Kafka is up. Creating topics."

for topic in "${TOPICS[@]}"; do
  name="${topic%%:*}"
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$name" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION"
  echo "    - ${name}"
done

echo ">>> All Kafka topics created."
