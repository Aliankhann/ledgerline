package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import java.sql.Connection
import java.time.LocalDate
import javax.sql.DataSource

/**
 * The append-only ledger store. Works with connections that run as role `ledgerline_app` (SELECT and INSERT only).
 * Each append is one transaction; appends are serialised by a transaction-scoped advisory lock, so
 * [PostedEntry.sequence] increases in commit order (draft ADR 0005).
 */
class LedgerRepository(private val dataSource: DataSource) {
    /** Posts [activity] as one entry with the lines from [Posting.linesFor]. */
    fun post(activity: Activity): PostedEntry =
        append(
            EntryRequest(
                activity.idempotencyKey,
                activity.effectiveDate,
                activity.sourceSeq,
                Posting.linesFor(activity),
            ),
        )

    /** Direct posting API. Refuses unbalanced lines with [UnbalancedEntryException] before touching the database. */
    fun append(request: EntryRequest): PostedEntry {
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey is blank" }
        Posting.requireBalanced(request.lines)
        return dataSource.connection.use { c -> inTransaction(c) { insert(c, request) } }
    }

    /** Every entry with its lines, in ascending sequence. */
    fun entries(): List<PostedEntry> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT e.entry_id, e.idempotency_key, e.effective_date, e.source_seq, " +
                    "l.account, l.unit, l.side, l.amount " +
                    "FROM ledger.entry e JOIN ledger.line l ON l.entry_id = e.entry_id " +
                    "ORDER BY e.entry_id, l.line_no",
            ).use { st ->
                st.executeQuery().use { rs ->
                    val entries = mutableListOf<PostedEntry>()
                    var lines = mutableListOf<LedgerLine>()
                    while (rs.next()) {
                        val id = rs.getLong(1)
                        if (entries.lastOrNull()?.sequence != id) {
                            lines = mutableListOf()
                            val date = rs.getObject(3, LocalDate::class.java)
                            entries += PostedEntry(id, rs.getString(2), date, rs.getLong(4), lines)
                        }
                        val side = Side.valueOf(rs.getString(7))
                        lines += LedgerLine(rs.getString(5), rs.getString(6), side, rs.getLong(8))
                    }
                    entries.map { it.copy(lines = it.lines.toList()) }
                }
            }
        }

    private fun insert(
        c: Connection,
        request: EntryRequest,
    ): PostedEntry {
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.setLong(1, APPEND_LOCK)
            it.execute()
        }
        val sequence =
            c.prepareStatement(
                "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) VALUES (?, ?, ?) " +
                    "RETURNING entry_id",
            ).use { st ->
                st.setString(1, request.idempotencyKey)
                st.setObject(2, request.effectiveDate)
                st.setLong(3, request.sourceSeq)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        c.prepareStatement(
            "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            request.lines.forEachIndexed { i, l ->
                st.setLong(1, sequence)
                st.setInt(2, i + 1)
                st.setString(3, l.account)
                st.setString(4, l.unit)
                st.setString(5, l.side.name)
                st.setLong(6, l.amount)
                st.addBatch()
            }
            st.executeBatch()
        }
        return PostedEntry(sequence, request.idempotencyKey, request.effectiveDate, request.sourceSeq, request.lines)
    }

    private fun <T> inTransaction(
        c: Connection,
        block: () -> T,
    ): T {
        val previous = c.autoCommit
        c.autoCommit = false
        try {
            val result = block()
            c.commit()
            return result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = previous
        }
    }

    private companion object {
        /** Advisory lock key that serialises appends ("LEDGERLN" in ASCII). */
        const val APPEND_LOCK = 0x4C45444745524C4EL
    }
}
