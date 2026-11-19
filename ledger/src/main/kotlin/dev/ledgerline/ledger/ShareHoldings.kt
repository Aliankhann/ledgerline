package dev.ledgerline.ledger

import java.sql.Connection
import java.time.LocalDate

/**
 * Invariant 7 applied to reversals: reversing an entry must not leave any investor account with fewer than 0 units of
 * a security at any point in calculation order (effective date, source sequence, idempotency key).
 *
 * Reversed entries and their reversals cancel exactly, so both are left out of the walk; the entry being reversed is
 * left out too, and an optional [Replacement] (a correction's new activity) is added. Runs inside the append
 * transaction, after the append lock is held, so the ledger cannot change under it.
 */
internal object ShareHoldings {
    /** An entry about to be appended in the same transaction as the reversal. */
    class Replacement(val request: EntryRequest)

    private class Move(val date: LocalDate, val sourceSeq: Long, val key: String, val delta: Long)

    /** Throws [ReversalRefusedException] `NEGATIVE_SHARES` if reversing [original] would break invariant 7. */
    fun requireNonNegativeWithout(
        c: Connection,
        original: PostedEntry,
        replacement: Replacement?,
    ) {
        val extraLines = replacement?.request?.lines.orEmpty()
        (original.lines + extraLines)
            .filter {
                it.unit.startsWith(Posting.SECURITY_UNIT_PREFIX) &&
                    !it.account.startsWith(Posting.SYSTEM_ACCOUNT_PREFIX)
            }
            .map { it.account to it.unit }
            .distinct()
            .forEach { (account, unit) ->
                val moves = storedMoves(c, account, unit, original.sequence).toMutableList()
                replacement?.request?.let { r ->
                    r.lines.filter { it.account == account && it.unit == unit }.forEach {
                        moves += Move(r.effectiveDate, r.sourceSeq, r.idempotencyKey, signed(it))
                    }
                }
                var held = 0L
                moves.sortedWith(compareBy({ it.date }, { it.sourceSeq }, { it.key })).forEach {
                    held = Math.addExact(held, it.delta)
                    if (held < 0) {
                        throw ReversalRefusedException(
                            ReversalRefusedException.NEGATIVE_SHARES,
                            original.sequence,
                        )
                    }
                }
            }
    }

    private fun storedMoves(
        c: Connection,
        account: String,
        unit: String,
        excludedEntry: Long,
    ): List<Move> =
        c.prepareStatement(
            "SELECT e.effective_date, e.source_seq, e.idempotency_key, l.side, l.amount " +
                "FROM ledger.line l JOIN ledger.entry e ON e.entry_id = l.entry_id " +
                "WHERE l.account = ? AND l.unit = ? AND e.entry_id <> ? " +
                "AND NOT EXISTS (SELECT 1 FROM ledger.reversal r " +
                "WHERE r.original_entry_id = e.entry_id OR r.reversal_entry_id = e.entry_id)",
        ).use { st ->
            st.setString(1, account)
            st.setString(2, unit)
            st.setLong(3, excludedEntry)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val delta = if (rs.getString(4) == Side.DEBIT.name) rs.getLong(5) else -rs.getLong(5)
                        add(Move(rs.getObject(1, LocalDate::class.java), rs.getLong(2), rs.getString(3), delta))
                    }
                }
            }
        }

    private fun signed(l: LedgerLine): Long = if (l.side == Side.DEBIT) l.amount else -l.amount
}
