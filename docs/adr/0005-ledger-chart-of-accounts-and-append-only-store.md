# 0005 Ledger line layout, chart of accounts and append-only store

**Status:** Draft · **Date:** 2026-11-19 (L-05 section 2026-11-20, round 2) · **Drafted by:** agent (dev, stories L-03, L-04, L-05)

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
- **Two database logins (B-003, round 2).** Migrations and the service use different logins. At startup the app opens
  a short-lived pool as the migration login (`DATABASE_MIGRATION_USER`, the compose Postgres admin `ledgerline`), runs
  Flyway, runs `ALTER ROLE ledgerline_app LOGIN PASSWORD <DATABASE_PASSWORD>` (quoted server-side with `format('%L')`),
  and closes that pool. It then serves through a pool that logs in as `DATABASE_USER` = `ledgerline_app`, which does
  not own the tables and holds only SELECT and INSERT, so it cannot disable, drop or bypass the triggers (42501).
  `AppConfig` refuses to start if both users are the same. Cost: the app process still receives the admin password in
  its environment; a separate one-shot migration container would remove that and can replace this later. The
  migration login is the superuser rather than a dedicated non-superuser owner, because `ALTER ROLE ... PASSWORD`
  needs CREATEROLE and a fresh volume needs no init script this way.
- **Entry sealing (B-004, migration V3).** Database-side, not only in Kotlin: a line may only be inserted for an entry
  created by the same (sub)transaction (the line's `xmin` must equal the entry's `xmin`); lines of an entry are
  appended in `line_id` order; and deferred constraint triggers check at commit that every entry has at least one line
  and balances per unit. Only the entry's newest line runs the full balance query, so cost stays linear in lines.
  Limits: lines written in a different savepoint from their entry are refused; `xmin` is 32-bit, so in theory an entry
  from about 4 billion transactions earlier could share an `xmin` (Postgres freezes such rows long before).
- **Balance arithmetic (B-005).** `Posting.requireBalanced` sums debits and credits separately per unit with
  `Math.addExact`. Amounts are positive, so the outcome depends only on the set of lines; an entry whose total debits
  or credits in a unit exceed `Long.MAX_VALUE` is refused whole with an `ArithmeticException` naming the unit.
- **Zero-amount cash activities (B-006, placeholder C15).** A `DEPOSIT`, `WITHDRAWAL`, `FEE`, `DIVIDEND` or
  `RETURN_OF_CAPITAL` with amount 0 is refused at posting with `ZERO_AMOUNT` (`ZeroAmountException`), because it
  would post no lines and V3 refuses entries without lines. Owner to decide (`docs/owner-inputs.md` C15).
- **`idempotency_key` is UNIQUE** in `ledger.entry`, so a duplicate key fails at the database. L-02 adds the
  key-plus-payload-hash behaviour on top (ADR 0001).
- **Lines lose some activity detail.** A transfer-in's book value (`amountMinor`) is not a cash movement and posts no
  line; dividend and return-of-capital lines do not name the security. Calculation must read these from the activity
  (L-05 outbox payload), not from the lines.
- **Reversals (L-04, migration V5).** A reversal is an ordinary entry (same V3 sealing and balance checks) plus one
  row in the append-only `ledger.reversal(reversal_entry_id, original_entry_id UNIQUE, reason)`. "Reversed" is derived
  from that row, so the original entry is never updated. The database also checks that the link is written in the
  reversal entry's own transaction, that the original is not itself a reversal (placeholder C16) and that the lines
  mirror the original's at commit. No foreign keys: with one, Postgres refuses `TRUNCATE ledger.entry, ledger.line`
  before the append-only trigger fires, so a trigger checks existence instead. The reversal copies the original's
  effective date and source sequence (C17), keys are never reused (C18), and a reversal (or correction) that would
  leave an investor account below 0 units of a security at any point in calculation order is refused
  `NEGATIVE_SHARES` (invariant 7). A correction counts its replacement in that check; the owner may prefer to ignore it.
