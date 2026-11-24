# L-23 Dashboard: four tabs and a summary bar

**Status:** Draft  <!-- Draft → Approved → Tests written → In dev → In QE → Done. Only the owner sets Approved. -->
**Day:** N · **Branch:** `l-23` · **Round:** 0 / 3

## Story

As a _self-directed investor_, I want one dashboard with a summary bar and four tabs (Upload, Your year, Holdings, Data
checks) driven by one taxpayer, so that I can see in one glance whether my numbers are trustworthy and go straight to
the trades behind any warning. As a _reviewing engineer_, I want a demo link that opens the right tab, so that I can
land on the flagship Holdings view without clicking through.

## Why it matters

Today `ui/src/App.tsx` stacks holdings, the tax year and the upload on one page, each with its own taxpayer box. The
need to be told when an ACB is a guess is in [`docs/problem/evidence.md`](../problem/evidence.md) (E-36, E-37, the same
entries L-16 cites). Whether a tabbed layout helps real users is a hypothesis; no user evidence exists for it. This
story is UI composition only: it has no tax values and needs no tax source. Extra story beyond the 14-story cap; owner
approval needed.

## Acceptance criteria

Honest note on testing: `ui/` has no browser or component test tool. Every AC below is checked by (a) QE source-text
guards over `ui/src/*.tsx` (the `SourceText.read` pattern L-16 and L-17 already use) and (b) `npm run typecheck` and
`npm run build`. Rendered behaviour (what a person sees) is NOT proven by these; the owner checks it by eye at
sign-off. If the owner wants rendered proof, that needs a new test tool (see decisions).

No new backend endpoint is needed; the summary bar is built from `GET /api/health`,
`GET /api/v1/tax-year/{taxpayer}` and `/{year}`, `GET /api/v1/holdings/{taxpayer}/acb-vs-book`, and
`GET /api/v1/holdings/{taxpayer}/{security}/explain`.

1. **AC1** Four tabs, fixed order, accessible
   - Input: `ui/src/App.tsx`.
   - Expected: a `role="tablist"` container holds four `<button role="tab">` elements, labelled in this order:
     "Upload", "Your year", "Holdings", "Data checks"; each has `aria-selected` true only for the active tab; the
     panel for each is a `role="tabpanel"`. `App.tsx` no longer renders `AcbVsBook`, `YourYear` and `Upload` all at
     once. Guard: label order by index in source; `aria-selected` and `role="tab"` present; `npm run build` passes.
2. **AC2** Tab in the URL hash
   - Input: hashes `#upload`, `#your-year`, `#holdings`, `#data-checks`, an unknown `#bogus`, and no hash.
   - Expected: each known hash opens its tab on page load; clicking a tab writes its hash; a `hashchange` listener
     keeps back/forward working. Unknown or empty hash opens the default: "Your year" once the taxpayer has at least
     one tax year (per `GET /v1/tax-year/{taxpayer}`), otherwise "Upload". An explicit known hash always wins over the
     default. Guard: the four hash strings, `location.hash` and `hashchange` appear in `App.tsx`.
3. **AC3** One taxpayer input, shared
   - Input: `App.tsx`, `AcbVsBook.tsx`, `YourYear.tsx`.
   - Expected: exactly one taxpayer `<input>` exists (in the summary bar area), default "TP-0001". `AcbVsBook` and
     `YourYear` take the taxpayer as a prop and contain no `useState("TP-0001")` and no taxpayer `<input>`. Changing
     the taxpayer reloads the summary bar and the active tab's data. Guard: count of taxpayer inputs and absence of
     the default in the two components.
4. **AC4** Summary bar fields, from existing endpoints, integer money only
   - Input (synthetic): taxpayer TP-0001, latest tax year 2025 with `totals.netCents = 244000`, 3 holdings, 2 items on
     the Data checks list, `/health` status "ok".
   - Expected: the bar shows taxpayer "TP-0001", tax year 2025 (a select of the years the route lists), net gain or
     loss "2,440.00" (negative shown "-1,234.05"), holdings 3, warnings 2, service health "ok" (or "unreachable"
     with the error text). Money goes through `formatMoney` only; no `toFixed`, `parseFloat`, `Number(` on cents,
     `Intl.NumberFormat`, `/ 100`. The warnings count is the length of the same list the Data checks tab shows (one
     source, so they cannot disagree). Net uses `totals.netCents`, which excludes non-CAD rows (B-036); the bar says
     "CAD only" next to it when any `FX-NOT-CONVERTED` warning exists. Guard: field labels, `formatMoney` use,
     forbidden float calls absent.
5. **AC5** Holdings tab (flagship, owner mockup)
   - Input: the acb-vs-book rows plus, per holding, the explain response (`status`, `fullyExplained`,
     `unexplainedCents`, `adjustmentCount`).
   - Expected: per holding a row with book value, ACB, difference (a dash when no book value, as today), a status
     badge from explain `status` (labels as in `ExplainPanel.tsx`: Matches, Explained, Discrepancy, Data gap, No book
     value), and the reasons, each reason's entry ids as buttons that open the L-17 trade timeline for that holding
     with that entry highlighted (`ExplainPanel` is reused, not copied). A footer states "All N holdings fully
     explained" ONLY when every holding with a book value has `fullyExplained === true` (strict: one `false`, or one
     holding that failed to load, removes the sentence and the footer names the holdings not explained). Guard:
     `fullyExplained` read from the explain payload, no client re-computation of reason sums, no `filter(` in
     `AcbVsBook.tsx` (existing L-16/L-17 guards stay green).
