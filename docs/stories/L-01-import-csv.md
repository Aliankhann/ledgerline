# L-01 Import an activity CSV into canonical activities

**Status:** QE passed (round 2), awaiting owner approval  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 2 · **Branch:** `L-01-import-csv` · **Round:** 0 / 3

## Story

As a self-directed investor, I want to load my activity history from a CSV file into validated canonical activities,
so that every later number is built from inputs that were checked once, with any bad row named by line and reason.

As a reviewing engineer, I want a seeded synthetic generator that writes the same file for the same seed, so that I
can reproduce every demo and benchmark without anyone's real data.

## Why it matters

Ledgerline's input is a Wealthsimple-style activity CSV plus synthetic data; broker exports are the usual starting
point for ACB tools, so the parser must fit that shape. See [`docs/problem/evidence.md`](../problem/evidence.md):
E-05 (ACB tools need per-broker export converters) and E-30 (Wealthsimple offers an "Activities Export" and a
"Monthly Statement CSV" that third-party ACB tools import). Not sourced: the 1,000-activity cap, "web-only" and the
real column names (still assumption A-10).

## Background: the Ledgerline canonical CSV (v1)

**The real Wealthsimple column names could not be confirmed from a primary source.** Wealthsimple's help centre is
reported (in search-result summaries; the page could not be opened from this session) to say the activity statement
shows transaction date, settlement date, type, description, debit or credit, amount and currency, without listing
header strings. One user forum post reports this header, which is unverified and may differ by export type:

`transaction_date,settlement_date,account_id,account_type,activity_type,activity_sub_type,direction,symbol,name,currency,quantity,unit_price,commission,net_cash_amount`

