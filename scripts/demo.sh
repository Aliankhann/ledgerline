#!/usr/bin/env bash
# End-to-end demo: compose up, POST three activities (L-01 fixture shape), wait for the pipeline, show holdings.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${BASE_URL:-http://localhost:${APP_PORT:-8080}}"
# TP-0099 is outside the seeded taxpayers (TP-0001..0005) and the keys are DEMO-*, so seeding never collides.
TP="TP-0099"

docker compose up -d --build --wait

post() {
  curl -fsS -X POST "$BASE/v1/activities" -H 'Content-Type: application/json' -d "$1"
  echo
}

post '{"idempotencyKey":"DEMO-0001","taxpayerId":"TP-0099","accountId":"TP-0099-NR1","accountType":"NON_REGISTERED","activityType":"DEPOSIT","effectiveDate":"2025-01-06","settlementDate":"2025-01-06","currency":"CAD","amount":"10000.00","commission":"0.00","description":"Initial deposit"}'
post '{"idempotencyKey":"DEMO-0002","taxpayerId":"TP-0099","accountId":"TP-0099-NR1","accountType":"NON_REGISTERED","activityType":"BUY","effectiveDate":"2025-01-07","settlementDate":"2025-01-08","securityId":"SYN-AAA","quantity":"100","currency":"CAD","amount":"3012.50","commission":"4.95"}'
post '{"idempotencyKey":"DEMO-0003","taxpayerId":"TP-0099","accountId":"TP-0099-NR1","accountType":"NON_REGISTERED","activityType":"BUY","effectiveDate":"2025-01-09","settlementDate":"2025-01-10","securityId":"SYN-AAA","quantity":"50","currency":"CAD","amount":"1520.00","commission":"4.95"}'

# Outbox relay -> Redpanda -> PoolConsumer is asynchronous: poll until a holding appears (max ~30 s).
for _ in $(seq 1 30); do
  out="$(curl -fsS "$BASE/v1/holdings/$TP/acb-vs-book")"
  if [[ "$out" != *'"holdings":[]'* ]]; then break; fi
  sleep 1
done
echo "$out"