6. **AC6** Data checks tab: one list, every source, each with trades and a plain action
   - Input: a taxpayer whose data triggers, respectively, `DATA-GAP-TRANSFER-NO-COST` and `DATA-GAP-OVERSELL`
     (L-16, from acb-vs-book `reasons`), `FX-NOT-CONVERTED` (L-18, from the tax-year `warnings`),
     `AMOUNT_OUT_OF_RANGE` (L-18 as the 422 body of the tax-year route; L-20 as a preview warning), and a rejected
     upload row (L-20 `rows` with `status` REJECTED, from the last preview in this page session).
   - Expected: every item is listed (none dropped, none merged away) with rule id, text, the trades involved (ledger
     entry ids for ledger warnings, file line numbers for rejected rows, each shown as a button that opens the
     Holdings timeline for that security where an entry id exists) and a one-line "What to do". Wording is a static
     map keyed by rule id, with a generic fallback "Review the trades listed and correct or add the missing activity"
     for an unknown id. Proposed wording (owner to confirm): `DATA-GAP-TRANSFER-NO-COST` "Add the cost of the
     transferred shares, or treat the ACB as unknown"; `DATA-GAP-OVERSELL` "Add the missing purchase or remove the
     sale"; `FX-NOT-CONVERTED` "Foreign-currency rows are excluded from totals; convert them yourself for now";
     `AMOUNT_OUT_OF_RANGE` "Split or correct the very large amount in the listed rows"; REJECTED row "Fix the row
     named and upload again". Empty state: "No data checks to show" (not a blank panel). The last preview's report
     is held in `App` state (lifted from `Upload`), so switching tabs does not lose it. Guard: all rule ids and the
     fallback present; `Upload` receives the report setter as a prop.
7. **AC7** Build and strict typing stay clean
   - Input: `cd ui && npm ci && npm run typecheck && npm run build`.
   - Expected: both exit 0 with `tsc` strict, no `any`, no `@ts-ignore` in the changed files; no new runtime
     dependency in `ui/package.json` (hash handling uses `window.location`, no router library).

## Out of scope

- Auth, login, multi-user, taxpayer lists or switching beyond one text input.
- Charts or graphs of any kind.
- L-21 (loss carry-forward) and L-22 (what-if); reserved numbers, not this story.
- Any new backend endpoint, or changing JSON of existing routes.
- Persisting the last preview across a page reload (it lives in page memory only).
- Mobile layout polish and visual design beyond the owner mockup for Holdings.
- A browser test tool (see decisions).

## Needs owner decision

- **Holdings badge source.** The list endpoint has no `status` or `fullyExplained`; only `/explain` has them. Proposed:
  call `/explain` once per holding (N small requests, fine for synthetic data) rather than add a list field or
  re-derive status in the UI (duplicated rule logic). Alternative: Dev adds `status` and `fullyExplained` to each
  acb-vs-book holding (backend change, then QE must re-check L-11/L-17 tests). Owner picks.
- **Meaning of "fully explained" for the footer.** Proposed strict reading in AC5 (every holding with a book value).
  Holdings with no book value: counted as "not compared", not as explained. Owner confirms; this is a trust claim
  about tax figures, not a UI detail.
- **Warnings count scope.** AC4 counts L-16 reasons, tax-year warnings and last-preview rejected rows/warnings, for the
  selected tax year only. Should L-16 ledger warnings (not tied to a year) always count, and should tax-year warnings
  of other years be shown? Owner decides.
- **Default tab rule.** "Your year once data exists" is read as: the taxpayer has at least one tax year with rows. A
  taxpayer with holdings but no sales would land on Upload. Owner confirms or sets "Holdings" for that case.
- **"What to do" wording** in AC6 is BA-proposed plain-language text. It gives no tax advice, but the owner should
  confirm it does not read as a tax instruction (for example the transfer-cost line; cost of transfers is the open
  question C33).
- **Rendered-behaviour proof.** Source-text guards cannot show that a tab actually switches. Owner decides whether to
  accept manual sign-off or approve adding a test tool (an ADR, since it changes the stack).
- **Story cap.** This is beyond the 14-story maximum; owner approval needed to proceed.

## Test map (QE fills)

| AC | Test |
| --- | --- |
| AC1 | `tests/spec/.../L23Spec.kt` › "L-23 AC1: …" |

## Change notes (Dev fills)

- BA code facts for Dev: `App.tsx` fetches `/api/health` once and renders `AcbVsBook`, `YourYear`, `Upload` in a
  stack; each of the first two owns its own `useState("TP-0001")`. `Upload.tsx` keeps `report` in local state, so
  an unmounted tab loses it: lift it. Tax-year route returns 422 `{error, message}` for `AMOUNT_OUT_OF_RANGE`
  (B-037), so the UI must read that body, not just throw on `!r.ok`. `ExplainPanel` renders `fullyExplained` text
  with `$${formatMoney(...)}` (a stray `$` before the template) in the unexplained case; not changed by this story.
