# B-006 A zero-amount cash activity that L-01 accepts cannot be posted (IllegalArgumentException)

**Story:** L-03 (AC4), with L-01 · **Severity:** minor · **Status:** Open (owner decision C15) · **Round:** 1

## Reproduction

1. Exact command(s) run:
   ```
   ./gradlew --init-script <mirror.init.gradle.kts> :tests:test -PincludeIntegration
   ```
2. Test: `L03ArithmeticOrderingEdgeSpec` › "L-03 edge (B-006): every zero-amount cash activity that L-01 accepts can
   be posted to the ledger". `CanonicalCsv.parse` on rows with `amount=0.00` and types `DEPOSIT`, `WITHDRAWAL`, `FEE`,
   `DIVIDEND`, `RETURN_OF_CAPITAL` (L-01 accepts all five: amount is "non-negative"), then `LedgerRepository.post`.

## Expected

One validated funnel (story "Why it matters"): every activity L-01 accepts either posts, or is refused with a named
reason at validation. Which one is an owner decision; today the two stories disagree.

## Actual

```
L-03 edge zero amounts: L-01 accepted [DEPOSIT, WITHDRAWAL, FEE, DIVIDEND, RETURN_OF_CAPITAL] of [DEPOSIT, WITHDRAWAL, FEE, DIVIDEND, RETURN_OF_CAPITAL]
java.lang.AssertionError: DEPOSIT amount 0 accepted by L-01
No exception expected, but a IllegalArgumentException was thrown.
```

`Posting.linesFor` drops zero movements, so the activity has no lines and `requireBalanced` throws
`IllegalArgumentException("an entry needs at least one line")`. The activity cannot be recorded, so its idempotency
key is never stored and a correction (L-04) has nothing to reverse.

## Notes

Options for the owner: L-01 rejects zero amounts for cash-only types (`NON_POSITIVE_AMOUNT` exists), or the ledger
records an entry with no lines (affects B-004's note), or a named ledger rejection code. FX with amount 0 and a
positive counter amount posts (USD lines only) and is not covered here.

## Fix note

Round 2: placeholder implemented (`// OWNER-INPUT: C15`, `docs/owner-inputs.md` C15): a zero-amount `DEPOSIT`,
`WITHDRAWAL`, `FEE`, `DIVIDEND` or `RETURN_OF_CAPITAL` is refused at posting with `ZeroAmountException`, code
`ZERO_AMOUNT`. Left Open because the test "every zero-amount cash activity that L-01 accepts can be posted to the
ledger" encodes a different option (posting succeeds), so it still fails ("No exception expected, but a
ZeroAmountException was thrown"). The placeholder was not changed to make it pass: posting would need an entry with
no lines, which B-004's fix (V3: every entry has at least one line) refuses. Owner picks C15; then QE aligns the test
or Dev changes the code.
