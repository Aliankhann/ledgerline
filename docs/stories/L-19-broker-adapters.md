# L-19 Import from more than one brokerage format

**Status:** Draft (owner to approve) · **Round:** 0

## Story
As an investor with accounts at more than one brokerage, I want to upload each broker's own export and have Ledgerline
normalize them into the same activities, so that one ledger, one ACB pool per security and one tax year cover all of
them, whatever format they came from.

## Why it matters
Owner's target list item 1, "multi-broker normalization: … demonstrates data normalization and cross-system
consistency". `docs/problem/evidence.md`: transferred positions and multiple accounts are a main source of wrong book
values. The Wealthsimple adapter waits for the owner's export header (B1/B2; header only, no rows).

## Formats
- **Canonical** (L-01): unchanged.
- **Broker B (synthetic, clearly labelled):** header
  `Trade Date,Settle Date,Account,Action,Symbol,Quantity,Price,Gross Amount,Fees,Currency,Reference,Memo`;
  dates `MM/DD/YYYY`; `Action` in {Buy, Sell, Deposit, Withdrawal, Transfer In, Dividend}; sells have negative
  `Quantity`; amounts may carry a `$` and thousands separators; `Fees` is the commission; `Reference` is the broker's
  unique id; no taxpayer column (the upload names the taxpayer and account type).

## Acceptance criteria
1. **AC1 Detection.** The upload preview (L-20) detects the format from the header row and reports it
   (`format: "CANONICAL" | "BROKER_B"`); an unknown header is a file error naming the expected headers of each format.
2. **AC2 Mapping.** Each Broker B row maps to exactly one canonical activity: dates to ISO, `Action` to activity type,
   `abs(Quantity)`, `Gross Amount` to amount (no `$` or commas), `Fees` to commission, `Currency` kept, idempotency key
   `BROKERB:<Account>:<Reference>`.
3. **AC3 Row errors** keep the line number and name the Broker B column (bad date, unknown action, positive quantity on a
   sell, missing reference) and never stop the rest of the file.
4. **AC4 Cross-format consistency (property).** For generated trade sets, the same trades written as canonical CSV and
   as Broker B CSV produce identical activities after normalization (except the idempotency key), identical ACB pools
   and identical tax-year totals. Uploading both formats of the same trades for different accounts yields two accounts
   in one pool.
5. **AC5 Idempotency across formats.** Re-uploading a Broker B file is all DUPLICATE; changing one row's amount is a
   CONFLICT on that row only.
6. **AC6 UI.** The Upload tab shows the detected format and, for Broker B, asks for taxpayer and account type before
   preview.

## Out of scope
Wealthsimple (needs header), PDF statements, options, corporate actions.

## Owner decisions
- Broker B is synthetic and labelled so; no real broker's name or layout is copied.
- Key scheme for Broker B rows.
- Approval beyond the 14-story cap.

## Change notes
