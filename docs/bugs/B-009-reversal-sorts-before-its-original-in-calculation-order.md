# B-009 In calculation order a reversal sorts before the entry it reverses

**Story:** L-04 (C17 with C4; invariants 5 and 7 on replay) · **Severity:** major · **Status:** Fixed · **Round:** 1
**Owner decision likely needed:** the cause is the interplay of two placeholders (C4 order, C17 reversal inherits the
original's date and source sequence).

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Test: `L04ReversalDatabaseEdgeSpec` › "L-04 edge (invariant 7, replay): in canonical order a reversal never comes
   before its original". Post the story's AC1 buy `TP-0001-2025-0002`, reverse it with the story's key
   `REV-TP-0001-2025-0002`, sort both entries by (effective date, source sequence, idempotency key) and walk the
   `SEC:SYN-AAA` holding of `TP-0001-NR1`.

## Expected

Holdings never go below 0 (invariant 7) when entries are replayed in the order calculation uses, so a replay that
does not special-case reversals sees the buy first and its reversal second.

## Actual

```
holdings of SEC:SYN-AAA during canonical replay of the AC1 buy and its reversal
expected:<0L> but was:<-100000000L>
```

The reversal carries the original's date and source sequence (C17), so the key decides, and `REV-...` < `TP-...`.
Any key that sorts before the original's puts the reversal first.

## Notes

`ShareHoldings` dodges this by leaving reversed pairs out of its walk, but the future calculation engine (L-06) replays
`ledger.entry` in the C4 order: it would see a sale of shares it does not hold and either reject a legitimate ledger or
need its own pairing logic, which puts replay determinism (invariant 5) at risk. Options for Dev and owner: order a
reversal immediately after its original at equal (date, source sequence), for example by tie-breaking on the link;
or have calculation skip reversed pairs and say so in an ADR.

## Fix (dev, round 2)

Added CanonicalOrder (reversal sorts right after its original; C4 amendment in owner-inputs.md and ADR 0005); NOTE the test sorts by the raw tuple, see status.md.
