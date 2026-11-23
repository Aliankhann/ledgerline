package dev.ledgerline.calc

import java.sql.Connection

/**
 * Applies an ENTRY_REVERSED event to the live pool (bug B-024). The pool of the reversed activity's (taxpayer,
 * security) is rebuilt by [AcbEngine.replay] over the stored activities that are not reversed, so it equals what a C19
 * replay gives: as if the reversed activity and its reversal had never happened.
 *
 * Runs on the caller's connection, inside the consumer's one transaction. Only activities of entries before the
 * reversal's own entry are read: events of one taxpayer are ordered, so those were already consumed, and later ones
 * are applied by their own events. A rejection from the replay is thrown, never clipped.
 */
internal class ReversalRecompute(private val engine: AcbEngine) {
    fun apply(
        c: Connection,
        reversedEntryId: Long,
        reversalEntryId: Long,
    ) {
        val (taxpayer, security) = reversedKey(c, reversedEntryId) ?: return
        // Rejections already present with the reversed activity still in are not the reversal's doing (B-026).
        val before =
            engine.replay(
                effectiveHistory(
                    c,
                    taxpayer,
                    security,
                    reversalEntryId,
                    reversalEntryId,
                    includeReversed = reversedEntryId,
                ),
            )
        // B-029: a correction's replacement (linked in ledger.correction, same transaction) is part of the same unit,
        // so the check runs on the state after both. A reversal posted alone has no link and is checked as before.
        // The link is read inside the history query (correctionOf), not as a separate lookup.
        val replay =
            rebuild(
                c,
                taxpayer,
                security,
                reversalEntryId,
                reversalsUpTo = reversalEntryId,
                correctionOf = reversalEntryId,
            )
        val existing = before.rejections.map { it.idempotencyKey to it.code }.toSet()
        val introduced = replay.rejections.filter { (it.idempotencyKey to it.code) !in existing }
        check(introduced.isEmpty()) {
            "reversal of entry $reversedEntryId makes the replay of $taxpayer/$security reject: $introduced"
        }
    }

    /**
     * Rebuilds the stored pool of (taxpayer, security) by replaying, in canonical order, the stored non-reversed
     * activities of entries before [beforeEntryId]. Rejections stay in the returned replay (never clipped, never thrown).
     */
    fun rebuild(
        c: Connection,
        taxpayer: String,
        security: String,
        beforeEntryId: Long,
        reversalsUpTo: Long = beforeEntryId - 1,
        correctionOf: Long? = null,
    ): Replay {
        val replay =
            engine.replay(
                effectiveHistory(c, taxpayer, security, beforeEntryId, reversalsUpTo, correctionOf = correctionOf),
            )
        val pool = replay.pools[PoolKey(taxpayer, security)]
        if (pool == null) {
            c.prepareStatement("DELETE FROM calc.pool WHERE taxpayer_id = ? AND security_id = ?").use {
                it.setString(1, taxpayer)
                it.setString(2, security)
                it.executeUpdate()
            }
        } else {
            c.prepareStatement(
                "INSERT INTO calc.pool (taxpayer_id, security_id, shares, total_acb) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (taxpayer_id, security_id) DO UPDATE SET shares = EXCLUDED.shares, " +
                    "total_acb = EXCLUDED.total_acb",
            ).use {
                it.setString(1, taxpayer)
                it.setString(2, security)
                it.setLong(3, pool.shares)
                it.setLong(4, pool.totalAcb)
                it.executeUpdate()
            }
        }
        return replay
    }

    /** The (taxpayer, security) of a reversed pool activity, or null if it has none (nothing to recompute). */
    private fun reversedKey(
        c: Connection,
        entryId: Long,
    ): Pair<String, String>? =
        c.prepareStatement("SELECT taxpayer_id, security_id FROM ledger.activity WHERE entry_id = ?").use {
            it.setLong(1, entryId)
            it.executeQuery().use { rs ->
                if (rs.next() && rs.getString(2) != null) rs.getString(1) to rs.getString(2) else null
            }
        }

    /**
     * B-030: an entry is excluded only by a reversal that existed at the event being applied (reversal entry id up to
     * [reversalsUpTo], extended to the replacement of a correction unit). Reversals posted later belong to later events.
     */
    private fun effectiveHistory(
        c: Connection,
        taxpayer: String,
        security: String,
        beforeEntryId: Long,
        reversalsUpTo: Long,
        includeReversed: Long? = null,
        correctionOf: Long? = null,
    ): List<PostedActivity> =
        c.prepareStatement(
            "SELECT a.activity_id, a.entry_id, a.idempotency_key, a.taxpayer_id, a.account_id, a.account_type, " +
                "a.activity_type, a.effective_date, a.settlement_date, a.security_id, a.quantity, a.currency, " +
                "a.amount, a.commission, a.counter_currency, a.counter_amount, a.description, a.source_seq " +
                "FROM ledger.activity a WHERE a.taxpayer_id = ? AND a.security_id = ? AND (a.entry_id < ?" +
                (
                    if (correctionOf != null) {
                        " OR a.entry_id IN (SELECT replacement_entry_id FROM ledger.correction " +
                            "WHERE reversal_entry_id = ?)) "
                    } else {
                        ") "
                    }
                ) +
                "AND NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = a.entry_id " +
                (
                    if (correctionOf != null) {
                        "AND r.reversal_entry_id <= GREATEST(?, COALESCE((SELECT MAX(k.replacement_entry_id) " +
                            "FROM ledger.correction k WHERE k.reversal_entry_id = ?), 0)) "
                    } else {
                        "AND r.reversal_entry_id <= ? "
                    }
                ) +
                (if (includeReversed != null) "AND r.original_entry_id <> ?) " else ") ") +
                "ORDER BY a.entry_id",
        ).use {
            it.setString(1, taxpayer)
            it.setString(2, security)
            it.setLong(3, beforeEntryId)
            var next = 4
            if (correctionOf != null) it.setLong(next++, correctionOf)
            it.setLong(next++, reversalsUpTo)
            if (correctionOf != null) it.setLong(next++, correctionOf)
            if (includeReversed != null) it.setLong(next, includeReversed)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.posted()) } }
        }
}
