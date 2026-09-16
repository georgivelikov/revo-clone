#!/usr/bin/env bash
# One-shot topic creation from the phase-00 topic catalogue (plans/phase-00-foundations.md
# §Topic catalogue). Idempotent (--if-not-exists), so safe to re-run.
#
# Cleanup policy and retention are decided here because neither can be changed cheaply later:
# switching a topic to compaction destroys the history a later phase replays. The "Key" column
# below is documentation of the partition key each producer must use — Kafka doesn't enforce it
# at topic-creation time.
#
# Run against the docker-compose.infra.yml stack, e.g.:
#   docker compose -f docker-compose.infra.yml exec -T kafka bash < infra/kafka/create-topics.sh
# or, from inside the kafka container, directly:
#   bash infra/kafka/create-topics.sh

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVER:-localhost:9092}"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

create() {
  local topic="$1" partitions="$2" cleanup="$3" retention_ms="$4"

  local configs="cleanup.policy=${cleanup}"
  if [ -n "${retention_ms}" ]; then
    configs="${configs} --config retention.ms=${retention_ms}"
  fi

  # shellcheck disable=SC2086
  "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
    --topic "${topic}" --partitions "${partitions}" --replication-factor 1 \
    --config cleanup.policy="${cleanup}" ${retention_ms:+--config retention.ms=${retention_ms}}
}

# topic                    key               parts cleanup retention(ms, empty = no override)
create identity.user.v1        12 compact ""           # key: userId
create account.account.v1      12 compact ""           # key: accountId
create ledger.entry.v1         12 delete  -1           # key: ledgerAccountId
create payment.event.v1        12 delete  2592000000   # key: paymentId
create notification.push.v1    12 delete  604800000    # key: userId
create fx.rate.tick.v1         12 delete  3600000       # key: pair
create fx.quote.v1             12 delete  2592000000   # key: quoteId
create card.authorization.v1   12 delete  -1           # key: cardId

echo "All topics created. Current state:"
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --list
