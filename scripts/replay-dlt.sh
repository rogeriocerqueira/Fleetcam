#!/usr/bin/env bash
# Replays dead-lettered safety events back to the main topic, after the cause was fixed.
# Safe to run more than once: the processor is idempotent on message_id (ADR 0003).
#
# Usage:
#   ./scripts/replay-dlt.sh            # dry run: shows what is in the DLT, publishes nothing
#   ./scripts/replay-dlt.sh --apply    # republishes to device.safety-events.v1
#
# --apply reads with the consumer group "dlt-replay", so messages already replayed
# are not replayed again on the next run. Events that are still invalid will simply
# land in the DLT again.
set -euo pipefail

DLT="device.safety-events.v1.DLT"
TARGET="device.safety-events.v1"
BIN="/opt/kafka/bin"
BOOTSTRAP="localhost:9092"
KAFKA=(docker compose exec -T kafka)
SEP=$'\t'
APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

consume() { # consume [extra consumer args...]
  "${KAFKA[@]}" "$BIN/kafka-console-consumer.sh" --bootstrap-server "$BOOTSTRAP" --topic "$DLT" \
    --property print.key=true --property key.separator="$SEP" --timeout-ms 10000 "$@" 2>/dev/null || true
}

if ! $APPLY; then
  messages="$(consume --from-beginning)"
  count=$(printf '%s' "$messages" | grep -c . || true)
  echo "DLT $DLT: $count message(s) in total (dry run, nothing published)"
  if (( count > 0 )); then
    echo "Devices with the most dead-lettered events:"
    printf '%s\n' "$messages" | cut -f1 | sort | uniq -c | sort -rn | head -5
    echo "First message:"
    printf '%s\n' "$messages" | head -1 | cut -f2- | cut -c1-300
  fi
  echo "Run with --apply to republish the messages not yet replayed."
  exit 0
fi

messages="$(consume --group dlt-replay --consumer-property auto.offset.reset=earliest)"
count=$(printf '%s' "$messages" | grep -c . || true)
if (( count == 0 )); then
  echo "Nothing new to replay."
  exit 0
fi

printf '%s\n' "$messages" | "${KAFKA[@]}" "$BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" \
  --topic "$TARGET" --property parse.key=true --property key.separator="$SEP"
echo "Replayed $count message(s) from $DLT to $TARGET."
echo "Check the result in Grafana: outcome=\"stored\" should rise; outcome=\"dead_lettered\" rises again if the cause persists."
