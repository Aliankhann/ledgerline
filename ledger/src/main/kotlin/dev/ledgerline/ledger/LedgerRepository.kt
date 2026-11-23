package dev.ledgerline.ledger

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ledger.outbox.OutboxLimits
import dev.ledgerline.ledger.outbox.OutboxWrites
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * The append-only ledger store. Works with connections that run as role `ledgerline_app` (SELECT and INSERT only).
 * Each append is one transaction; appends are serialised by a transaction-scoped advisory lock, so
 * [PostedEntry.sequence] increases in commit order (draft ADR 0005). An append whose outbox event would exceed
 * [maxEventBytes] throws [dev.ledgerline.ledger.outbox.EventTooLargeException] and writes nothing (OWNER-INPUT: C27).
 */
class LedgerRepository(
    private val dataSource: DataSource,
    private val maxEventBytes: Int = OutboxLimits.DEFAULT_MAX_EVENT_BYTES,
) {
    init {
        OutboxLimits.requireValid(maxEventBytes)
    }

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

    /**
     * The idempotent funnel (ADR 0001, story L-02). In one transaction: a new key posts the entry, its activity row
     * (payload hash, `recordedAt`) and one outbox row; a known key with the same [PayloadHash] changes nothing and
     * returns the original ids; a known key with another hash changes nothing and is a [SubmitOutcome.Mismatch].
     *
     * Lines are computed before anything is written, so an activity the ledger refuses (for example
     * [ZeroAmountException]) throws without consuming its key (OWNER-INPUT: C21). Concurrent submissions of one key
     * are serialised by the append lock; the unique constraints on `ledger.entry` and `ledger.activity` are the
     * backstop, and a 23505 is answered by reading the winner's row.
     */
    fun submit(
        activity: Activity,
        recordedAt: Instant,
    ): SubmitOutcome {
        val request =
            EntryRequest(
                activity.idempotencyKey,
                activity.effectiveDate,
                activity.sourceSeq,
                Posting.linesFor(activity),
            )
        Posting.requireBalanced(request.lines)
        val hash = PayloadHash.of(activity)

        fun attempt() =
            dataSource.connection.use { c ->
                inTransaction(c) { submitOnce(c, activity, request, hash, recordedAt) }
            }
        return try {
            attempt()
        } catch (e: SQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            attempt()
        }
    }

    /** The accepted activity for [idempotencyKey] as first stored, or null. */
    fun findActivity(idempotencyKey: String): ActivityRecord? =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT activity_id, entry_id, recorded_at, taxpayer_id, account_id, account_type, activity_type, " +
                    "effective_date, settlement_date, security_id, quantity, currency, amount, commission, " +
                    "counter_currency, counter_amount, description, source_seq " +
                    "FROM ledger.activity WHERE idempotency_key = ?",
            ).use { st ->
                st.setString(1, idempotencyKey)
                st.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val activity =
                        Activity(
                            idempotencyKey = idempotencyKey,
                            taxpayerId = rs.getString(4),
                            accountId = rs.getString(5),
                            accountType = AccountType.valueOf(rs.getString(6)),
                            activityType = ActivityType.valueOf(rs.getString(7)),
                            effectiveDate = rs.getObject(8, LocalDate::class.java),
                            settlementDate = rs.getObject(9, LocalDate::class.java),
                            securityId = rs.getString(10),
                            quantity = rs.getLong(11).takeUnless { rs.wasNull() },
                            currency = Currency.valueOf(rs.getString(12)),
                            amountMinor = rs.getLong(13),
                            commissionMinor = rs.getLong(14),
                            counterCurrency = rs.getString(15)?.let { Currency.valueOf(it) },
                            counterAmountMinor = rs.getLong(16).takeUnless { rs.wasNull() },
                            description = rs.getString(17),
                            sourceSeq = rs.getLong(18),
                        )
                    ActivityRecord(
                        rs.getString(1),
                        rs.getLong(2),
                        activity,
                        rs.getObject(3, OffsetDateTime::class.java).toInstant(),
                    )
                }
            }
        }

    /** Every entry with its lines, in ascending sequence. */
    fun entries(): List<PostedEntry> = dataSource.connection.use { c -> loadEntries(c, "", 0L) }

    /** Every entry in the order calculation and replay use: see [CanonicalOrder]. */
    fun entriesInCanonicalOrder(): List<PostedEntry> = CanonicalOrder.sort(entries())

    /**
     * OWNER-INPUT: C19. The entries replay and calculation apply: canonical order without any reversed entry or its
     * reversal (they net to zero, invariant 4). The ledger still stores and lists both rows; see [CanonicalOrder.forReplay].
     */
    fun entriesForReplay(): List<PostedEntry> = CanonicalOrder.forReplay(entries())

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
                lockAppends(c)
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
                lockAppends(c)
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
                    val posted = insert(c, request)
                    // B-029: record the pair so the consumer judges the correction as one unit.
                    c.prepareStatement(
                        "INSERT INTO ledger.correction (reversal_entry_id, replacement_entry_id) VALUES (?, ?)",
                    ).use {
                        it.setLong(1, reversal.sequence)
                        it.setLong(2, posted.sequence)
                        it.executeUpdate()
                    }
                    // OWNER-INPUT: C25: the replacement is published like any posted activity.
                    OutboxWrites.activityPosted(c, posted, null, replacement, maxEventBytes)
                    CorrectionResult(reversal, posted)
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
        val reversal = insert(c, request, ReversalLink(entrySequence, reason))
        // OWNER-INPUT: C25: a reversal writes its ENTRY_REVERSED outbox row in the same transaction.
        OutboxWrites.entryReversed(c, reversal, entrySequence, reason, maxEventBytes)
        return reversal
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

    private fun submitOnce(
        c: Connection,
        activity: Activity,
        request: EntryRequest,
        hash: String,
        recordedAt: Instant,
    ): SubmitOutcome {
        lockAppends(c)
        c.prepareStatement("SELECT activity_id, entry_id, payload_hash FROM ledger.activity WHERE idempotency_key = ?")
            .use { st ->
                st.setString(1, activity.idempotencyKey)
                st.executeQuery().use { rs ->
                    if (rs.next()) {
                        return if (rs.getString(3) == hash) {
                            SubmitOutcome.Duplicate(rs.getString(1), rs.getLong(2))
                        } else {
                            SubmitOutcome.Mismatch
                        }
                    }
                }
            }
        val entry = insertEntry(c, request)
        val activityId = UUID.randomUUID().toString()
        insertActivity(c, activityId, entry.sequence, hash, recordedAt, activity)
        OutboxWrites.activityPosted(c, entry, activityId, activity, maxEventBytes)
        return SubmitOutcome.Posted(activityId, entry.sequence)
    }

    private fun insertActivity(
        c: Connection,
        activityId: String,
        entryId: Long,
        hash: String,
        recordedAt: Instant,
        a: Activity,
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.activity (activity_id, entry_id, idempotency_key, payload_hash, recorded_at, " +
                "taxpayer_id, account_id, account_type, activity_type, effective_date, settlement_date, " +
                "security_id, quantity, currency, amount, commission, counter_currency, counter_amount, " +
                "description, source_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setString(1, activityId)
            st.setLong(2, entryId)
            st.setString(3, a.idempotencyKey)
            st.setString(4, hash)
            st.setObject(5, OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC))
            st.setString(6, a.taxpayerId)
            st.setString(7, a.accountId)
            st.setString(8, a.accountType.name)
            st.setString(9, a.activityType.name)
            st.setObject(10, a.effectiveDate)
            st.setObject(11, a.settlementDate)
            st.setString(12, a.securityId)
            st.setObject(13, a.quantity)
            st.setString(14, a.currency.name)
            st.setLong(15, a.amountMinor)
            st.setLong(16, a.commissionMinor)
            st.setString(17, a.counterCurrency?.name)
            st.setObject(18, a.counterAmountMinor)
            st.setString(19, a.description)
            st.setLong(20, a.sourceSeq)
            st.executeUpdate()
        }
    }

    private fun lockAppends(c: Connection) {
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
        lockAppends(c)
        return insertEntry(c, request, link)
    }

    private fun insertEntry(
        c: Connection,
        request: EntryRequest,
        link: ReversalLink? = null,
    ): PostedEntry {
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
        const val UNIQUE_VIOLATION = "23505"
    }
}
