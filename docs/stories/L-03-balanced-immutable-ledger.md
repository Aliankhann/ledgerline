# L-03 Balanced, immutable ledger

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 3 · **Branch:** `L-03-balanced-immutable-ledger` · **Round:** 0 / 3

## Story

As a reviewing engineer, I want every activity to post as one balanced double-entry ledger entry that can never be
edited or deleted, so that the ledger is a trustworthy source of truth that every derived number can be replayed from.

## Why it matters

Ledgerline is inspired by Wealthsimple's published Book of Record: one validated funnel into an append-only
double-entry ledger. See [`docs/problem/evidence.md`](../problem/evidence.md): E-16 to E-18 (the ledger records "in
double-entry form, append-only, with reversal symmetry"), and its "differences" list (Wealthsimple validates before
the ledger; Ledgerline's `ledger/` also owns idempotency).

## Notes for QE and Dev

- Invariants covered: **1 Balance** (AC1–AC4, AC8) and **3 Immutability** (AC5–AC6, AC9). Invariant 8 is touched by
  AC7. Reversal symmetry (invariant 4) is L-04.
- **The `DomainPlugin` interface is defined on day 3** alongside this story (build plan, step 3): validate an
  activity, apply it to state, explain a result. AC10 covers only its contract, not ACB.
- "Unit" below means the thing a line is measured in: a currency (`CAD`, `USD`) or a security (`SYN-AAA`) for share
  lines. Balance is checked per unit. The chart of accounts and line layout are the Dev's to propose in a draft ADR;
  tests assert balance per unit, not specific account names.
- Quantities assume the 10⁻⁶ share scale proposed in ADR 0002 (`100` shares = `100000000` units).
- Function, table and error names are proposals the Dev may refine; QE tests the names the owner approves.

## Acceptance criteria

1. **AC1 A CAD buy posts one balanced entry.**
   - Input: canonical activity `TP-0001-2025-0002`: `BUY`, taxpayer `TP-0001`, account `TP-0001-NR1`
     (`NON_REGISTERED`), `effectiveDate=2025-01-07`, `SYN-AAA`, quantity `100000000` units, `CAD`,
     `amountMinor=301250`, `commissionMinor=495`.
   - Expected: exactly one ledger entry for that idempotency key. Grouping its lines by unit: in `CAD`, total debits =
     total credits; in `SYN-AAA`, total debits = total credits. The cash account `TP-0001-NR1` CAD balance decreases by
     `301745` and its `SYN-AAA` holding increases by `100000000` units.
2. **AC2 A USD buy balances in USD.**
   - Input: `TP-0001-2025-0020`: `BUY`, `TP-0001-NR1`, `2025-03-03`, `SYN-USX`, quantity `25000000` units, `USD`,
     `amountMinor=123400`, `commissionMinor=495`.
   - Expected: one entry; per-unit debits = credits in `USD` and in `SYN-USX`; no `CAD` line exists on the entry
     (FX conversion to CAD is calculation-side, L-08).
3. **AC3 An FX conversion balances in each currency separately.**
   - Input: `TP-0001-2025-0003`: `FX_CONVERSION`, `TP-0001-NR1`, `2025-01-09`, `CAD` `amountMinor=138000`,
     `counterCurrency=USD`, `counterAmountMinor=100000`.
   - Expected: one entry; `CAD` debits = `CAD` credits; `USD` debits = `USD` credits. Account CAD balance −`138000`,
     account USD balance +`100000`.
4. **AC4 All nine activity types post balanced entries.**
   - Input: one valid activity of each type for `TP-0001-NR1` (deposit `1000000` CAD; buy as AC1; sell 40 shares of
     `SYN-AAA` for `amountMinor=128000`, commission `495`; dividend `1234` CAD on `SYN-AAA`; return of capital `2000`
     CAD on `SYN-AAA`; fee `995` CAD; withdrawal `50000` CAD; transfer-in 10 shares of `SYN-BBB` with `amountMinor=45000`;
     FX conversion as AC3), all dated in January to March 2025.
   - Expected: nine entries, each balanced per unit.
5. **AC5 An unbalanced entry is refused and nothing is written.**
   - Input: a direct call to the ledger posting API (bypassing activity validation) with lines: debit `CAD 301250` to
     one account, credit `CAD 301249` to another.
   - Expected: error `UNBALANCED` naming unit `CAD` and the difference `1`; the count of rows in every `ledger` table
     is unchanged.
6. **AC6 Ledger rows cannot be updated, deleted or truncated through the application's database role.**
   - Input: after AC1, connected as the application's database user, run each of:
     `UPDATE` setting the amount of one of AC1's lines to `1`; `DELETE` of that line; `DELETE` of AC1's entry;
     `TRUNCATE` of the lines table.
   - Expected: every statement fails with a database error; afterwards a SHA-256 over all rows of every `ledger`
     table (ordered by primary key) equals the value taken before the attempts.
7. **AC7 No floating-point or decimal columns in the ledger schema.**
   - Input: query `information_schema.columns` for schema `ledger`.
   - Expected: no column has data type `real`, `double precision` or `numeric`. Amount and quantity columns are
     `bigint`.
8. **AC8 Property test, invariant 1 (Balance).**
   - Input: generated streams of valid activities (all nine types, CAD and USD, random amounts from `1` to
     `10000000000` minor units), at least 1,000 generated cases.
   - Expected: for every entry and every unit, debits = credits. Test output reports the number of cases run.
9. **AC9 Property test, invariant 3 (Immutability).**
   - Input: generated sequences of postings, at least 200 cases. After each posting, snapshot every ledger row.
   - Expected: every row present in snapshot *n* is present and byte-identical in snapshot *n+1*; rows are only added.
10. **AC10 `DomainPlugin` contract exists and a trivial plugin runs on it.**
    - Input: a test-only plugin in `tests/support/` (QE writes it) that counts activities per account: `validate`
      rejects an activity whose `accountId` is blank, `apply` increments a count, `explain` returns the list of
      idempotency keys counted.
    - Expected: `validate` on an activity with `accountId=""` returns a rejection with a reason; applying AC4's nine
      activities in order yields count `9` for `TP-0001-NR1`; applying them twice to two fresh states yields equal
      states (`apply` is deterministic and does no I/O); `explain` lists the nine keys in apply order.
11. **AC11 Each entry gets a unique, increasing sequence number.**
    - Input: post AC1, then AC3, then AC2 one after another.
    - Expected: three distinct sequence numbers, strictly increasing in commit order.

## Out of scope

- Reversals and corrections (L-04).
- Idempotency key and payload-hash checks, and the HTTP ingestion API (L-02).
- Outbox and publishing (L-05).
- Rejecting sells that exceed shares held (invariant 7) if the owner places that check in calculation rather than the
  ledger (see below).
- ACB, FX rates or any tax logic.

## Needs owner decision

- **Share fixed-point scale and ACB unit** (ADR 0002). Values above assume 10⁻⁶ share.
- **Chart of accounts and share lines.** Do share holdings live in the ledger as lines in a security unit (as assumed
  here), or does the ledger hold cash only and leave positions to calculation? This changes AC1, AC2 and AC4.
- **How immutability is enforced** (ADR 0003): revoked `UPDATE`/`DELETE`/`TRUNCATE` grants on the app role, a trigger,
  or both. A trigger also blocks the table owner; grants alone do not.
- **What "sequence" means for ordering** (invariants 5 and 6). If sequence is assigned on arrival (AC11), two
  activities with the same effective date arriving in a different order get a different sequence, which can break
  order independence (for example a same-day buy and sell). Proposal: calculation orders by effective date, then a
  deterministic tie-breaker from the activity itself (for example source row order, then idempotency key), and the
  ledger sequence is only a storage order. Needs an ADR.
- **Where invariant 7 is enforced** (ledger at posting time, or calculation). Affects L-04 and L-09.
- **Size.** Schema, posting rules for nine types, immutability and the `DomainPlugin` interface may exceed half a day.
  If so, the owner could move AC10 into L-04 or L-14 rather than adding a story.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L03Spec.kt` › "L-03 AC1: …" |

## Change notes (Dev fills)

- …