- **Canonical order with reversals (B-009, amends placeholder C4).** Because a reversal carries its original's date and
  source sequence, ordering by `(effective_date, source_seq, idempotency_key)` alone can place a reversal before the
  entry it cancels (a key such as `REV-...` sorts before `TP-...`), and a replay would see shares sold that it does not
  hold. The canonical order is therefore: a reversal takes its original's `(date, source_seq, key)` and sorts right
  after it (original depth 0, reversal depth 1). Implemented by `CanonicalOrder` and
  `LedgerRepository.entriesInCanonicalOrder()`; this order keeps every row and is what audit and entry listings use. It cannot also keep holdings non-negative (B-011); see the next item.
- **Replay skips reversed pairs (B-011, placeholder C19, provisionally accepted by owner 2026-11-19).** When a later
  sell and an earlier buy are both reversed, the adjacent-pair order above (buy, REV-buy, sell, REV-sell) takes the
  holding negative, though each reversal was valid when made. Replay and calculation therefore skip every reversed
  entry together with its reversal (they net to zero, invariant 4). The ledger still stores both rows; audit and
  `entriesInCanonicalOrder()` still list them. Implemented by `CanonicalOrder.forReplay` and
  `LedgerRepository.entriesForReplay()`; the L-06 engine must use these, not the full listing. The `NEGATIVE_SHARES`
  check already left reversed pairs out of its walk. Alternative not taken: order a reversal by its own sequence
  (breaks C17).
- **Reversal chains at the database (B-008, migration V6).** `reversal_checks` refuses a link when the original is
  already a reversal and also when the reversal entry is already someone's original, so no chain A <- R <- R2 can
  commit whichever order the links are written in.
- **Share check arithmetic (B-010).** The `NEGATIVE_SHARES` walk covers only the affected account and security and uses
  exact (`BigInteger`) running totals, so holdings above `Long.MAX_VALUE` units cannot make it throw.
- **L-02 tables (V4).** `ledger.activity` (key, payload hash, canonical fields, `recorded_at`) and `ledger.outbox`
  (one row per new entry, no sent marker; L-05 adds event columns and a delivery table, below) are written in the entry's transaction, append-only like the others. They
  carry no foreign keys to `ledger.entry`: Postgres reports an FK before the append-only trigger fires, which would
  break "TRUNCATE ledger.entry, ledger.line" failing with "append-only". Pairing is by UNIQUE `entry_id` and
  `idempotency_key`. Concurrent duplicates are serialised by the append lock; the unique keys are the backstop.
