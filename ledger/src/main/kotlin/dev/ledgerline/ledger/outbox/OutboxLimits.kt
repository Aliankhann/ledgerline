package dev.ledgerline.ledger.outbox

/**
 * OWNER-INPUT: C27 (placeholder): the largest message value (bytes, UTF-8) an outbox event may have. Ingestion refuses
 * an activity or reversal whose event would be larger ([EventTooLargeException], code [EventTooLargeException.CODE]),
 * so nothing unpublishable reaches the outbox (B-016). The default leaves room under the producer's 1 MiB request
 * limit ([OutboxProducers.MAX_REQUEST_BYTES]) and Redpanda's default 1 MiB batch limit for the key, headers and
 * record overhead.
 */
object OutboxLimits {
    const val DEFAULT_MAX_EVENT_BYTES = 900 * 1024

    /** `"eventId":` plus the longest `Long` and a comma: what [OutboxPayloads.messageValue] adds to a stored body. */
    private const val EVENT_ID_OVERHEAD = 10 + 20 + 1

    /** A configured maximum must be positive and leave the same headroom as the default. */
    fun requireValid(maxEventBytes: Int) {
        require(maxEventBytes in 1..DEFAULT_MAX_EVENT_BYTES) {
            "max event bytes must be in 1..$DEFAULT_MAX_EVENT_BYTES (under the producer's " +
                "${OutboxProducers.MAX_REQUEST_BYTES}-byte request limit): $maxEventBytes"
        }
    }

    /** An upper bound on the message value's size for stored body [payload]. */
    internal fun messageBytesUpperBound(payload: String): Int = utf8Length(payload) + EVENT_ID_OVERHEAD

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n +=
                when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
            i++
        }
        return n
    }
}

/**
 * The event for an activity or reversal would be [eventBytes] bytes, over the configured [maxBytes]
 * (OWNER-INPUT: C27). Thrown before the transaction commits, so nothing is written and the key is not consumed (C21).
 */
class EventTooLargeException(val idempotencyKey: String, val eventBytes: Int, val maxBytes: Int) :
    RuntimeException(
        "$CODE: the event for $idempotencyKey would be about $eventBytes bytes; the maximum is $maxBytes",
    ) {
    val code: String = CODE

    companion object {
        const val CODE = "EVENT_TOO_LARGE"
    }
}
