package dev.ledgerline.testsupport

import dev.ledgerline.ledger.PostedEntry

/** Test-side checks over entries listed in the public canonical order (C4 as amended for B-009, ADR 0005). */
object CanonicalReplay {
    /** Reversals that appear before the entry they reverse (empty when the order is sound). */
    fun reversalsBeforeOriginal(ordered: List<PostedEntry>): List<String> {
        val position = ordered.withIndex().associate { (i, e) -> e.sequence to i }
        return ordered.withIndex().filter { (i, e) ->
            e.reversesSequence?.let { orig -> (position[orig] ?: Int.MAX_VALUE) > i } ?: false
        }.map { it.value.idempotencyKey }
    }

    /**
     * Walks [ordered] keeping a running holding per (account, unit) for units starting with `SEC:` on investor accounts (SYS: counter-accounts legitimately go negative). Returns the
     * lowest holding seen, or 0 if there are no share lines.
     */
    fun lowestHolding(ordered: List<PostedEntry>): Long {
        val held = mutableMapOf<Pair<String, String>, Long>()
        var lowest = 0L
        ordered.forEach { e ->
            e.lines.filter {
                it.unit.startsWith("SEC:") && !it.account.startsWith("SYS:")
            }.map { it.account to it.unit }.distinct().forEach {
                    k ->
                val now = Math.addExact(held[k] ?: 0L, LineMath.delta(e.lines, k.first, k.second))
                held[k] = now
                if (now < lowest) lowest = now
            }
        }
        return lowest
    }
}