Sources (secondary, to re-open before relying on them):
[Portfolio Performance forum thread](https://forum.portfolio-performance.info/t/cvs-import-where-all-values-are-in-account-currency/38925),
[Wealthsimple help article 35654428540571](https://help.wealthsimple.com/hc/en-ca/articles/35654428540571),
[AdjustedCostBase.ca: importing from Wealthsimple](https://www.adjustedcostbase.ca/blog/?p=1710).

So this story defines a **Ledgerline canonical CSV** that the synthetic generator writes and the parser reads. Mapping
the real Wealthsimple export onto it needs the owner's own export and is a manual owner step (see "Needs owner
decision"). The column list below is a BA proposal; the Dev may refine names, and the owner approves the final format.

- UTF-8, optional UTF-8 BOM ignored, comma delimiter, RFC 4180 quoting, LF or CRLF line endings, header row required.
- Line numbers in error reports are 1-based physical lines of the file; the header is line 1.
- Columns, in this order:

| Column | Required | Format |
| --- | --- | --- |
| `idempotency_key` | yes | 1–64 chars from `A–Z a–z 0–9 . _ : -` |
| `taxpayer_id` | yes | e.g. `TP-0001` |
| `account_id` | yes | e.g. `TP-0001-NR1` |
| `account_type` | yes | `NON_REGISTERED`, `TFSA` or `RRSP` |
| `activity_type` | yes | `BUY`, `SELL`, `DIVIDEND`, `RETURN_OF_CAPITAL`, `FEE`, `DEPOSIT`, `WITHDRAWAL`, `TRANSFER_IN`, `FX_CONVERSION` |
| `effective_date` | yes | ISO `YYYY-MM-DD` (trade date) |
| `settlement_date` | no | ISO `YYYY-MM-DD`, not before `effective_date` |
| `security_id` | for `BUY`, `SELL`, `DIVIDEND`, `RETURN_OF_CAPITAL`, `TRANSFER_IN` | e.g. `SYN-AAA` |
| `quantity` | for `BUY`, `SELL`, `TRANSFER_IN` | positive decimal, at most 6 decimal places (pending the share scale decision) |
| `currency` | yes | `CAD` or `USD` |
| `amount` | yes | non-negative decimal, at most 2 decimal places; gross cash amount; direction comes from `activity_type` |
| `commission` | no (default `0`) | non-negative decimal, at most 2 decimal places |
| `counter_currency` | for `FX_CONVERSION` only | `CAD` or `USD`, different from `currency` |
| `counter_amount` | for `FX_CONVERSION` only | positive decimal, at most 2 decimal places |
| `description` | no | free text |

`recorded_at` is not a CSV column; the system sets it on ingest.

Worked examples below assume the share scale of 10⁻⁶ share proposed in ADR 0002 (so `100` shares = `100000000`
units). If the owner picks another scale, the BA updates these values before approval.

## Acceptance criteria

Each criterion is testable and has a concrete input and expected output. Function, command and code names are
proposals the Dev may refine; QE tests the names the owner approves.

1. **AC1 Valid canonical file parses into canonical activities.**
   Given this file:
   ```
   idempotency_key,taxpayer_id,account_id,account_type,activity_type,effective_date,settlement_date,security_id,quantity,currency,amount,commission,counter_currency,counter_amount,description
   TP-0001-2025-0001,TP-0001,TP-0001-NR1,NON_REGISTERED,DEPOSIT,2025-01-06,2025-01-06,,,CAD,10000.00,0.00,,,Initial deposit
   TP-0001-2025-0002,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-01-07,2025-01-08,SYN-AAA,100,CAD,3012.50,4.95,,,
   TP-0001-2025-0003,TP-0001,TP-0001-NR1,NON_REGISTERED,FX_CONVERSION,2025-01-09,2025-01-09,,,CAD,1380.00,0.00,USD,1000.00,
   ```
   - Expected: 3 activities, 0 rejections. Activity 2 has `idempotencyKey=TP-0001-2025-0002`, `activityType=BUY`,
     `effectiveDate=2025-01-07`, `settlementDate=2025-01-08`, `securityId=SYN-AAA`, `quantity=100000000` units,
     `currency=CAD`, `amountMinor=301250`, `commissionMinor=495`. Activity 3 has `amountMinor=138000` CAD and
     `counterAmountMinor=100000` USD. Activity 1 has `amountMinor=1000000`.
2. **AC2 Every bad row is rejected with its line number and a reason code; good rows are still returned.**
   Given the header from AC1 followed by these lines (line numbers shown on the left, not part of the file):
   ```
   2  TP-0001-2025-0010,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-03,,SYN-AAA,10,CAD,301.255,0.00,,,
   3  TP-0001-2025-0011,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-30,,SYN-AAA,10,CAD,301.25,0.00,,,
   4  TP-0001-2025-0012,TP-0001,TP-0001-NR1,NON_REGISTERED,SHORT,2025-02-04,,SYN-AAA,10,CAD,301.25,0.00,,,
   5  TP-0001-2025-0013,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-04,,SYN-AAA,10,EUR,301.25,0.00,,,
   6  TP-0001-2025-0014,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-04,,,10,CAD,301.25,0.00,,,
   7  TP-0001-2025-0015,TP-0001,TP-0001-NR1,NON_REGISTERED,SELL,2025-02-04,,SYN-AAA,-5,CAD,150.00,0.00,,,
   8  TP-0001-2025-0016,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-05,2025-02-04,SYN-AAA,10,CAD,301.25,0.00,,,
   9  TP-0001-2025-0017,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-05,,SYN-AAA,0.0000001,CAD,0.01,0.00,,,
   10 TP-0001-2025-0018,TP-0001,TP-0001-NR1,NON_REGISTERED,BUY,2025-02-05,,SYN-AAA,10,CAD,301.25,0.00,,,
   ```
   - Expected: 1 activity (line 10) and 8 rejections, exactly:
     | Line | Reason code | Field |
     | --- | --- | --- |
     | 2 | `AMOUNT_TOO_PRECISE` | `amount` |
     | 3 | `INVALID_DATE` | `effective_date` |
     | 4 | `UNKNOWN_ACTIVITY_TYPE` | `activity_type` |
     | 5 | `UNSUPPORTED_CURRENCY` | `currency` |
     | 6 | `MISSING_FIELD` | `security_id` |
     | 7 | `NON_POSITIVE_QUANTITY` | `quantity` |
     | 8 | `SETTLEMENT_BEFORE_EFFECTIVE` | `settlement_date` |
     | 9 | `QUANTITY_TOO_PRECISE` | `quantity` |
   - Each rejection also carries a human-readable message (wording not tested).
3. **AC3 A file with a wrong header is rejected as a whole.**
   - Input: the AC1 file with the `commission` column removed from the header only.
   - Expected: 0 activities; exactly one file-level error `HEADER_INVALID` naming the missing column `commission`.
     No row-level parsing is attempted.
4. **AC4 Formatting variants parse identically.**
   - Input: the AC1 file saved four ways: LF endings; CRLF endings; with a UTF-8 BOM; with the `description` field of
     line 2 quoted as `"Initial deposit, first"` (containing a comma).
   - Expected: all four yield the same 3 activities as AC1 (with the description text differing only in the fourth
     case), and 0 rejections.
5. **AC5 Header-only and empty files.**
   - Input A: the header line only. Expected: 0 activities, 0 rejections, no error.
   - Input B: a zero-byte file. Expected: file-level error `HEADER_INVALID`, 0 activities.
6. **AC6 Round trip.**
   - Input: any file written by the generator (AC7) and the AC1 file.
   - Expected: `serialize(parse(file))` is byte-identical to `file` (LF endings, no BOM, the canonical column order).
7. **AC7 Same seed gives a byte-identical file.**
   - Input: run the generator twice with seed `42`, 5 taxpayers, year 2025 (proposed command:
     `./gradlew :ingest:run --args="generate --seed 42 --taxpayers 5 --year 2025 --out build/synthetic-42.csv"`).
   - Expected: the two files have the same SHA-256. A third run with seed `43` produces a different SHA-256.
8. **AC8 Generator output does not depend on the machine's locale or time zone.**
   - Input: seed `42`, 5 taxpayers, year 2025, generated once with `TZ=UTC` and JVM locale `en-CA`, and once with
     `TZ=America/Toronto` and JVM locale `fr-CA`.
   - Expected: identical SHA-256 (no locale-specific decimal commas, no time-zone-shifted dates).
9. **AC9 Generator output is valid and covers every activity type.**
   - Input: the seed `42` file from AC7.
   - Expected: parsing it gives 0 rejections; every one of the 9 activity types appears at least once; every
     `effective_date` is in 2025; every `idempotency_key` is unique in the file; and no `SELL` row's quantity exceeds
     the shares held in that account and security at that date, counting rows in (effective date, file order)
     order (invariant 7).
10. **AC10 Generator uses only synthetic identifiers.**
    - Input: the seed `42` file.
    - Expected: every `security_id` starts with `SYN-`, every `taxpayer_id` matches `TP-\d{4}`, and every
      `account_id` starts with its row's `taxpayer_id`.

## Out of scope

- Mapping the real Wealthsimple export columns (owner step; see below). Any committed fixture from the owner's export.
- Submitting parsed activities to the ledger, and duplicate handling across uploads (L-02).
- Duplicate `idempotency_key` values inside one file (L-02 decides whether that is a duplicate or a conflict).
- ACB, FX rates or any tax logic (L-06, L-07, L-08).
- Activity types outside the nine listed; options, crypto, margin.
- Wealthsimple's 1,000-activity download cap (relevant to the real export only).

## Needs owner decision

- **Real Wealthsimple mapping (manual owner step).** Export your own activity CSV, compare its header with the
  forum-reported one above, and record the real header (header line only, no personal rows) in this story or an ADR.
  Your own export must not be committed. Decide whether the Wealthsimple adapter is part of this story or a follow-up.
- **Proposed split (needs approval, would exceed 14 stories).** Parser plus generator plus round trip is close to a
  full day. Proposal: L-01a canonical parser (AC1–AC6) and L-01b seeded generator (AC7–AC10). Alternatively keep one
  story and accept that day 2 is fully used by it.
- **Share fixed-point scale** (ADR 0002). Examples assume 10⁻⁶ share. This sets the `QUANTITY_TOO_PRECISE` boundary.
- **Partial-file policy.** The parser returns good rows and rejections together. When a file is imported, are good
  rows posted while bad rows are reported (partial), or is the whole file refused if any row is bad (all-or-nothing)?
- **How CSV rows get an idempotency key when the real export has no id column** (ADR 0001 open question). Options:
  derive it from a hash of the row's fields plus its occurrence count within the file, or require the owner to add one.
- **`TRANSFER_IN` cost.** Is `amount` on a transfer-in the declared book cost? Whether it becomes ACB is a tax
  decision for L-06.
- **Synthetic tickers.** The generator uses `SYN-` ids rather than real tickers. Confirm.

## Test map (QE fills)

All tests are in `tests/spec/kotlin/dev/ledgerline/spec/L01ImportCsvSpec.kt`; fixtures in `tests/fixtures/l01/`.
The spec's KDoc holds the API contract the tests compile against (provisional; share scale 10^-6 and "parser returns
good rows plus rejections" are OWNER-INPUT placeholders). The Gradle command `:ingest:run --args="generate ..."` is
NOT tested; AC7-AC10 test the function `SyntheticGenerator.generate(seed, taxpayers, year)`.

| AC | Test |
| --- | --- |
| AC1 | "L-01 AC1: valid canonical file parses into canonical activities" (`ac1_valid.csv`) |
| AC2 | "L-01 AC2: every bad row is rejected with line number and reason code; good rows still returned" (`ac2_bad_rows.csv`) |
| AC3 | "L-01 AC3: wrong header rejects the whole file, naming the missing column" |
| AC4 | "L-01 AC4: LF, CRLF, BOM and quoted-comma variants parse identically" |
| AC5 | "L-01 AC5: header-only file is empty success; zero-byte file is HEADER_INVALID" |
| AC6 | "L-01 AC6: serialize(parse(file)) is byte-identical for the AC1 file and generated files" |
| AC7 | "L-01 AC7: same seed gives byte-identical file; different seed differs" |
| AC8 | "L-01 AC8: generator output does not depend on default locale or time zone" (sets Locale/TimeZone defaults in-test, restores after; does not set the JVM `TZ` env var) |
| AC9 | "L-01 AC9: generator output is valid, covers all activity types, unique keys, no oversell" |
| AC10 | "L-01 AC10: generator uses only synthetic identifiers" |

## Change notes (Dev fills)

- Provisional build while the story is Draft. Domain types and fixed-point helper in `core/` (`Activity`, `FixedPoint`,
  `Scale`); `CanonicalCsv` and `SyntheticGenerator` in `ingest/`. No Double/Float/BigDecimal; decimals parse by string.
- `OWNER-INPUT: C3` share scale (10^-6) is the single constant `Scale.SHARE_DECIMALS`. The "good rows plus rejections"
  parser policy is also an OWNER-INPUT placeholder.
- Extra reason codes beyond the story: WRONG_COLUMN_COUNT, INVALID_NUMBER, NEGATIVE_AMOUNT, NON_POSITIVE_AMOUNT,
  UNKNOWN_ACCOUNT_TYPE, INVALID_IDEMPOTENCY_KEY, INVALID_COUNTER_CURRENCY, COMMISSION_/COUNTER_AMOUNT_TOO_PRECISE.
  One rejection per row (the first failing check).
- Generator: `SplittableRandom(seed)`, no clock/locale; each taxpayer gets a scripted prefix covering all 9 types, then
  random events, simulating holdings so sells never exceed shares held.
- Not done: the `:ingest:run --args="generate ..."` command (optional; untested).
