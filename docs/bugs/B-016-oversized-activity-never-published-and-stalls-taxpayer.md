# B-016 An accepted activity too large for the broker is never published and stalls its taxpayer forever

**Story:** L-05 · **Severity:** major · **Status:** Fixed (pending CI) · **Round:** 1

## Reproduction

1. Command (needs Docker; not run locally, no Docker in this sandbox; CI runs it on `l-05`):
   ```
   ./gradlew :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L05AttackRobustnessSpec'
   ```
   Test: "L-05 attack (B-016): an activity accepted with a 1.5 MB description is published or refused, never stuck".
2. `POST /v1/activities` a deposit `TP-0001-2025-0910` whose `description` is 1 500 000 `x` characters (the body is
   under the route's 4 MiB `MAX_ACTIVITY_BYTES`; nothing limits `description`).
3. Post `TP-0001-2025-0911` (TP-0001) and `TP-0002-2025-0912` (TP-0002), run the production relay
   (`OutboxProducers.create`) for 30 s, read the topic.

## Expected

Either the activity is refused at ingestion (4xx, nothing posted), or it is posted and published. The story: "every
posted ledger entry [reaches] the event stream"; AC3/AC6: an accepted posting drains within seconds. Without a crash
no event should be sent more than once.

## Actual

Not observed yet: derived from reading the code at 68c7b78 (kafka-clients 3.9.0); the test is expected to fail in CI
and this section will be replaced with the CI output. The POST answers 201. The message value is about 1.5 MB, over the
producer's default `max.request.size` of 1 MiB (and Redpanda's default batch limit), so `KafkaProducer.send` returns a
future failed with `RecordTooLargeException` (it does not throw). `drainOnce` counts it as failed and holds back every
later `TP-0001` event in the batch; the background loop retries every second, forever:
- `TP-0001-2025-0910` and `TP-0001-2025-0911` stay pending indefinitely (AC-level loss for the consumer);
- `TP-0001-2025-0911` is pipelined behind it and acknowledged on every retry but never recorded, so a new copy lands on
  the topic about once a second, without bound;
- `TP-0002` is unaffected.

## Notes

Reachable through the public API with one request, and the effect is permanent: every later event of that taxpayer is
blocked and the topic fills with copies. Options for Dev/owner: cap text fields at ingestion so an event always fits
(for example `description` at a few KB, refused with a 422 code), raise `max.request.size` and the topic's
`max.message.bytes` to cover the largest accepted body, or move unsendable events aside (a dead-letter record plus an
alert) while keeping per-key order. CSV import (`/v1/imports`, 32 MiB) has the same unbounded `description`.

## Fix (dev, round 2)

Fixed (pending CI), placeholder OWNER-INPUT: C27 (`docs/owner-inputs.md`):
- Ingestion: `OutboxWrites` refuses any outbox event whose message value would exceed `OUTBOX_MAX_EVENT_BYTES`
  (default 900 KiB, under the producer's explicit 1 MiB `max.request.size`) with `EventTooLargeException`; the
  transaction rolls back and nothing is written. `POST /v1/activities` answers 422 `{"code": "EVENT_TOO_LARGE",
  "field": ""}`; a CSV import reports the line as rejected with that code. Reversals and corrections get the same check.
- Relay: an event over the max, or one the producer or broker refuses with `RecordTooLargeException`, is appended to
  the new append-only `ledger.outbox_parked` (V8, SELECT/INSERT for the app role) with a reason, logged at ERROR and
  never retried (no repeated copies, no hot loop). It holds back later events of its own taxpayer only (C27).
Not run locally (no Docker).
