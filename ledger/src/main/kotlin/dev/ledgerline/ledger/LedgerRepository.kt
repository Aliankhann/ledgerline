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
    fun entries(): List<PostedEntry> = dataSource.connection.use { c -> loadEntries(c, "", 0L) }

    /** Every entry in the order calculation and replay use: see [CanonicalOrder]. */
    fun entriesInCanonicalOrder(): List<PostedEntry> = CanonicalOrder.sort(entries())

    /**
     * Reverses entry [entrySequence] with one appended entry keyed [reversalKey]: the original's lines with the sides
     * swapped, carrying the original's effective date and source sequence (OWNER-INPUT: C17). The same key and entry
     * again returns the existing reversal. Refusals throw [ReversalRefusedException]; nothing is written.
     */
    fun reverse(
        entrySequence: Long,
        reversalKey: String,
        reason: String,
    ): PostedEntry {
        requireReversalArguments(reversalKey, reason)
        return dataSource.connection.use { c ->
            inTransaction(c) {
                lock(c)
                existingReversal(c, entrySequence, reversalKey)
                    ?: appendReversal(c, entrySequence, reversalKey, reason, null)
            }
        }
    }

    /** Whether [entrySequence] is reversed, derived from the reversal's link row. Unknown entry: IllegalArgumentException. */
    fun statusOf(entrySequence: Long): EntryStatus =
        dataSource.connection.use { c ->
            requireNotNull(loadEntry(c, entrySequence)) { "no entry with sequence $entrySequence" }
            c.prepareStatement("SELECT reversal_entry_id FROM ledger.reversal WHERE original_entry_id = ?").use { st ->
                st.setLong(1, entrySequence)
                st.executeQuery().use { rs ->
                    if (rs.next()) {
                        EntryStatus(
                            EntryState.REVERSED,
                            rs.getLong(1),
                        )
                    } else {
                        EntryStatus(EntryState.POSTED, null)
                    }
                }
            }
        }

    /**
     * Reverses [entrySequence] (as [reverse], keyed [correctionKey]) and posts [replacement] in one transaction; if
     * either fails neither is written. The same [correctionKey] again returns the existing result.
     */
    fun correct(
        entrySequence: Long,
        correctionKey: String,
        reason: String,
        replacement: Activity,
    ): CorrectionResult {
        requireReversalArguments(correctionKey, reason)
        val request =
            EntryRequest(
                replacement.idempotencyKey,
                replacement.effectiveDate,
                replacement.sourceSeq,
                Posting.linesFor(replacement),
            )
        require(request.idempotencyKey.isNotBlank()) { "idempotencyKey is blank" }
        Posting.requireBalanced(request.lines)
        return dataSource.connection.use { c ->
            inTransaction(c) {
                lock(c)
                val existing = existingReversal(c, entrySequence, correctionKey)
                if (existing != null) {
                    val posted = findByKey(c, request.idempotencyKey)
                    val same = posted?.let { it.effectiveDate == request.effectiveDate && it.lines == request.lines }
                    check(posted != null && same == true) {
                        "idempotency key $correctionKey was not used for a correction with this replacement"
                    }
                    CorrectionResult(existing, posted)
                } else {
                    val reversal =
                        appendReversal(c, entrySequence, correctionKey, reason, ShareHoldings.Replacement(request))
                    CorrectionResult(reversal, insert(c, request))
                }
            }
        }
    }

    private fun requireReversalArguments(
        key: String,
        reason: String,
    ) {
        require(key.isNotBlank()) { "idempotencyKey is blank" }
        require(reason.isNotBlank()) { "reason is blank" }
    }

    /**
     * The reversal already stored under [key], if it reverses [entrySequence]. A key that exists for anything else is
     * refused: OWNER-INPUT: C18, a key stays bound to its first payload forever, even after that entry is reversed.
     */
    private fun existingReversal(
        c: Connection,
        entrySequence: Long,
        key: String,
    ): PostedEntry? {
        val existing = findByKey(c, key) ?: return null
        check(existing.reversesSequence == entrySequence) {
            "idempotency key $key is already bound to a different payload (entry ${existing.sequence})"
        }
        return existing
    }

    /** Runs the refusal checks, then appends the reversal entry and its link row. The append lock is held. */
    private fun appendReversal(
        c: Connection,
        entrySequence: Long,
        key: String,
        reason: String,
        replacement: ShareHoldings.Replacement?,
    ): PostedEntry {
        val original =
            loadEntry(c, entrySequence)
                ?: throw ReversalRefusedException(ReversalRefusedException.UNKNOWN_ENTRY, entrySequence)
        // OWNER-INPUT: C16 reversing a reversal is refused.
        if (original.reversesSequence != null) {
            throw ReversalRefusedException(ReversalRefusedException.REVERSAL_OF_REVERSAL, entrySequence)
        }
        reversalOf(c, entrySequence)?.let {
            throw ReversalRefusedException(ReversalRefusedException.ALREADY_REVERSED, entrySequence, it)
        }
        ShareHoldings.requireNonNegativeWithout(c, original, replacement)
        // OWNER-INPUT: C17 the reversal carries the original's effective date and source sequence.
        val request = EntryRequest(key, original.effectiveDate, original.sourceSeq, Posting.reversalOf(original.lines))
        return insert(c, request, ReversalLink(entrySequence, reason))
    }

    private fun reversalOf(
        c: Connection,
        entrySequence: Long,
    ): Long? =
        c.prepareStatement("SELECT reversal_entry_id FROM ledger.reversal WHERE original_entry_id = ?").use { st ->
            st.setLong(1, entrySequence)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun loadEntry(
        c: Connection,
        entrySequence: Long,
    ): PostedEntry? = loadEntries(c, "WHERE e.entry_id = ?", entrySequence).singleOrNull()

    private fun findByKey(
        c: Connection,
        key: String,
    ): PostedEntry? =
        c.prepareStatement("SELECT entry_id FROM ledger.entry WHERE idempotency_key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) loadEntry(c, rs.getLong(1)) else null }
        }

    /** Entries matching [where] (which binds one long parameter, or none when [where] is empty), with their lines. */
    private fun loadEntries(
        c: Connection,
        where: String,
        param: Long,
    ): List<PostedEntry> =
        c.prepareStatement(
            "SELECT e.entry_id, e.idempotency_key, e.effective_date, e.source_seq, " +
                "l.account, l.unit, l.side, l.amount, rv.original_entry_id " +
                "FROM ledger.entry e JOIN ledger.line l ON l.entry_id = e.entry_id " +
                "LEFT JOIN ledger.reversal rv ON rv.reversal_entry_id = e.entry_id " +
                "$where ORDER BY e.entry_id, l.line_no",
        ).use { st ->
            if (where.isNotEmpty()) st.setLong(1, param)
            st.executeQuery().use { rs ->
                val entries = mutableListOf<PostedEntry>()
                var lines = mutableListOf<LedgerLine>()
                while (rs.next()) {
                    val id = rs.getLong(1)
                    if (entries.lastOrNull()?.sequence != id) {
                        lines = mutableListOf()
                        val date = rs.getObject(3, LocalDate::class.java)
                        val reverses = rs.getObject(9, java.lang.Long::class.java)?.toLong()
                        entries += PostedEntry(id, rs.getString(2), date, rs.getLong(4), lines, reverses)
                    }
                    val side = Side.valueOf(rs.getString(7))
                    lines += LedgerLine(rs.getString(5), rs.getString(6), side, rs.getLong(8))
                }
                entries.map { it.copy(lines = it.lines.toList()) }
            }
        }

    private fun lock(c: Connection) {
        c.prepareStatement("SELECT pg_advisory_xact_lock(?)").use {
            it.setLong(1, APPEND_LOCK)
            it.execute()
        }
    }

    /** The link row that makes an entry a reversal of [originalSequence]. */
    private class ReversalLink(val originalSequence: Long, val reason: String)

    private fun insert(
        c: Connection,
        request: EntryRequest,
        link: ReversalLink? = null,
    ): PostedEntry {
        lock(c)
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
        link?.let {
            c.prepareStatement(
                "INSERT INTO ledger.reversal (reversal_entry_id, original_entry_id, reason) VALUES (?, ?, ?)",
            ).use { st ->
                st.setLong(1, sequence)
                st.setLong(2, it.originalSequence)
                st.setString(3, it.reason)
                st.execute()
            }
        }
        return PostedEntry(
            sequence,
            request.idempotencyKey,
            request.effectiveDate,
            request.sourceSeq,
            request.lines,
            link?.originalSequence,
        )
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
