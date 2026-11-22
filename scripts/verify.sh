#!/usr/bin/env bash
# L-10: reconcile twice against the running stack; fail unless both summaries are identical and drift=0.
set -euo pipefail
cd "$(dirname "$0")/.."
run() { docker compose exec -T app java -cp '/opt/ledgerline/lib/*' dev.ledgerline.app.MainKt reconcile; }
# The consumer applies seeded events asynchronously, so a first run right after startup can show drift: retry (~60 s).
for _ in $(seq 1 30); do
  first="$(run | grep '^reconcile: ' || true)"
  [[ "$first" == *" drift=0 "* ]] && break
  sleep 2
done
second="$(run | grep '^reconcile: ')"
echo "$first"
echo "$second"
[[ "$first" == "$second" ]] || { echo "verify: summaries differ between runs" >&2; exit 1; }
[[ "$first" == *" drift=0 "* ]] || { echo "verify: drift detected" >&2; exit 1; }
echo "verify: ok"
