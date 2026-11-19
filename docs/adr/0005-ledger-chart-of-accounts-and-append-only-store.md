# 0005 Ledger line layout, chart of accounts and append-only store

**Status:** Draft · **Date:** 2026-11-19 · **Drafted by:** agent (dev, story L-03)

## Context

L-03 needs every activity to post as one balanced entry (invariant 1) that is never updated or deleted (invariant 3).
The story leaves the chart of accounts and line layout to the Dev, and lists the immutability mechanism and the meaning
of "sequence" as owner decisions. The code follows the placeholders in `docs/owner-inputs.md` (C3 share scale 10^-6,
C4 order `(effective_date, source_seq, idempotency_key)`) and the L-03 spec KDoc; this ADR records the rest.

## Options considered

1. **Lines per unit, investor account plus a few global system accounts** (built): units are `CAD`, `USD` and
   `SEC:<securityId>`; the investor side uses `activity.accountId`; the other side is one of `SYS:EXTERNAL`
   (deposits, withdrawals, transfers in), `SYS:MARKET` (buy/sell counterparty), `SYS:EXPENSE:COMMISSION`,
   `SYS:EXPENSE:FEE`, `SYS:INCOME:DIVIDEND`, `SYS:ISSUER:RETURN_OF_CAPITAL`, `SYS:FX:CLEARING` (each FX leg).
   Movements net per (account, unit), so a buy posts one investor cash credit of amount + commission; zero movements
   post no line.
2. **Per-investor system accounts** (for example `TP-0001-NR1:EXPENSE:COMMISSION`): easier per-account reporting, more
   accounts.
3. **Cash-only ledger**, positions left to calculation.

## Decision

_Owner, in your own words._

## Rejected alternatives and why

- Option 3 was not built because the placeholder says share holdings are ledger lines, and lines let reconciliation
  check holdings against calculation.
- Option 2 was not built because nothing yet reads system-account balances; it can be added without changing the
  investor-side lines.

## Consequences

- **Sequence.** `ledger.entry.entry_id` (identity) is the ledger sequence. Appends take
  `pg_advisory_xact_lock` before inserting, so ids increase in commit order, not just allocation order. Gaps are
  possible. Cost: appends are serialised across the whole ledger; revisit (for example a per-taxpayer lock) if L-13
  shows it limits throughput.
- **Immutability.** Both mechanisms: role `ledgerline_app` (NOLOGIN, created idempotently) has only USAGE on the schema,
  SELECT and INSERT on the tables and USAGE on sequences; a statement-level trigger raises "append-only" on UPDATE,
  DELETE **and TRUNCATE** for every role, including the owner. TRUNCATE in the trigger goes beyond the placeholder
  (which says UPDATE/DELETE); drop it if the owner disagrees. A superuser can still disable triggers, so this guards
  against mistakes and the app role, not a hostile DBA.
- **App role at runtime.** The app still connects as the compose superuser `ledgerline`; making the service run as
  `ledgerline_app` (login, password, `SET ROLE`) is not wired in L-03.
- **`idempotency_key` is UNIQUE** in `ledger.entry`, so a duplicate key fails at the database. L-02 adds the
  key-plus-payload-hash behaviour on top (ADR 0001).
- **Lines lose some activity detail.** A transfer-in's book value (`amountMinor`) is not a cash movement and posts no
  line; dividend and return-of-capital lines do not name the security. Calculation must read these from the activity
  (L-05 outbox payload), not from the lines.
- Verified by `L03LedgerSpec`, `BalanceInvariantPropertyTest` and `ImmutabilityInvariantPropertyTest`.
