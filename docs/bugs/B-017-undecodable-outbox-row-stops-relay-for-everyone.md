# B-017 One undecodable outbox row stops the relay for every taxpayer and re-sends the rows before it

**Story:** L-05 · **Severity:** minor · **Status:** Fixed (pending CI) · **Round:** 1

## Reproduction

1. Command (needs Docker; not run locally, no Docker in this sandbox; CI runs it on `l-05`):
   ```
   ./gradlew :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L05AttackRobustnessSpec'
   ```
   Test: "L-05 attack (B-017): one undecodable outbox row does not stop other taxpayers or re-send earlier rows".
2. Post a TP-0001 deposit (event 1). As the table owner, in one transaction, append a balanced entry and an
   ACTIVITY_POSTED outbox row for TP-0001 whose stored payload is `{"eventType": }` (event 2; passes V7's
   `payload LIKE '{%}'` CHECK). Post a TP-0002 deposit (event 3) and a TP-0001 deposit (event 4).
3. Run the production relay for 15 s, read the topic.

## Expected

The bad row cannot be published and may hold back its own taxpayer (per-key order), but event 1 is delivered and
recorded once, and TP-0002's event 3 is published.

## Actual

Not observed yet: derived from reading `OutboxRelay.send` at 68c7b78; the test is expected to fail in CI and this
section will be replaced with the CI output. `OutboxPayloads.messageValue` is called outside the `try` that guards
`producer.send`, so the JSON decoding exception escapes `drainOnce` after event 1 has been handed to the producer and
before any ack is recorded. The loop logs, waits 1 s and repeats: event 1 is re-sent about once a second without bound
and never recorded; events 3 and 4 are never sent. Every later event of every taxpayer is blocked behind the row.

## Notes

Minor because no production writer produces such a row today (`OutboxPayloads` always encodes valid JSON); it needs a
direct database write or a future encoder bug. The blast radius is the whole stream, though. Options: validate the
body at insert (a stricter CHECK, or `payload::jsonb IS NOT NULL` in `outbox_checks`), and/or treat an unencodable row
like a failed send (hold back only its taxpayer, record what was acknowledged before it). The same applies to a
legacy pre-V7 row whose activity row is missing: `pendingBatch` throws `checkNotNull` and blocks every drain.

## Fix (dev, round 2)

Fixed (pending CI): decoding is per event. `OutboxStore.pendingBatch` returns a legacy row it cannot build as an
unreadable row instead of throwing, and `OutboxRelay` builds each message value inside a `try`. An undecodable row is
parked in `ledger.outbox_parked` (V8) with reason `PAYLOAD_UNREADABLE` (or `LEGACY_UNREADABLE`), in the same
transaction that records the drain's deliveries, so event 1 is recorded once and TP-0002's event 3 is published. Its
own taxpayer stays blocked (event 4 stays pending, unsent) to keep per-key order until the owner decides C27. A legacy
row whose taxpayer is unknown blocks nothing. Not run locally (no Docker).
