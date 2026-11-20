# L-11 Book value versus true ACB view with reasons

**Status:** QE passed (round 2; backend only, live data source pending), awaiting owner approval  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** 10 · **Branch:** `L-11-book-value-vs-acb-view` · **Round:** 0 / 3

## Story

As a self-directed investor, I want a page that shows my broker's book value next to my ACB for each holding, with a
plain-language reason for every difference that links to the ledger entries behind it, so that I can see why the
numbers differ and check them myself.

## Why it matters

Broker book value is not guaranteed to equal ACB, and investors are told to track ACB themselves. See
[`docs/problem/evidence.md`](../problem/evidence.md): E-21 and E-22 (book value is not a tax figure; Wealthsimple
says book cost "is never guaranteed to be accurate", second-hand), and the causes E-23 (pooling), E-24 (transfers;
do not say "reset to market value"), E-25 and E-32 (return of capital), E-28 (superficial loss), E-29 (FX). All
from vendor sources. That investors want a reason per difference is not sourced (A-11; E-31 is vendor advice).

## Notes for QE and Dev

- **No tax expected values.** The UI only displays values the API returns. UI tests use a **stubbed API response**
  (below); the numbers in it are display fixtures, not tax results. API-level tests in AC6 check that reasons add up,
  not what the ACB should be.
- Depends on L-06, L-07 and L-08 for the reasons and on a book-value source (owner decision below).
- **Interface proposal (Dev may refine):** `GET /v1/taxpayers/{taxpayerId}/holdings` returns
  ```json
  {"taxpayerId": "TP-0001", "currency": "CAD", "holdings": [
    {"securityId": "SYN-AAA", "shares": "60", "bookValueMinor": 180750, "acbMinor": 179550,
     "differenceMinor": -1200,
     "reasons": [{"code": "RETURN_OF_CAPITAL", "amountMinor": -1200,
                  "text": "Return of capital of $12.00 on 2025-06-30 lowers ACB; the broker did not lower book value.",
                  "entryIds": ["e-0042"]}]},
    {"securityId": "SYN-BBB", "shares": "50", "bookValueMinor": 225495, "acbMinor": 225495,
     "differenceMinor": 0, "reasons": []}]}
  ```
  Reason codes (proposed): `RETURN_OF_CAPITAL`, `SUPERFICIAL_LOSS_ADDED`, `SUPERFICIAL_LOSS_DENIED_REGISTERED`,
  `FX_RATE`, `POOLED_ACROSS_ACCOUNTS`, `COMMISSION`.
- `GET /v1/entries/{entryId}` returns the entry with its lines (from L-03).
- Amounts are reported after the single reporting-boundary rounding rule (ADR 0002); the UI does no arithmetic other
  than formatting.

## Acceptance criteria

1. **AC1 Each holding shows book value, ACB and the difference.**
   - Input: the stubbed response above; open the page for `TP-0001`.
   - Expected: a row for `SYN-AAA` showing shares `60`, book value `$1,807.50`, ACB `$1,795.50`, difference `−$12.00`;
     a row for `SYN-BBB` showing `$2,254.95`, `$2,254.95` and difference `$0.00`.
2. **AC2 Every non-zero difference shows its reasons.**
   - Input: as AC1.
   - Expected: the `SYN-AAA` row shows the `RETURN_OF_CAPITAL` reason's text and amount `−$12.00`; the `SYN-BBB` row
     shows "No difference" and no reasons.
3. **AC3 Reasons link to ledger entries.**
   - Input: click entry `e-0042` on the `SYN-AAA` reason, with `GET /v1/entries/e-0042` stubbed to return an entry with
     two lines.
   - Expected: the entry's effective date, activity type, idempotency key and both lines are shown.
4. **AC4 An unexplained difference is shown as an error, not hidden.**
   - Input: stub with `differenceMinor: -1500` and one reason of `amountMinor: -1200`.
   - Expected: the row shows a visible "Unexplained difference: −$3.00" warning.
5. **AC5 Empty and error states.**
   - Input A: stub returns `"holdings": []`. Expected: "No holdings for TP-0001".
   - Input B: stub returns HTTP `500`. Expected: an error message; no stale numbers are shown.
6. **AC6 The API's reasons always add up (API test, real stack).**
   - Input: the seed `42` year loaded; call `GET /v1/taxpayers/{id}/holdings` for every generated taxpayer.
   - Expected: for every holding, `differenceMinor = acbMinor − bookValueMinor` and the sum of `reasons[].amountMinor`
     equals `differenceMinor` exactly; every reason has non-empty `text` and at least one `entryId` that resolves via
     `GET /v1/entries/{entryId}`.
7. **AC7 Type check and build are clean.**
   - Input: `cd ui && npm ci && npm run typecheck && npm run build`.
   - Expected: exit code `0`, no TypeScript errors under strict mode.

## Out of scope

- Computing ACB, book value or reasons in the UI.
- Editing or correcting activities from the UI.
- Charts, mobile layout, accessibility audit beyond semantic HTML, authentication.
- Grafana dashboard (cut-list item 1; separate from this page).

