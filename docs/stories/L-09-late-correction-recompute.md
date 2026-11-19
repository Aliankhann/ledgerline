# L-09 Late correction recompute

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 8 · **Branch:** `L-09-late-correction-recompute` · **Round:** 0 / 3

## Story

As a self-directed investor, I want a correction or a late activity dated months back to recompute the holdings it
affects, from its date forward, and leave everything else alone, so that a late return-of-capital notice or a fixed
typo gives me the right number without a full rebuild or silent drift.

## Why it matters

Broker data is corrected after the fact, and ACB depends on order, so a back-dated change has to ripple forward. See
[`docs/problem/evidence.md`](../problem/evidence.md): E-32 (Wealthsimple "retroactively applies return-of-capital
and non-cash distribution adjustments, generally in May", second-hand via myCostBase) and E-25 (return of capital
reduces ACB).

## Notes for QE and Dev

- **No tax expected values in this story.** The oracle is equality with a from-scratch replay of the corrected
  activity set through the L-06 engine, plus share counts (which are not tax judgments). Absolute ACB numbers are
  tested in L-06, L-07 and L-08 against cited sources.
- Design per `docs/build-plan.md`: recompute the affected (taxpayer, security) pool from the earliest changed
  effective date forward. Depends on L-04 (correction), L-06 (pools) and L-12 part 1 (consumer).
- Quantities assume the 10⁻⁶ share scale (`100` shares = `100000000` units).
- **Observability proposal (Dev may refine):** pool history at
  `GET /v1/pools/{taxpayerId}/{securityId}/history` (one row per applied activity, in calculation order), and a
  counter `ledgerline_pool_recompute_total{taxpayer,security}` incremented once per recompute.
- **Fixture F1** (all CAD, all `NON_REGISTERED`, posted in this order):

  | Key | Taxpayer | Account | Type | Effective date | Security | Shares | Amount (minor) | Commission |
  | --- | --- | --- | --- | --- | --- | --- | --- | --- |
  | `TP-0001-2025-0001` | TP-0001 | TP-0001-NR1 | DEPOSIT | 2025-01-06 | – | – | 5000000 | 0 |
  | `TP-0001-2025-0002` | TP-0001 | TP-0001-NR1 | BUY | 2025-03-10 | SYN-AAA | 100 | 301250 | 495 |
  | `TP-0001-2025-0003` | TP-0001 | TP-0001-NR1 | BUY | 2025-04-14 | SYN-BBB | 50 | 225000 | 495 |
  | `TP-0001-2025-0004` | TP-0001 | TP-0001-NR1 | SELL | 2025-06-02 | SYN-AAA | 60 | 196800 | 495 |
  | `TP-0001-2025-0005` | TP-0001 | TP-0001-NR1 | BUY | 2025-09-15 | SYN-AAA | 20 | 63000 | 495 |
  | `TP-0002-2025-0001` | TP-0002 | TP-0002-NR1 | DEPOSIT | 2025-01-06 | – | – | 5000000 | 0 |
  | `TP-0002-2025-0002` | TP-0002 | TP-0002-NR1 | BUY | 2025-03-10 | SYN-AAA | 100 | 301250 | 495 |

## Acceptance criteria

1. **AC1 A back-dated correction gives the same pool as a clean replay.**
   - Input: F1 fully applied. On recorded-at `2025-11-15T14:00:00Z`, correct `TP-0001-2025-0002` (L-04 correction)
     with replacement `TP-0001-2025-0002-C1`, identical except shares `110` and amount `331375`.
   - Expected: pool `TP-0001`/`SYN-AAA` has shares `70000000` (110 − 60 + 20) and every pool field equal to the pool
     produced by replaying, into an empty calc state, F1 with `TP-0001-2025-0002` replaced by `-C1`.
2. **AC2 Unaffected pools are not touched.**
   - Input: as AC1.
   - Expected: pools `TP-0001`/`SYN-BBB` and `TP-0002`/`SYN-AAA` are byte-identical before and after (same row hash);
     `ledgerline_pool_recompute_total` increased for `TP-0001`/`SYN-AAA` only (by 1 if the reversal and replacement
     are recomputed together, by 2 if separately; the Dev states which) and is unchanged for the other two pools.
3. **AC3 Recompute starts at the earliest changed date.**
   - Input: as AC1, and F1 extended with `TP-0001-2025-0000` `BUY` `SYN-AAA` 10 shares on `2025-02-03` (amount `30000`,
     commission `495`) before AC1's correction.
   - Expected: in `TP-0001`/`SYN-AAA` history, the 2025-02-03 row is byte-identical before and after the correction;
     rows from 2025-03-10 onward are recomputed and equal a clean replay's rows.
4. **AC4 A late new activity (not a correction) also recomputes.**
   - Input: F1 applied. Then post a new activity `TP-0001-2025-0050`: `BUY` `SYN-AAA` 5 shares dated `2025-05-01`,
     amount `15500`, commission `495`, recorded at `2025-11-16T10:00:00Z`.
   - Expected: `TP-0001`/`SYN-AAA` shares `65000000` (100 + 5 − 60 + 20); pool equals a clean replay of F1 plus this
     activity; history rows dated before 2025-05-01 unchanged.
5. **AC5 Correction dated before every existing activity.**
   - Input: F1 applied. Post `TP-0001-2025-0060`: `BUY` `SYN-AAA` 10 shares dated `2025-01-02`, amount `29000`,
     commission `495`.
   - Expected: pool equals a clean replay; every `TP-0001`/`SYN-AAA` history row is recomputed; shares `70000000`.
6. **AC6 A correction that would make a later sell negative is refused (proposed; see owner decision).**
   - Input: F1 applied. Correct `TP-0001-2025-0002` to shares `30` (amount `90375`).
   - Expected: error `NEGATIVE_SHARES` naming `TP-0001-2025-0004` dated `2025-06-02` (sell of 60 with 30 held);
     neither the reversal nor the replacement is written; all pools unchanged.
7. **AC7 Redelivery of the correction events changes nothing.**
   - Input: after AC1, redeliver the reversal and replacement events to the consumer.
   - Expected: no pool changes; `ledgerline_pool_recompute_total` unchanged.
8. **AC8 Equivalence with full replay at scale (integration).**
   - Input: seed `42` generated year (L-01), then 20 random back-dated corrections and late activities, each 1 to 10
     months back.
   - Expected: after each, live pools equal a full replay (L-10 reports zero drift).

## Out of scope

- The incremental-versus-recompute benchmark (L-13, and on the cut list).
- Corrections that cross tax years in a way that needs tax judgment (for example a prior year already filed).
- Superficial-loss windows re-evaluated by a correction: covered only to the extent the L-07 engine runs during
  recompute; no new superficial-loss expected values here.
- UI for corrections.

## Needs owner decision

- **Where `NEGATIVE_SHARES` is checked for back-dated changes** (AC6). Refusing at the funnel requires the ledger to
  know holdings by effective date; the alternative is to accept and flag the pool as invalid on the calc side.
- **Reversal effective date** (L-04): AC1 assumes the reversal takes the original's effective date.
- **Does a recompute span other securities?** Superficial loss can link sales and repurchases only of identical
  property, so the proposal is one pool. If the owner models anything cross-security later, AC2 changes.
- **Pool history storage**: AC3 and AC4 assume a stored per-activity history. If only the current pool is stored, they
  reduce to AC1-style equality.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L09Spec.kt` › "L-09 AC1: …" |

## Change notes (Dev fills)

- …
