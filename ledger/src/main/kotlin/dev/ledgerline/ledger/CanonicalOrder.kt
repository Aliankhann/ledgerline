package dev.ledgerline.ledger

import java.time.LocalDate

/**
 * The canonical (calculation and replay) order of ledger entries: effective date, then source sequence, then
 * idempotency key (placeholder C4), with one amendment: a reversal takes its original's place in that order and sorts
 * immediately after it. Without the amendment a reversal, which carries the original's date and source sequence
 * (C17), would sort by its own key and could come before the entry it cancels (B-009).
 *
 * Sort key: (date, source sequence, key) of the entry itself, or of the entry it reverses if it is a reversal; then
 * depth 0 (original) before depth 1 (reversal). Keys are unique, so nothing else can fall between the pair. Reversing
 * a reversal is refused (C16), so depth is never more than 1.
 */
object CanonicalOrder {
    private class Key(val date: LocalDate, val sourceSeq: Long, val key: String, val depth: Int)

    private val comparator: Comparator<Key> =
        compareBy<Key>({ it.date }, { it.sourceSeq }, { it.key }, { it.depth })

    /** [entries] in canonical order. A reversal whose original is not in [entries] sorts by its own tuple. */
    fun sort(entries: List<PostedEntry>): List<PostedEntry> {
        val bySequence = entries.associateBy { it.sequence }
        return entries.sortedWith { a, b -> comparator.compare(keyOf(a, bySequence), keyOf(b, bySequence)) }
    }

    private fun keyOf(
        e: PostedEntry,
        bySequence: Map<Long, PostedEntry>,
    ): Key {
        val original = e.reversesSequence?.let { bySequence[it] }
        return if (original == null) {
            Key(e.effectiveDate, e.sourceSeq, e.idempotencyKey, 0)
        } else {
            Key(original.effectiveDate, original.sourceSeq, original.idempotencyKey, 1)
        }
    }
}
