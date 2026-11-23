# B-030 Correcting a correction's replacement stalls a consumer that has not yet read the first reversal

**Story:** L-09 (AC1, AC7, AC8) via L-12 consumer reversal path; follow-on to B-029 · **Severity:** major (reachable through `LedgerRepository.correct` twice; stalls every taxpayer on the consumer; no reconcile possible while stalled) · **Status:** Open · **Round:** 1

## Reproduction

1. Reproduced without Docker: a local PostgreSQL 16.15 (not a container), ledger and calc Flyway migrations V1 to V9 applied,
   `LedgerRepository(app role)`, then `ReversalRecompute.apply` called by reflection on the app-role connection (the same
   call `PoolConsumer.handle` makes for an `ENTRY_REVERSED` event). The throwaway harness was deleted after the run.
   Not run: the Kafka consumer itself (needs Redpanda). The Integration test that expresses the same scenario end to end is
   `B029FixAttackSpec` "B-029 attack 1: correcting a replacement again, both corrections written before the consumer runs";
   it has not been run (needs Docker; CI will).
2. Sequence (story L-09 fixture F1, posted with `submit`, so every entry has its `ledger.activity` row):

   | entry | key | what |
   | --- | --- | --- |
   | 2 | `TP-0001-2025-0002` | BUY 100 SYN-AAA 2025-03-10 |
   | 4 | `TP-0001-2025-0004` | SELL 60 SYN-AAA 2025-06-02 |
   | 8, 9 | `...-0002-COR`, `...-0002-C1` | `correct(2, ..., replacement BUY 110)` |
   | 10, 11 | `...-0002-COR2`, `...-0002-C2` | `correct(9, ..., replacement BUY 120)` (the replacement is corrected again) |

   Both corrections are committed before the consumer reads entry 8's event (consumer behind, down, or restarting).
3. `ReversalRecompute.apply(c, reversedEntryId = 2, reversalEntryId = 8)`.

## Expected

The ledger accepted both corrections (it checks the final state: 120 - 60 >= 0). The consumer applies the events in order
and ends at 120 - 60 + 20 = 80 shares of SYN-AAA for TP-0001, with reconcile drift 0.

## Actual

```
single correction (B-029 case): apply(2, 8)  -> OK
chain, entries 10 and 11 present: apply(2, 8)   -> java.lang.IllegalStateException: reversal of entry 2 makes the replay
  of TP-0001/SYN-AAA reject: [Rejection(idempotencyKey=TP-0001-2025-0004, code=INSUFFICIENT_SHARES, held=0,
  requested=60000000, ...)]
chain: apply(9, 10) -> OK
```

`handle` rolls back and `pollOnce` retries the same event for ever, as in B-026 and B-029.

## Root cause (from reading `ReversalRecompute.effectiveHistory`)

The B-029 fix adds the linked replacement (entry 9) to the history for reversal 8, but the same query still removes
every entry that has a reversal row (`NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = a.entry_id ...)`).
Entry 9 is reversed by entry 10, which is later than the event being applied. So at event 8 the history has neither
entry 2 (reversed by 8) nor entry 9 (reversed by 10); the sell of 60 has no buy behind it and is rejected as a
rejection the reversal "introduced". The consumer judges event 8 against reversals that belong to later events.
The ledger is right: at the time of the second correction it checked the state without entries 2 and 9 and with
entry 11. The consumer only sees that state once it has applied event 10.

## Notes

- Does not occur when the consumer applies event 8 before entry 10 is written (the B-029 sequence), which is why the
  existing L-09 tests pass.
- Same class as B-029 (consumer and ledger disagree on what the "state after the reversal" is). Fix direction for Dev and
  the owner: judge a reversal against the history as of that reversal (ignore reversal rows with a reversal entry id
  above `reversalEntryId`), or follow the correction link chain (entry 9 -> 11) when the replacement is itself reversed.
  Either choice needs the ADR 0007 note extended.
- Observation, not filed as a bug: a correction whose replacement keeps the same effective date but settles after the
  sell (`settlementDate` 2025-06-05, tax date per `TaxRuleSet.taxDate`) is accepted by the ledger (it orders by effective
  date) and the consumer then stalls on the reversal event with the same `INSUFFICIENT_SHARES` rejection. That is the
  B-026 behaviour, nothing is clipped, and the brief allows it. Whether the ledger should check tax dates is an owner
  question. Attack 4(b) in `B029FixAttackSpec` accepts either outcome (refused, or stall with the pool unchanged).
