# L-03 Balanced, immutable ledger

**Status:** In QE  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
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

Written provisionally while the story was Draft, on the owner's instruction. Placeholders (docs/owner-inputs.md),
named in each test file's KDoc: C3 share scale 10^-6; C4 order (effective_date, source_seq, idempotency_key) with
`sourceSeq` a field on the activity; immutability by BOTH revoked grants on role `ledgerline_app` AND an append-only
trigger; share holdings are ledger lines in unit `SEC:<securityId>`; DomainPlugin stays in this story.
The API contract the tests compile against is the KDoc at the top of `L03LedgerSpec.kt`.

Files: spec `tests/spec/kotlin/dev/ledgerline/spec/L03LedgerSpec.kt` (S); property
`tests/property/kotlin/dev/ledgerline/property/BalanceInvariantPropertyTest.kt` (B) and
`ImmutabilityInvariantPropertyTest.kt` (I); support `tests/support/kotlin/dev/ledgerline/testsupport/`
(`L03Activities`, `Activities`, `ActivityStreams` generators, `LineMath` oracle, `LedgerDb` Testcontainers helper,
`ActivityCountPlugin`). [DB] = tagged `Integration`, Testcontainers `postgres:16.4-alpine`, needs `-PincludeIntegration`.

| AC | Test |
| --- | --- |
| AC1 | S › "L-03 AC1: CAD buy posts lines balanced in CAD and in SEC:SYN-AAA"; S › "L-03 AC1: CAD buy decreases TP-0001-NR1 CAD by 301745 and increases its SYN-AAA holding by 100000000 units"; S › "L-03 AC1: posting the CAD buy stores exactly one balanced entry for its key" [DB] |
| AC2 | S › "L-03 AC2: USD buy balances in USD and in SEC:SYN-USX and has no CAD line" |
| AC3 | S › "L-03 AC3: FX conversion balances in CAD and USD separately; account CAD -138000, USD +100000" |
| AC4 | S › "L-03 AC4: all nine activity types post lines balanced per unit"; S › "L-03 AC4: share units move only for BUY, SELL and TRANSFER_IN, by the activity's quantity"; S › "L-03 AC4: posting all nine activities stores nine entries, each balanced per unit" [DB] |
| AC5 | S › "L-03 AC5: unbalanced lines are refused with UNBALANCED, unit CAD, difference 1, and nothing is written" [DB]; B › "Invariant 1 (L-03 AC5 generalised): an entry unbalanced in one unit is refused, naming it (200 cases)" [DB] |
| AC6 | S › "L-03 AC6: as ledgerline_app, UPDATE, DELETE and TRUNCATE of ledger rows all fail; table hash unchanged" [DB] (expects SQLSTATE 42501 on every ledger table); S › "L-03 AC6: the append-only trigger makes UPDATE and DELETE fail even for the table owner; table hash unchanged" [DB] |
| AC7 | S › "L-03 AC7: ledger schema has no real, double or numeric column; amount and quantity columns are bigint" [DB] (also bans `money`) |
| AC8 | B › "Invariant 1 (L-03 AC8): every generated entry balances per currency and per security unit (1000 cases)" (prints the case count); B › "Invariant 1 (L-03 AC8): every entry stored in Postgres balances per unit, checked in SQL (100 streams)" [DB] |
| AC9 | I › "Invariant 3 (L-03 AC9): after every posting, every earlier row is present and byte-identical (200 cases)" [DB] |
| AC10 | S › "L-03 AC10: validate rejects an activity with accountId \"\" and gives a reason"; "L-03 AC10: applying AC4's nine activities in order counts 9 for TP-0001-NR1"; "L-03 AC10: applying the nine activities to two fresh states yields equal states"; "L-03 AC10: explain lists the nine keys in apply order" |
| AC11 | S › "L-03 AC11: AC1, then AC3, then AC2 get three distinct, strictly increasing sequences in commit order" [DB] |

Not tested: that `apply` "does no I/O" (only determinism is observable); TRUNCATE by the table owner (the trigger
placeholder covers UPDATE and DELETE only). AC4 keys other than AC1/AC3 are QE-chosen (`TP-0001-2025-0001`,
`-0004` to `-0009`); the story gives none. AC6 orders rows by full row text rather than primary key before hashing,
which gives the same equality check.

## Change notes (Dev fills)

- 2026-11-19 · dev · Round 0 implementation, built provisionally on placeholders C3 and C4 (`// OWNER-INPUT` markers).
  `core`: `Activity.sourceSeq` (last parameter, default 0), `Validation`, `DomainPlugin` (no I/O). `ledger`: pure
  `Posting.linesFor` and `Posting.requireBalanced` (exact arithmetic), `LedgerRepository` (one transaction per append,
  advisory lock so sequence follows commit order), `LedgerMigrations`. Flyway `V2__ledger_entries_and_lines.sql`:
  `ledger.entry`, `ledger.line` (bigint amounts), role `ledgerline_app` (SELECT/INSERT only), append-only statement
  trigger on UPDATE, DELETE and TRUNCATE. App now migrates through `LedgerMigrations`. Testcontainers 1.20.4 to 1.21.3.
  Chart of accounts, sequence and trigger choices in draft ADR 0005.
