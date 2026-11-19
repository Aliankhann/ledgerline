# B-011 Canonical replay goes negative when a sell and an earlier buy are both reversed

**Story:** L-04 (invariants 5 and 7 on replay; follow-up to B-009) · **Severity:** major · **Status:** Fixed (pending CI) · **Round:** 3
**Owner decision likely needed:** the amended C4 order (reversal immediately after its original) cannot also keep
replay non-negative; either calculation skips reversed pairs (ADR) or the order changes.

## Reproduction

1. Command:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:propertyTest -PincludeIntegration
   ```
2. Test: `ReversalSymmetryPropertyTest` "Invariant 4 (L-04 AC9): reverse a random subset ..." (final canonical-order
   check, `CanonicalReplay.lowestHolding`). Minimal shape from the failing case, one account, one security, same date:
   buy B (source_seq 4), sell S (source_seq 5, covered by B). Reverse S first (allowed), then reverse B (allowed).
   `entriesInCanonicalOrder()` gives B, REV-B, S, REV-S.

## Expected

Replaying the canonical order never takes an investor holding below 0 (invariant 7), so L-06 can replay
`ledger.entry` without special-casing reversals.

## Actual

After REV-B the account holds 0, then S sells 534143859766 units it does not hold: holding goes to
-534143859766 (property run: lowest holding -740889387465 against a floor of 0 for the originals alone).
Both reversals were individually valid when made (reversing S, then B, leaves nothing held); the order places
REV-B before S because a reversal sorts directly after its own original, not after the later entries that were
reversed before it.

## Notes

B-009's literal checks pass: every reversal follows its original, and the story's AC1 buy and reversal never go
negative (spec test passes). This is the general case. Adjacent-pair order cannot satisfy both "reversal right after
original" and "no negative holdings" when a later sell is also reversed. Options: calculation drops reversed
original/reversal pairs before replay (needs an ADR; pairs net to zero by invariant 4); or order a reversal by its
own entry sequence (then a reversal can follow later entries, but is always after its original). The test stays
failing until Dev or the owner picks one.

## Dev fix (owner decision C19, provisionally accepted 2026-11-19)

Replay skips each reversed entry together with its reversal. New `CanonicalOrder.forReplay` and
`LedgerRepository.entriesForReplay()` return the canonical order without those rows; `entriesInCanonicalOrder()` still
returns every row (audit, listings). The share-holdings check already left reversed pairs out of its walk.

**Test note for QE:** the property test and the spec test "in canonical order a reversal never comes before its original"
call `entriesInCanonicalOrder()`, which by C19 still lists reversed rows, so the final `lowestHolding` check in
`ReversalSymmetryPropertyTest` still sees B, REV-B, S, REV-S and can still fail. It should check
`repo.entriesForReplay()` for the holdings floor (and may keep the order checks on the full listing). Dev cannot edit `tests/`.
