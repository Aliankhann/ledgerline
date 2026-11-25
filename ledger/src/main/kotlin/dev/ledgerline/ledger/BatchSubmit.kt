package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import dev.ledgerline.ledger.outbox.EventTooLargeException
import dev.ledgerline.ledger.outbox.OutboxWrites
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/** One row of [LedgerRepository.submitAll]: the outcome [LedgerRepository.submit] would give, or the refusal it would throw. */
sealed interface BatchResult {
    data class Done(val outcome: SubmitOutcome) : BatchResult

    data class Refused(val cause: RuntimeException) : BatchResult

    companion object {
        fun of(block: () -> SubmitOutcome): BatchResult =
            try {
                Done(block())
            } catch (e: ZeroAmountException) {
                Refused(e)
            } catch (e: EventTooLargeException) {
                Refused(e)
            }
    }
}

/**
 * Chunked submission: per chunk one transaction, one append-lock acquisition, one key lookup, and JDBC batch inserts
 * for the new rows (entry, lines, activity, outbox). Per-row outcomes equal those of row-by-row [LedgerRepository.submit],
 * including repeats of a key inside the chunk. A chunk that fails in the database or whose event is too large is rolled
 * back and redone row by row, so the failing row gets exactly its single-row result.
 */
internal class BatchSubmit(private val repo: LedgerRepository, private val maxEventBytes: Int) {
    private class Prepared(val activity: Activity, val request: EntryRequest, val hash: String)

    fun submitAll(
        c: Connection,
        activities: List<Activity>,
        recordedAt: Instant,
    ): List<BatchResult> = activities.chunked(CHUNK).flatMap { chunk(c, it, recordedAt) }

    private fun chunk(
        c: Connection,
        activities: List<Activity>,
        recordedAt: Instant,
    ): List<BatchResult> {
        val results = arrayOfNulls<BatchResult>(activities.size)
        val prepared = arrayOfNulls<Prepared>(activities.size)
        activities.forEachIndexed { i, a ->
            try {
                val request = EntryRequest(a.idempotencyKey, a.effectiveDate, a.sourceSeq, Posting.linesFor(a))
                Posting.requireBalanced(request.lines)
                prepared[i] = Prepared(a, request, PayloadHash.of(a))
            } catch (e: ZeroAmountException) {
                results[i] = BatchResult.Refused(e)
            }
        }
        val todo = prepared.indices.filter { prepared[it] != null }
        if (todo.isNotEmpty()) {
            val done =
                try {
                    repo.inTransaction(c) { write(c, todo.map { prepared[it]!! }, recordedAt) }
                } catch (e: SQLException) {
                    null
                } catch (e: EventTooLargeException) {
                    null
                } ?: todo.map { BatchResult.of { repo.submitOn(c, prepared[it]!!.activity, recordedAt) } }
            todo.forEachIndexed { k, i -> results[i] = done[k] }
        }
        return results.map { it!! }
    }

    private fun write(
        c: Connection,
        rows: List<Prepared>,
        recordedAt: Instant,
    ): List<BatchResult> {
        repo.lockAppends(c)
        val stored = storedHashes(c, rows.map { it.activity.idempotencyKey })
        // Decide each row in order, as row-by-row submission would: a key first seen in this chunk is new.
        val firstInChunk = HashMap<String, Int>()
        val outcomes = arrayOfNulls<BatchResult>(rows.size)
        val mismatch = BatchResult.Done(SubmitOutcome.Mismatch)
        val fresh = mutableListOf<Int>()
        rows.forEachIndexed { i, r ->
            val key = r.activity.idempotencyKey
            val known = stored[key]
            val earlier = firstInChunk[key]
            when {
                known != null -> {
                    val same = known.hash == r.hash
                    val dup = SubmitOutcome.Duplicate(known.activityId, known.entryId)
                    outcomes[i] = if (same) BatchResult.Done(dup) else mismatch
                }
                // Same hash: filled in below once the first row has its ids.
                earlier != null -> if (rows[earlier].hash != r.hash) outcomes[i] = mismatch
                else -> {
                    firstInChunk[key] = i
                    fresh += i
                }
            }
        }
        val posted = insertNew(c, fresh.map { rows[it] }, recordedAt)
        fresh.forEachIndexed { k, i -> outcomes[i] = BatchResult.Done(posted[k]) }
        rows.forEachIndexed { i, r ->
            if (outcomes[i] == null) {
                val first = outcomes[firstInChunk.getValue(r.activity.idempotencyKey)] as BatchResult.Done
                val p = first.outcome as SubmitOutcome.Posted
                outcomes[i] = BatchResult.Done(SubmitOutcome.Duplicate(p.activityId, p.entryId))
            }
        }
        return outcomes.map { it!! }
    }

