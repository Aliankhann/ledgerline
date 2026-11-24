# L-17 Explain a holding (explainable reconciliation)

**Status:** In QE (provisional, owner to approve; OWNER-INPUT)
**Day:** N · **Branch:** `l-17` · **Round:** 0 / 3

## Story

As a _self-directed investor_, I want to open one holding and see, step by step, how its ACB was built and which
trades explain each part of the gap to my broker's book value, so that I can check the number myself or hand the
timeline to an accountant. As a _reviewing engineer_, I want every cent of the difference traced to ledger entries.

## Why it matters

Broker figures may not be ACB and the taxpayer must adjust them (E-36); a broker says transferred-in assets may
carry a wrong ACB (E-37). See [`docs/problem/evidence.md`](../problem/evidence.md). E-36 and E-37 are CRA/Wealthsimple
quotes as reported by the owner's LLM, not re-fetched; they show the problem, not demand for this feature (thin).

## Code facts this story reuses (no new tax logic)

- Pool history: `PoolChange` (activityId, entryId, taxDate, sharesDelta, acbDelta in internal units, ruleIds,
  ruleAmounts) `calc/AcbPool.kt:13-22`; `AcbPool.history` `AcbPool.kt:25`; appended by `AcbEngine.applied`
  `calc/AcbEngine.kt:133-147`.
- Superficial-loss add-back step: `replaySuperficialLosses` `calc/SuperficialReplay.kt:57-71`. It is recorded under the
  SALE's `activityId`/`entryId` with the TARGET's tax date, 0 shares, rule `SL-ADDBACK-TARGET`. Denied amount and trigger
  buys are in `SaleResult`/`SlTrigger` (`calc/Superficial.kt:10-13`), reached via `SlReplay.sales`
  (`SuperficialReplay.kt:49,74`).
- Reasons: `Reason`, `HoldingRow` `calc/BookValueComparison.kt:4-22`; reason building `:105-144`; `UNEXPLAINED` `:30,:132-141`;
  `NO_LEDGER_HISTORY` `:109-117`; `DATA_GAP` `:57-65`; `NO_BROKER_VALUE` `:82`. Reason ids producible today: `ROC-REDUCES-ACB`,
  `COST-BUY-COMMISSION`, `SL-ADDBACK-TARGET`, plus the pseudo ids above and L-16 warnings.
- Rule ids come from `CaIta2026v1.ruleIds` `calc/TaxRuleSet.kt:50-78`; cents via `toReportedCents` `TaxRuleSet.kt:84`.
- Idempotency key lives on the activity (`core/Activity.kt:23`), reachable from `PostedActivity` (`AcbPool.kt:7`); a
  `PoolChange` has none, so the endpoint joins by `activityId`.
- Existing route and live pool source: `app/AcbVsBookRoutes.kt:35-48` (`acbVsBookFromActivities`, superficial replay + L-16
  warnings), route `:64`. UI: `ui/src/AcbVsBook.tsx` (`formatMoney` `:14-21`, reason list `:75-82`).

## Acceptance criteria

Tax numbers come only from existing fixtures in `tests/fixtures/cra/`; this story adds none.

1. **AC1 Timeline reproduces a CRA fixture.**
   - Input: fixture `cra-xyz-gain.properties` loaded as activities (SYN-CRA-XYZ), then
     `GET /v1/holdings/{taxpayerId}/SYN-CRA-XYZ/explain`.
   - Expected: 2 steps in order. t1 BUY 2024-01-15: shares delta 400000000 units, cost delta 400000 cents, running
     shares 400000000, running ACB 400000. t2 SELL 2025-03-13: running shares 0, running ACB 0. Each step has entryId,
     idempotency key, tax date, type, rule ids (non-empty, all in `CaIta2026v1.ruleIds`).
   - Source: fixture `t1.expected.acb.cents=400000`, `t2.expected.acb.cents=0` (CRA "XYZ" worked example, URL in file).
   - Cost delta of a step = rounded running ACB minus the previous rounded running ACB, so deltas always sum to the
     final ACB (rounding only at the reporting boundary).
2. **AC2 Sum check.** Given a holding with a book value, when explained, then the `amountMinor` of its reasons sum to
   `differenceCents`, and every reason with `amountMinor != 0` lists at least one entry id that is a step in the timeline.
   Reasons with no entry ids by design (`NO_LEDGER_HISTORY`, `DATA_GAP`) are exempt and say so.
   - Input: a holding built from `cra-xyz-gain` plus buy commission, book value 1 cent below ACB.
   - Expected: reasons sum to the difference; `COST-BUY-COMMISSION` (or `UNEXPLAINED`) lists the BUY entry id.
