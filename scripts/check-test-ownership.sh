#!/usr/bin/env bash
# Enforces the role rules from docs/agent-workflow.md on a range of commits.
#
# Every commit must carry an "Agent:" trailer naming who made it: ba, dev, qe or owner.
#   dev   may not touch tests/                          (no test gaming)
#   qe    may not touch production code or ui/          (QE never edits production code)
#   ba    may only touch docs/                          (BA never writes code or tests)
#   owner unrestricted
#
# Usage: scripts/check-test-ownership.sh <base-ref> [head-ref]
#   e.g. scripts/check-test-ownership.sh origin/main HEAD
set -euo pipefail

base="${1:?usage: $0 <base-ref> [head-ref]}"
head="${2:-HEAD}"

PRODUCTION_RE='^(core|ledger|calc|ingest|app|ui)/'
fail=0

for sha in $(git rev-list --no-merges "${base}..${head}"); do
  subject="$(git log -1 --format=%s "$sha")"
  agent="$(git log -1 --format='%(trailers:key=Agent,valueonly,separator=%x2C)' "$sha" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
  files="$(git diff-tree --no-commit-id --name-only -r "$sha")"

  violation() {
    echo "::error::${sha:0:10} (${agent:-none}) \"${subject}\": $1"
    fail=1
  }

  case "$agent" in
    dev)
      bad="$(grep -E '^tests/' <<<"$files" || true)"
      [[ -n "$bad" ]] && violation "dev commit touches QE-owned tests/: $(tr '\n' ' ' <<<"$bad")"
      ;;
    qe)
      bad="$(grep -E "$PRODUCTION_RE" <<<"$files" || true)"
      [[ -n "$bad" ]] && violation "qe commit touches production code: $(tr '\n' ' ' <<<"$bad")"
      ;;
    ba)
      bad="$(grep -vE '^docs/' <<<"$files" || true)"
      [[ -n "$bad" ]] && violation "ba commit touches files outside docs/: $(tr '\n' ' ' <<<"$bad")"
      ;;
    owner) ;;
    "")
      violation "missing 'Agent: <ba|dev|qe|owner>' trailer"
      ;;
    *)
      violation "unknown Agent trailer '${agent}' (expected ba, dev, qe or owner)"
      ;;
  esac
done

if [[ $fail -ne 0 ]]; then
  echo "Role check failed. See docs/agent-workflow.md (Authority rules) and CLAUDE.md (Commits)."
  exit 1
fi
echo "Role check passed for ${base}..${head}."