- **Outbox events and delivery (L-05, migration V7; placeholders C5, C25, C26).** Each `ledger.outbox` row now carries
  its event: `event_type` (`ACTIVITY_POSTED` or `ENTRY_REVERSED`), `taxpayer_id` (the message key) and `payload`, the
  event body as JSON text without `eventId`. The relay sends `eventId` (= `outbox_id`) followed by the stored body, so
  every copy of an event is byte-identical; the body is stored rather than rebuilt so that a correction's replacement,
  which has no `ledger.activity` row, still carries the activity's fields for L-06. `text`, not `jsonb`, because
  `jsonb` reorders keys. Rows written before V7 have NULL event columns (V7 cannot fill them without an `UPDATE`); a
  `NOT VALID` CHECK binds every new row, and the relay derives an old row's event from its activity row.
  - **Reversals and corrections publish (C25).** A reversal writes `ENTRY_REVERSED` (keyed by the reversed entry's
    taxpayer, with `reversesEntryId` and `reason`) in its own transaction; a correction writes `ENTRY_REVERSED` then
    `ACTIVITY_POSTED` for the replacement. Entries written by the L-03 direct `post`/`append` APIs have no taxpayer and
    no outbox row, and their reversals publish nothing (there is nothing to retract). The service never calls those
    APIs; the owner may prefer `post` to publish too.
  - **Sealing.** A trigger refuses an outbox row unless its entry was created in the same transaction (equal `xmin`,
    as for lines and reversal links), so a committed entry cannot be republished under a new event id; it also checks
    that `ENTRY_REVERSED` is used exactly for entries with a reversal link.
  - **"Sent" is append-only (C5).** `ledger.outbox_delivery(outbox_id PK, topic, kafka_partition, kafka_offset,
    delivered_at)` gets one row per broker-acknowledged event, written only after the ack; nothing is updated. Pending
    is outbox minus delivery, a `NOT EXISTS` anti-join on the two primary keys (the delivery PK is the index). `ON
    CONFLICT DO NOTHING` lets two relays race without an error. A trigger refuses a delivery row for an outbox row
    that does not exist (it would otherwise hide that future event). Same append-only trigger, grants and no-FK rule as
    the other tables. Retention is out of scope, so both tables grow without bound.
  - **Delivery (C26).** At-least-once to `ledgerline.activities.v1`, key = taxpayer id, header `eventId`. A crash after
    the ack and before the delivery row is written leaves the row pending, so it is sent again: two identical copies,
    deduplicated by the L-12 consumer on `eventId`. Producer: `acks=all`, idempotence on, at most 5 requests in
    flight, 30 s delivery timeout, no Kafka transactions (rejected in the build plan).
  - **Relay cost per drain.** One query for up to 500 sendable pending rows, then rounds: each round sends the next
    row of every taxpayer in the batch and waits for those acks; then one transaction appends every delivery (and
    parked) row as a JDBC batch. No lock or transaction per event. Each relay also remembers the highest id below which
    everything is delivered, parked or held back behind a parked row, and queries above it, so the anti-join does not
    rescan history. This relies on event ids increasing in commit order: every outbox insert happens under the append
    lock held to commit, and the identity has `CACHE 1`. Changing either breaks this and per-key order.
  - **Per-taxpayer order (B-015, round 2).** A taxpayer's next row is sent only after the broker acknowledged the
    previous one (strictly serial per taxpayer, parallel across taxpayers). If a send fails, that taxpayer stops for
    the rest of the drain and the next drain resends from the failed row, so no later event's first copy can reach the
    topic before an earlier one, even across drains and across two relays. Cost: one round trip per event per
    taxpayer (a single busy taxpayer drains at about one event per linger + round trip). Round 1 pipelined every send
    and only held back *recording*, which let a later event reach the topic first; producer idempotence alone does not
    prevent that once a batch has failed.
  - **Poison events (B-016, B-017; OWNER-INPUT: C27, placeholder).** Ingestion refuses an activity, reversal or
    correction whose message value would exceed the configured maximum (`OUTBOX_MAX_EVENT_BYTES`, default 900 KiB,
    under the producer's explicit 1 MiB `max.request.size` and Redpanda's 1 MiB default): `EVENT_TOO_LARGE`, 422 on
    `POST /v1/activities`, a per-line rejection in a CSV import, nothing written. In the relay, a row whose body cannot
    be decoded, whose value is over the maximum, or that the producer or broker refuses as too large
    (`RecordTooLargeException`) is appended to the append-only `ledger.outbox_parked` (V8: event id, taxpayer, reason
    code, detail; SELECT/INSERT for the app role) and logged at ERROR; `OutboxStore.parkedEvents()` lists them. It is
    never retried (no hot loop, no repeated copies) and other taxpayers keep flowing. It stays pending, and every later
    row of the same taxpayer is held back (left out of the relay's query), because publishing past it would let the
    consumer compute ACB without it. Nothing releases a parked row yet: the owner decides C27 (for example a release
    or skip record, or raising limits and re-posting). A legacy pre-V7 row with no activity row is parked with no
    taxpayer, so it blocks nothing.
  - **Broker errors never throw.** A send that throws (for example no metadata within 10 s while the broker is down)
    or is not acknowledged stops that taxpayer for the drain; the row stays pending and counts as `failed`. Database
    errors do throw. `start()` runs a daemon thread that polls every 100 ms, backs off 1 s after a failure, and
    survives both kinds of outage.
  - **Drainer placement.** The relay runs inside the service process (`Main` starts it as the app role and stops it on
    shutdown). Simplest; a separate process can reuse `OutboxRelay` unchanged. No fault hook in production code: the
    L-05 tests inject crashes at the producer they hand to the relay.
- Verified by `L03LedgerSpec`, `BalanceInvariantPropertyTest` and `ImmutabilityInvariantPropertyTest`. L-05: `L05OutboxSpec`, `OutboxDeliveryPropertyTest`.
