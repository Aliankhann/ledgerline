package dev.ledgerline.ledger.outbox

// OWNER-INPUT: C26 (placeholder): at-least-once delivery to this topic, keyed by taxpayer id, each message carrying a
// stable eventId (the outbox row id) in a header and in its value, so a consumer can apply each event once (L-12).
object OutboxTopic {
    const val NAME = "ledgerline.activities.v1"
}

/** OWNER-INPUT: C25: reversals (and the reversal half of a correction) publish [ENTRY_REVERSED]. */
enum class OutboxEventType { ACTIVITY_POSTED, ENTRY_REVERSED }

/**
 * One `ledger.outbox` row. [eventId] is the outbox row id (C26); [entryId] is the entry's ledger sequence (ADR 0005).
 * For a reversal, [taxpayerId] is the reversed entry's taxpayer and [idempotencyKey] is the reversal's own key.
 */
data class OutboxEvent(
    val eventId: Long,
    val eventType: OutboxEventType,
    val taxpayerId: String,
    val entryId: Long,
    val idempotencyKey: String,
)

/**
 * The outcome of one [OutboxRelay.drainOnce]. [published]: rows acknowledged by the broker and recorded as delivered.
 * [failed]: rows sent in this call that stay pending (not acknowledged, or held back behind an earlier failure of the
 * same taxpayer so that per-key order is kept).
 */
data class DrainResult(val published: Int, val failed: Int)
