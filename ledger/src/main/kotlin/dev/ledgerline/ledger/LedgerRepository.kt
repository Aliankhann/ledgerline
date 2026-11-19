package dev.ledgerline.ledger

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
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
        c.prepareStatement("INSERT INTO ledger.outbox (entry_id, activity_id) VALUES (?, ?)").use { st ->
            st.setLong(1, entry.sequence)
            st.setString(2, activityId)
            st.executeUpdate()
        }
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

    private fun insert(
        c: Connection,
        request: EntryRequest,
    ): PostedEntry {
        lockAppends(c)
        return insertEntry(c, request)
    }

    private fun insertEntry(
        c: Connection,
        request: EntryRequest,
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
        const val UNIQUE_VIOLATION = "23505"
    }
}