3. **AC3 Superficial-loss step.** Given fixture `cra-ti-2004-0073011e5-partial-sl.properties` (S=50, P=20, B=20),
   then the timeline has a step with rule `SL-ADDBACK-TARGET` showing the sale entry (t3) and the target buy entry
   (from the sale's `SlTrigger`s), the denied loss and the add-back, and `denied x 50 = loss x 20` holds.
   - Source: the fixture's `expected.*.units` ratio 20/50 only. The fixture's dollar amounts are labelled test
     assumptions; this AC asserts the ratio and the entry ids, never a dollar amount.
4. **AC4 Unknown and warning-only holdings.** Unknown taxpayer or security: HTTP 404, body names the key. A holding
   with only L-16 warnings (`DATA-GAP-TRANSFER-NO-COST` or `DATA-GAP-OVERSELL`, no pool): HTTP 200, empty `steps`,
   the warnings listed with their entry ids.
5. **AC5 Determinism.** The same activities replayed, or supplied in shuffled arrival order, give byte-identical
   JSON (invariants 5 and 6). Steps are ordered by tax date, then sequence.
6. **AC6 UI.** In `AcbVsBook.tsx`, clicking a holding row opens a panel with the timeline; each reason chip lists
   its entry ids and selecting one highlights that step. All money uses `formatMoney` (integer math, no floats).
   Verified with `npm run typecheck && npm run build` plus a component test; QE decides the test tool.
7. **AC7 Nothing hidden.** Every `UNEXPLAINED` amount is returned and shown, never dropped or folded into another
   reason. For the seed-42 synthetic year, each holding either has no `UNEXPLAINED` reason or shows it. (Whether seed 42
   yields zero `UNEXPLAINED` is unknown; the BA has not run it. Do not promise it.)
8. **AC8 Honest status and footer.** The footer says "Difference fully explained by N adjustments" only when the
   cent-carrying reasons sum exactly to the difference and no `UNEXPLAINED` reason is present. Otherwise it shows
   "$X unexplained" prominently. A fully explained holding is never labelled "Unreconciled".
   - Input: difference 100 cents, reasons 60 + 40 -> "Explained", N = 2. Difference 100, reasons 60 + `UNEXPLAINED` 40
     -> "Discrepancy", "$0.40 unexplained".

## UI target (owner mockup; illustrative, its figures are not fixtures)

- One card per holding: "Broker book value", "Calculated ACB", "Difference" (signed), and a badge: "Matches"
  (difference 0), "Explained" (reasons sum to the difference, no `UNEXPLAINED`), "Discrepancy" (`UNEXPLAINED` != 0),
  "Data gap" (L-16 warnings or `DATA_GAP`).
- "Explanation of difference": one line per reason with a plain-English label (map rule id to label: `SL-ADDBACK-TARGET` ->
  "Superficial-loss add-back"; `COST-BUY-COMMISSION` -> "Acquisition commission included in ACB"; `ROC-REDUCES-ACB` ->
  "Return of capital reduced ACB"), the trade date (from its timeline steps; `Reason` carries no date), and the signed
  amount. Each line expands to its trades.
- Footer per AC8.

## Out of scope

- New tax rules or changes to calculation; editing data from the UI.
- A reason for FX at the Bank of Canada rate. `calc/fx/FxRateCache.kt` exists, but `BookValueComparison` produces no FX
  reason today (`ruleAmounts` only has `COST-BUY-COMMISSION` and `SL-ADDBACK-TARGET`). Showing one would be invented.
- A portfolio-total card (open question below).

## Needs owner decision

- **Tax judgment:** none new. The story only displays existing rules; their open decisions (C29 `UNEXPLAINED`, C31, C6/C7
  rounding) still apply.
- Portfolio total card: the engine has no cross-holding total. A per-taxpayer sum is arithmetic only, but the
  ACB-vs-book list covers only holdings with a book value or warning. Include a sum, or drop the card?
- Add-back step carries the sale's entry id and the target's date; tying it to the target buy needs `SaleResult`
  triggers (AC3). Dev may instead add a target id to `PoolChange`. Owner to choose.
- Labels and ids for the status badges and plain-English reason names are proposals.
- Route param naming (`{taxpayerId}` here, `{taxpayer}` on the existing route) and the live pool source (C29,
  `Main.kt` still wired with an empty pool map) must be settled first.
- Beyond the 14-story cap: needs explicit owner approval.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L17Spec.kt` › "L-17 AC1: …" |

## Change notes (Dev fills)

- calc: new `HoldingExplanation.kt` (`ExplainStatus`, `SuperficialLossStep`, `ExplainStep`, `Explanation`,
  `ExplanationFooter`, `HoldingExplanation.explain`). It reuses `replaySuperficial`, `BookValueComparison.compareAll` and
  `DataGapWarnings`; no new tax logic, integer money only. Steps are the pool history in replay order; running shares/ACB
  are cumulative sums and `costDeltaCents` is the difference of rounded running ACBs. One add-back step per denied sale,
  with target entry ids from the sale's `SlTrigger`s.
- calc: the old `dev.ledgerline.calc.Explanation` (rule-set note on `PoolStep.Applied`) is renamed `StepExplanation`
  because the L-17 contract takes the name `Explanation`. Pure rename.
- Status: `DATA_GAP` when the holding has L-16 warnings and no pool steps (a transfer-only or oversell-only holding
  still has an empty pool object, so "no pool" is read as "no steps").
- app: `ExplainRoutes.kt` (`explainFromActivities`, `GET /v1/holdings/{taxpayer}/{security}/explain`, 404 names both),
  wired in `Main.kt`.
- ui: `ExplainPanel.tsx` (card, reasons with entry-id chips, footer per AC8, timeline); `AcbVsBook.tsx` rows are
  clickable; `formatMoney` moved to `money.ts` (re-exported). Footer shows "$X unexplained" with `formatMoney`, so a
  negative amount reads "$-0.40".
- Blocked: `L17ExplainSpec.kt` does not compile (missing `jsonPrimitive` import, `Int` vs `Long` `shouldBeGreaterThan`);
  see `docs/status.md` Open questions. On a scratch copy with those two imports added, L17+L11+L16 spec tests: 86 run, 0
  failed; `./gradlew build`: successful, 574 tests, 0 failures. UI `npm run typecheck` and `npm run build` pass.
