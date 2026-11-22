#!/usr/bin/env bash
# L-10: reconcile twice against the running stack; fail unless both summaries are identical and drift=0.
set -euo pipefail
cd "$(dirname "$0")/.."
run() { docker compose exec -T app java -cp '/opt/ledgerline/lib/*' dev.ledgerline.app.MainKt reconcile; }
first="$(run | grep '^reconcile: ')"
second="$(run | grep '^reconcile: ')"
echo "$first"
echo "$second"
[[ "$first" == "$second" ]] || { echo "verify: summaries differ between runs" >&2; exit 1; }
[[ "$first" == *" drift=0 "* ]] || { echo "verify: drift detected" >&2; exit 1; }
echo "verify: ok"
