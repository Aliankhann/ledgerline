package dev.ledgerline.testsupport

import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.PostedEntry
import dev.ledgerline.ledger.Side
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Hand-written SQL against the ledger, the way a buggy or hostile caller holding the app role could write it. Used to
 * attack the database-side guarantees without going through `LedgerRepository`.
 */
object RawLedger {
    private const val APPEND_LOCK = 0x4C45444745524C4EL

    fun lock(c: Connection) {
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.setLong(1, APPEND_LOCK)
            it.execute()
        }
    }

    /** Inserts an entry row and returns its id. */
    fun entry(
        c: Connection,
        key: String,
        date: LocalDate = LocalDate.of(2025, 1, 7),
        sourceSeq: Long = 1,
    ): Long =
        c.prepareStatement(
            "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) VALUES (?, ?, ?) " +
                "RETURNING entry_id",
        ).use { st ->
            st.setString(1, key)
            st.setObject(2, date)
            st.setLong(3, sourceSeq)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    fun lines(
        c: Connection,
        entryId: Long,
        lines: List<LedgerLine>,
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            lines.forEachIndexed { i, l ->
                st.setLong(1, entryId)
                st.setInt(2, i + 1)
                st.setString(3, l.account)
                st.setString(4, l.unit)
                st.setString(5, l.side.name)
                st.setLong(6, l.amount)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    fun link(
        c: Connection,
        reversalEntryId: Long,
        originalEntryId: Long,
        reason: String = "raw",
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.reversal (reversal_entry_id, original_entry_id, reason) VALUES (?, ?, ?)",
        )
            .use { st ->
                st.setLong(1, reversalEntryId)
                st.setLong(2, originalEntryId)
                st.setString(3, reason)
                st.execute()
            }
    }

    /**
     * Runs [block] in one transaction on [ds] and commits. Returns the [SQLException] raised by the block or by the
     * commit (deferred constraint triggers), or null if the transaction committed. A failure rolls everything back.
     */
    fun attempt(
        ds: DataSource,
        block: (Connection) -> Unit,
    ): SQLException? =
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                lock(c)
                block(c)
                c.commit()
                null
            } catch (e: SQLException) {
                c.rollback()
                e
            }
        }

    /** [original]'s lines with every side swapped: what a mirror-image reversal's lines look like. */
    fun mirrorLines(original: PostedEntry): List<LedgerLine> =
        original.lines.map {
            it.copy(
                side = if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT,
            )
        }
}
