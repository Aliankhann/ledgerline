# L-18 Your tax year: realised gains summary and per-sale table

**Status:** In QE
**Day:** N · **Branch:** `l-18` · **Round:** 0 / 3

## Story

As a _self-directed investor_, I want to pick a tax year and see one row per sale in the shape of Schedule 3, plus
totals for gains, losses, net and the taxable amount, and download the rows as CSV, so that I can fill in my return or
hand the table to an accountant. As a _reviewing engineer_, I want every figure on the page to come from the existing
ACB replay and to trace to ledger entry ids, with no tax rule that is not already in `docs/tax-model.md`.

## Why it matters

The taxpayer must work out proceeds, ACB and outlays per disposition and report them on Schedule 3 (CRA "Calculating and
reporting your capital gains and losses", the page behind fixture `cra-xyz-gain`). See
[`docs/problem/evidence.md`](../problem/evidence.md). Evidence that investors want a year view specifically is thin: the
cited sources show the calculation burden (E-36, E-37, as reported by the owner's LLM, not re-fetched), not demand for
this screen. The BA has not found a sourced quote for that and does not invent one.

## Code facts this story reuses (no new tax logic)

- Sale records: `GainLine` (kind, activityId, taxDate, `gain`, `gainCents`, `acbSold`, ruleIds) `calc/AcbPool.kt:34-45`,
  carried by `PoolStep.Applied.gains` `AcbPool.kt:66-70`. `GainKind` is `DISPOSITION` or `DEEMED_GAIN_ROC`.
- Superficial loss: `SaleResult` (`gainBeforeRule`, `denied`, `reportedGain`, `outcome`, `triggers`, `reason`, `ruleIds`;
  internal units, `reportedGain = gainBeforeRule + denied`) `calc/Superficial.kt:13-23`, reached via `SlReplay.sales`
  `Superficial.kt:25-31`.
- Tax date: `CaIta2026v1.taxDate` = `settlementDate ?: effectiveDate` `calc/TaxRuleSet.kt:92`. Rounding to cents:
  `toReportedCents`, half up, away from zero on exact halves (OWNER-INPUT C6/C7) `TaxRuleSet.kt:83-84`.
- Rejected sells (`Rejection`, `AcbPool.kt:52-61`) are not dispositions and never appear as rows.
- `GainLine` carries no proceeds and no outlays. Proceeds (`amountMinor`) and the selling commission (`commissionMinor`)
  are on the `Activity` (`core/Activity.kt:33-34`), joined by `activityId`. This is a join of existing data, not a new
  rule; Dev confirms the join reproduces `gainBeforeRule` (AC1) and says so in change notes.
- Existing route pattern and live pool source: `app/AcbVsBookRoutes.kt:35-48,64`; UI pattern `ui/src/AcbVsBook.tsx`,
  `ui/src/money.ts` (`formatMoney`, integer math).

## Acceptance criteria

Tax numbers come only from existing fixtures in `tests/fixtures/cra/`; this story adds none. Where no published figure
exists the criterion states a structural property and says so. Endpoint paths below are proposals (see decisions).

1. **AC1 One row reproduces the CRA XYZ example.**
   - Input: fixture `cra-xyz-gain.properties` loaded as activities (SYN-CRA-XYZ, BUY 2024-01-15, SELL 2025-03-13), then
     `GET /v1/tax-year/{taxpayer}/2025`.
   - Expected: exactly 1 row. taxDate `2025-03-13`, security `SYN-CRA-XYZ`, shares `400000000` units, proceeds `650000`
     cents, outlays and expenses `6000`, ACB of shares sold `400000`, gain `244000`, superficialLossDenied `0`, notes
     empty, entry ids for the SELL (non-empty). Totals: gains `244000`, losses `0`, net `244000`, total superficial losses
     denied `0`. The same request for 2024 returns 0 rows and all totals `0`.
   - Source: fixture `t2.gross.cents=650000`, `t2.commission.cents=6000`, `t1.gross.cents=400000` (ACB sold),
     `t2.expected.gain.cents=244000` (CRA "XYZ" worked example, URL in the file: 6,500 - 60 - 4,000 = 2,440).
2. **AC2 Taxable capital gain on the CRA example.**
   - Input: as AC1.
   - Expected: taxable capital gain `122000` cents at the 1/2 inclusion rate.
   - Source: the fixture header quotes the CRA "taxable portion $1,220" for this example; `docs/tax-model.md` (Not
     modeled) quotes ITA-38 as "1/2 of the taxpayer's capital gain". The fixture notes the CRA taxable figure was "not
     tested" before, so this is the first test of it. The inclusion rate is a single constant, not a new rule per row.
3. **AC3 Net, taxable and odd cents (OWNER DECISION).**
   - Input: a year whose net is an odd number of cents, for example net `1001` cents.
   - Expected: the response does not silently pick a rounding. Until the owner decides, the story fixes only that taxable
     is an integer number of cents, that `taxable = net / 2` is exact when net is even, and that a negative net gives
     taxable `0` (a net capital loss is reported separately, not as a negative taxable gain; see Out of scope).
     Whether `1001` gives `500` or `501` is not specified here: no published worked example was found, so no test may
     assert it. Dev must not implement a rounding for it until the owner chooses.
   - Source: none for the odd-cent case (flagged); even case as AC2.
4. **AC4 Superficial loss row.**
   - Input: fixture `cra-ti-2004-0073011e5-partial-sl.properties` (S=50, P=20, B=20) loaded, the year of its sale
     requested.
   - Expected: the sale row has `superficialLossDenied > 0`, a note naming the rule (`SL-PARTIAL`) and the entry ids of
     the sale and of the substituted buy (from the sale's `SlTrigger`s), `denied x 50 = lossBeforeRule x 20` holds, row
     gain = `gainBeforeRule + denied` (the allowed loss), and the totals' "superficial losses denied" equals the sum of the
     row amounts.
   - Source: the fixture's `expected.*.units` ratio 20/50 (CRA TI 2004-0073011E5, third-party host, caveat in the
     fixture: may not reflect the CRA's current position). Its dollar amounts are test assumptions; this AC asserts the
     ratio and the ids, never a dollar amount.
5. **AC5 Structural properties (no published figure needed).** For any set of activities and any year:
   - sum of row gains = net; total gains = sum of positive rows; total losses = sum of negative rows (shown as a
     negative number); gains + losses = net;
   - rows are ordered by tax date, then sequence, then entry id, and an activity with a settlement date is placed by that
     date;
   - every row's entry ids exist in the ledger; a rejected oversell (invariant 7) never yields a row;
   - arrival order and replay do not change the bytes of the response (invariants 5 and 6);
   - all amounts are integer cents; totals are sums of the rounded row cents (rounding only at the reporting boundary,
     invariant 8). Rows whose rounded parts do not add up exactly are flagged below, not hidden.
6. **AC6 Year boundary uses the tax date.**
   - Input: a SELL with `effectiveDate` 2025-12-30 and `settlementDate` 2026-01-02, and a SELL with `effectiveDate`
     2026-01-02 and no settlement date.
   - Expected: the first appears in year 2026 only, with taxDate `2026-01-02`; the second also appears in 2026. A sell
     on 2025-12-31 with no settlement date appears in 2025 only. Each row shows the tax date it was placed by.
   - Source: the repo's `TaxRuleSet.taxDate` (settlement date, else effective date). No published CRA citation has been
     checked by the BA for the settlement-date convention in this story; see Owner decisions.
7. **AC7 Empty, bad and unknown inputs.** Unknown taxpayer: HTTP 404, body names the key. A taxpayer with activities but
   no sale in the year: HTTP 200, `rows: []`, all totals `0`. A non-numeric or out-of-range year (for example `20x5`,
   `0`, `10000`): HTTP 400 naming the parameter. Securities with only L-16 warnings and no pool contribute no rows, and
   the response lists the warnings (`DATA-GAP-OVERSELL`, `DATA-GAP-TRANSFER-NO-COST`) with their entry ids so a gap is
   not read as "no gain".
8. **AC8 CSV download equals the JSON.**
   - Input: AC1's data, `GET /v1/tax-year/{taxpayer}/2025.csv` (path proposal).
   - Expected: `Content-Type: text/csv`, one header row, one row per JSON row in the same order, columns in the same
     order as the table, then a totals line whose values equal the JSON totals digit for digit. Quoting follows RFC 4180.
     A security or note field starting with `=`, `+`, `-` or `@` is prefixed with `'` so a spreadsheet cannot run it.
     (The injection rule is a BA proposal, not a published source.) Two requests give byte-identical files.
9. **AC9 Read-only.** The endpoint changes nothing: ledger row counts and the output hash of the replay are identical
   before and after any number of requests (invariant 3). No new tables, no write path.
10. **AC10 UI "Your year" tab.** A new tab with a year picker (years that have at least one row, newest first; default the
    latest), summary cards (Total gains, Total losses, Net, Taxable capital gain, Superficial losses denied), the per-sale
    table, and a "Download CSV" link. Money uses `formatMoney` (integer math, no floats). Losses are shown with a minus
    sign. A visible "Not included" panel lists the Out of scope items so the totals are not read as a full return.
    Verified with `npm run typecheck && npm run build` plus a component test; QE decides the test tool.

## Table shape (proposal; Schedule 3 shape, not a copy of the form)

Tax date · Security · Shares · Proceeds of disposition · ACB of shares sold · Outlays and expenses · Gain or loss ·
Superficial loss denied · Notes (rule ids and entry ids). Gain or loss is the reported gain after the denial. Proceeds,
ACB and outlays are shown as the engine has them; the on-screen identity is proceeds - outlays - ACB (+ denied) = gain.

## Out of scope

Shown in the UI as "Not included":

- Other income types: dividends, interest, ROC income, fees, T-slip boxes.
- T5008 or any broker slip reconciliation (no import of slips; synthetic data and the owner's own CSV only).
- Foreign property reporting (T1135) and any foreign-currency gain or loss on cash (not modeled, `docs/tax-model.md`).
  USD securities are shown in CAD as the engine already converts them (L-08).
- Registered accounts (TFSA, RRSP): their dispositions are not reported. The row set is non-registered accounts only;
  Dev confirms what the pools already contain and says so. A loss lost by repurchase in a registered account still shows
  as denied on the non-registered sale (`SL-REGISTERED`).
- Carry-forward and carry-back of net capital losses, the capital gains reserve, the lifetime exemption, tax payable and
  marginal rates. No tax payable figure.
- New tax rules or any change to calculation; editing data; PDF output; filing.
- Multiple taxpayers at once; the 14-story cap (this is story 18; needs explicit owner approval, as L-17).

## Needs owner decision

- **Tax judgment: odd-cent half inclusion (AC3).** Taxable = net x 1/2 leaves a half cent when net is odd. Round half up,
  half down, truncate, or carry 0.5 and round only the sum? No published example found. Nothing is implemented until you
  choose; the choice then goes in an ADR with its source.
- **Tax judgment: inclusion rate.** Fixed at 1/2 for every year (`docs/tax-model.md` quotes ITA-38 as 1/2), or a
  per-year table? The BA did not verify whether the rate has differed for any year and does not assume.
- **Tax judgment: year boundary (AC6).** The brief says the tax date (settlement, else effective) decides the year. The BA
  has not checked a CRA page for the settlement-date convention; the rule is the one already in code. Confirm it, and
  whether a late-December trade settling in January moves to the next year.
- **Tax judgment: net capital loss.** With a negative net, taxable is shown as `0` and the loss as "net capital loss
  for the year", with no carry-forward. Confirm.
- **Tax judgment: `DEEMED_GAIN_ROC` lines (ROC pushing ACB below zero).** Is it a row (with proceeds 0, shares 0, note
  "deemed gain, ROC"), a separate section, or excluded? It is a capital gain but not a sale. Default proposed: separate
  row kind, included in the totals, labelled.
- **Rounding per row.** Each row is rounded to cents on its own and totals are sums of those cents (AC5). A row's
  proceeds - outlays - ACB (+ denied) can then differ from its gain by 1 cent, and the sum of rounded rows can differ from
  the rounded sum of the internal amounts. Accept that and show a note, or make the rows add by construction? Existing
  C6/C7 rounding rule stays as is.
- **CSV money format.** Integer cents (`244000`) or dollars with two decimals (`2440.00`, built with integer math)?
  Proposal: dollars with two decimals for a human spreadsheet, plus a documented header. Locale-free (`.` separator).
- **Superficial loss display.** One note listing sale and substituted-buy entry ids (AC4), or one row per trigger? Also:
  a TFSA or RRSP repurchase denies the loss but its buy may not be a timeline entry (see L-17 B-032).
- Paths and route param names (`{taxpayer}` as on the existing route) and the live pool source (C29, `Main.kt` wired with
  an empty pool map) must be settled first, as for L-17.
- Labels, card names, "Your year" and the "Not included" wording are proposals.
- Beyond the 14-story cap: needs explicit owner approval.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L18Spec.kt` › "L-18 AC1: …" |

## Change notes (Dev fills)

- Added `calc/TaxYearReport.kt` (pure; `TaxYearRow`, `TaxYearTotals`, `TaxYear`, `build`, `yearsWithRows`,
  `taxableCents`), `app/TaxYearRoutes.kt` (`taxYearFromActivities`: `GET /v1/tax-year/{taxpayer}`, `/{year}`,
  `/{year}.csv`, read-only, wired in `Main.kt` from `activities::effectiveActivities`) and `ui/src/YourYear.tsx`
  ("Your year" section in `App.tsx`: year picker, five cards, per-sale table, Download CSV link, Not included panel,
  warnings list). No migration, no new tax rule, no change to existing calc.
- Source of every figure: `AcbEngine.replaySuperficial` sales (`gainBeforeRule`, `denied`, `reportedGain`, triggers),
  ACB sold from the sale's own pool-history entry (`sharesDelta < 0`), rounded once with `toReportedCents`. Proceeds
  and selling commission are joined from the SELL `Activity` by activity id. The join reproduces `gainBeforeRule`
  exactly: the engine computes `(amount - commission) x 10^4 - acbSold`; on the CRA XYZ fixture the row identity
  650000 - 6000 - 400000 = 244000 holds. Warnings come from `DataGapWarnings.detect`.
- Row set: non-registered pools only (the SL replay assesses `NON_REGISTERED` sales only); rejected sells and
  zero-quantity sells give no row.
- Provisional values awaiting the owner (each marked `// OWNER-INPUT: L-18` in code):
  - Odd-cent half inclusion: `taxableCents` truncates the half cent (1001 -> 500). Not a decision; tests do not assert it.
  - CSV money format: integer cents. Totals line puts net in `gain`, denied in `superficialLossDenied`, and gains,
    losses and taxable in the notes cell.
  - `DEEMED_GAIN_ROC` lines: excluded (no row), because `SlReplay` does not carry them; owner to decide.
  - Inclusion rate fixed at 1/2 for every year.
  - Per-row rounding: rows rounded on their own, totals sum the rounded rows; no on-screen rounding note yet.
  - SL display: one note per sale naming SL-PARTIAL, the sale entry id and the substituted buy entry ids.
- Ran: `./gradlew --init-script ../mirror.init.gradle.kts build --offline` BUILD SUCCESSFUL;
  `cd ui && npm ci && npm run typecheck && npm run build` clean. Specs: L18YourYearSpec 20/20, L18UiGuardSpec 5/5
  (0 skipped, 0 failures).