    private class Stored(val activityId: String, val entryId: Long, val hash: String)

    private fun storedHashes(
        c: Connection,
        keys: List<String>,
    ): Map<String, Stored> =
        c.prepareStatement(
            "SELECT idempotency_key, activity_id, entry_id, payload_hash FROM ledger.activity " +
                "WHERE idempotency_key = ANY (?)",
        ).use { st ->
            st.setArray(1, c.createArrayOf("text", keys.toTypedArray()))
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(
                        rs.getString(1),
                        Stored(rs.getString(2), rs.getLong(3), rs.getString(4)),
                    )
                }
            }
        }

    /** Inserts entries (one multi-row statement, so ids follow row order), then lines, activities and outbox in batches. */
    private fun insertNew(
        c: Connection,
        rows: List<Prepared>,
        recordedAt: Instant,
    ): List<SubmitOutcome.Posted> {
        if (rows.isEmpty()) return emptyList()
        val sql =
            "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) VALUES " +
                rows.joinToString(", ") { "(?, ?, ?)" } + " RETURNING idempotency_key, entry_id"
        val ids = HashMap<String, Long>()
        c.prepareStatement(sql).use { st ->
            var p = 1
            rows.forEach {
                st.setString(p++, it.request.idempotencyKey)
                st.setObject(p++, it.request.effectiveDate)
                st.setLong(p++, it.request.sourceSeq)
            }
            st.executeQuery().use { rs -> while (rs.next()) ids[rs.getString(1)] = rs.getLong(2) }
        }
        c.prepareStatement(
            "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { st ->
            rows.forEach { r ->
                r.request.lines.forEachIndexed { i, l ->
                    st.setLong(1, ids.getValue(r.request.idempotencyKey))
                    st.setInt(2, i + 1)
                    st.setString(3, l.account)
                    st.setString(4, l.unit)
                    st.setString(5, l.side.name)
                    st.setLong(6, l.amount)
                    st.addBatch()
                }
            }
            st.executeBatch()
        }
        val activityIds = rows.map { UUID.randomUUID().toString() }
        c.prepareStatement(ActivityRows.INSERT).use { st ->
            rows.forEachIndexed { i, r ->
                ActivityRows.bind(
                    st,
                    activityIds[i],
                    ids.getValue(r.request.idempotencyKey),
                    r.hash,
                    recordedAt,
                    r.activity,
                )
                st.addBatch()
            }
            st.executeBatch()
        }
        val entries =
            rows.map {
                PostedEntry(
                    ids.getValue(it.request.idempotencyKey),
                    it.request.idempotencyKey,
                    it.request.effectiveDate,
                    it.request.sourceSeq,
                    it.request.lines,
                    null,
                )
            }
        OutboxWrites.activityPostedAll(c, entries, activityIds, rows.map { it.activity }, maxEventBytes)
        return entries.mapIndexed { i, e -> SubmitOutcome.Posted(activityIds[i], e.sequence) }
    }

    companion object {
        const val CHUNK = 500
    }
}
