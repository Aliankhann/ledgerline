# B-015 After a failed ack mid-drain, a taxpayer's later events reach the topic before the retried earlier one

**Story:** L-05 · **Severity:** major · **Status:** Fixed (pending CI) · **Round:** 1

## Reproduction

1. Command (needs Docker; not run locally, no Docker in this sandbox; CI runs it on `l-05`):
   ```
   ./gradlew :tests:test -PincludeIntegration --tests 'dev.ledgerline.spec.L05AttackDeliverySpec'
   ```
   Test: "L-05 attack AC7 (B-015): a failed ack mid-drain must not let a later event of that taxpayer be seen first".
2. The test posts 10 deposits, alternating `TP-0001` (event ids 1, 3, 5, 7, 9) and `TP-0002` (2, 4, ..., 10).
3. The relay's producer is `AckFailingProducer(real, failSends = setOf(3))`: send number 3 (event 3, `TP-0001`)
   never reaches the broker and its future fails with a Kafka `TimeoutException`, as a record does when its delivery
   timeout expires during a broker failure. Every other send goes to the real Redpanda producer.
4. `drainOnce(500)` twice, then read the topic and list, per key, the event ids in the order a consumer that
   deduplicates on `eventId` (the L-12 inbox) first sees them.

## Expected

AC7: a taxpayer's events appear in strictly increasing ledger sequence order. The `L05OutboxSpec` API contract
(KDoc): "Rows of one taxpayer are never sent past an earlier unacked one." For `TP-0001`: first seen `1, 3, 5, 7, 9`.

## Actual

Not observed yet: derived from reading `OutboxRelay.drainOnce` at 68c7b78; the test is expected to fail in CI and
this section will be replaced with the CI output. All sends of a batch are pipelined before any ack is awaited, so
events 5, 7 and 9 are written to the partition while event 3 has failed. The relay then holds 5, 7 and 9 back (not
recorded) and the next drain sends 3, 5, 7, 9 again. Expected `TP-0001` partition order: `1, 5, 7, 9, 3, 5, 7, 9`,
first seen `1, 5, 7, 9, 3`. A deduplicating consumer applies 5, 7 and 9 before 3 and drops the later correct copies.

## Notes

Dev already documents this in draft ADR 0005 ("Per-taxpayer order": "a later event's first copy can reach the topic
before a retried earlier event") and offers strictly serial sends per taxpayer as the fix, at one round trip per
event. Major because it breaks AC7 and C26 (the consumer would compute ACB on events in the wrong order, for example a
sell before the buy it depends on), not because it is likely: it needs a send to fail while later sends of the same
key succeed. With a real idempotent producer that mostly happens when a batch expires on its delivery timeout while a
later batch for the partition is accepted. Owner to choose: fix (serial per key, or stop sending a key after its first
unacked record and wait for that ack before sending more), or accept and require the L-12 consumer to re-order by
ledger sequence (then AC7's wording must change).

## Fix (dev, round 2)

Fixed (pending CI) in `OutboxRelay.drainOnce`: sends are strictly serial per taxpayer and parallel across taxpayers
(rounds of one event per taxpayer; a taxpayer's next event is sent only after the previous one is acknowledged). A
failed send stops that taxpayer for the rest of the drain; the next drain resends from the failed event. For this
reproduction, events 5, 7 and 9 are not sent in the first drain, so `TP-0001`'s topic order is `1, 3, 5, 7, 9`.
Other taxpayers keep flowing. Draft ADR 0005 "Per-taxpayer order" updated. Not run locally (no Docker).