## Needs owner decision

- **Where book value comes from.** The canonical CSV (L-01) has no book-value column. Options: (a) a synthetic
  "broker method" computed by Ledgerline (for example per-account average cost without ROC or superficial-loss
  adjustments), clearly labelled as a model; (b) a book-value column in the owner's real export, if it has one;
  (c) a separate holdings snapshot file. This is a narrative decision: whatever is chosen must be described honestly
  on the page.
- **Reason taxonomy and wording.** The codes above are a proposal. Each reason text touches tax explanation; the
  owner should approve the wording, and L-06/L-07/L-08 sources should back it.
- **Where UI tests live.** `CLAUDE.md` puts all tests in `:tests`; UI component tests (for example Vitest) normally sit
  in `ui/`. Owner to decide whether QE owns a `ui/` test folder or drives the UI from `tests/` (for example Playwright).
- **Display currency**: CAD only (proposed), or also show USD amounts for USD holdings.

## Test map (QE fills)

Provisional: owner input B2 (real book value export) is pending and the story is still Draft. Backend contract is in
the KDoc of `L11BookValueSpec`; it uses a simple CSV `security_id,book_value` and `GET /v1/holdings/{taxpayer}/acb-vs-book`,
not the story's proposed `/v1/taxpayers/{id}/holdings` (deliberate difference; Dev keeps the QE path unless the owner decides otherwise).
Reasons carry `amountMinor` and the AC6 sum check is covered. A book value with no pool is a `NO-LEDGER-HISTORY` row (provisional C29).

| AC | Test |
| --- | --- |
| AC1 | UI follow-up (stubbed API; no JS runner yet, owner to decide where UI tests live). Backend rows: `L11BookValueSpec` "L-11 AC6 backend: book value equal to ACB ..." |
| AC2 | UI follow-up. Backend: `L11BookValueSpec` "L-11 AC2/AC6 backend: ... ROC-REDUCES-ACB" |
| AC3 | UI follow-up (needs `GET /v1/entries/{id}` stub) |
| AC4 | UI follow-up. Backend sums are always exact (property test), so the warning is a UI-only stub case |
| AC5 | UI follow-up; backend empty case in "L-11 API: ..." (`holdings: []`) |
| AC6 | `L11BookValueSpec` "L-11 AC6 backend: ..." and "L-11 API: ..."; `L11BookValuePropertyTest` (2 tests). Orphan book value: "L-11 AC6 backend: a book value with no pool ...". Real-stack seed-42 run not written |
| AC7 | CI step `npm run typecheck && npm run build`, no Kotlin test |

## Change notes (Dev fills)

- `calc/BookValueComparison.kt`: pure `BookValueComparison`, `BookValueCsv`, `HoldingDiff`, `Reason`. Reasons: return of capital (rule `ROC-REDUCES-ACB`, entry IDs from the pool history), then the remainder under `POOL-IDENTICAL` (pooled entries); a book value with no pool gets `NO-LEDGER-HISTORY`. Amounts always sum to the difference. Provisional choices marked `OWNER-INPUT` (B2, C29 in `docs/owner-inputs.md`).
- `app/AcbVsBookRoutes.kt`: `acbVsBookModule` and `GET /v1/holdings/{taxpayer}/acb-vs-book`; a taxpayer with no pools gets `holdings: []`. Not wired into `Main` (needs a pool source and a book value file; follow-up).
- Not done: UI table (`npm run typecheck` fails here with missing modules because `node_modules` is not installed); superficial loss, FX and commission reasons.

### Round 2 (B-021): reasons from pool steps

- `PoolChange` has an additive `ruleAmounts` map (internal units a rule contributed). The superficial-loss add-back records `SL-ADDBACK-TARGET` with its amount, under the sale's entry ID.
- `BookValueComparison` builds reasons from those amounts (and `ROC-REDUCES-ACB`), keeping only parts that point the same way as the difference and fit inside it. Any remainder is `UNEXPLAINED` with its amount; it is never given to `POOL-IDENTICAL`.
- `UNEXPLAINED` is added to `CaIta2026v1.ruleIds` (marked OWNER-INPUT C29) only because the provisional L-11 tests require every reason ID to be in that set. It is not a tax rule; the owner may prefer to change the test instead.

### Round 2 (B-022): buy commission reason

- A buy records `COST-BUY-COMMISSION` with its commission (internal units) in `PoolChange.ruleAmounts`; the comparison reports it under that rule ID with the buy entry. FX has no reason: the pool takes CAD amounts, so there is no FX pool step yet.

### Round 2 (B-023): route wired

- `Main.kt` now calls `acbVsBookModule`. Not run against the live stack (needs Postgres and Kafka). There is still no ledger-to-pool source, so the live route is given an empty pool map and the book value file named by `BOOK_VALUE_CSV` (default: header only); it answers 200 with no holdings until a pool source exists. Owner to decide that source (Open question, C29). `acbVsBookModule` installs `ContentNegotiation` only if absent, since `module()` installs it as well.
