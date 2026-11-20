package dev.ledgerline.testsupport

import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.errors.TimeoutException
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps a real producer. The sends numbered in [failSends] (1-based, counting every `send` call) never reach the
 * broker: they return a future that fails with a Kafka [TimeoutException], as a record does when its delivery timeout
 * expires during a broker failure. Every other send goes to the real producer. The process does not crash, so this
 * models a broker failure mid-drain (L-05 phase B, ADR 0005 "Per-taxpayer order"), not a fault point.
 */
class AckFailingProducer(
    private val delegate: Producer<String, ByteArray>,
    private val failSends: Set<Int>,
) : Producer<String, ByteArray> by delegate {
    private val sends = AtomicInteger()

    /** The event ids (header `eventId`) of the sends that were made to fail, in order. */
    val failedEventIds: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun send(record: ProducerRecord<String, ByteArray>): Future<RecordMetadata> = send(record, null)

    override fun send(
        record: ProducerRecord<String, ByteArray>,
        callback: Callback?,
    ): Future<RecordMetadata> {
        if (sends.incrementAndGet() !in failSends) return delegate.send(record, callback)
        failedEventIds += record.headers().lastHeader("eventId")?.value()?.toString(Charsets.UTF_8) ?: "?"
        val error = TimeoutException("injected: delivery timeout expired before the broker acknowledged")
        callback?.onCompletion(null, error)
        return CompletableFuture<RecordMetadata>().apply { completeExceptionally(error) }
    }
}

/**
 * Per message key, the event ids in the order a consumer that deduplicates on `eventId` (L-12's inbox) first sees
 * them: messages in offset order, later copies of an id already seen dropped. A key maps to one partition, so offset
 * order is the key's topic order.
 */
fun List<TopicMessage>.firstSeenOrderByKey(): Map<String?, List<String>> =
    sortedWith(compareBy({ it.partition }, { it.offset }))
        .groupBy { it.key }
        .mapValues { (_, msgs) -> msgs.mapNotNull { it.eventId }.distinct() }

/** Copies on the topic per event id. */
fun List<TopicMessage>.copiesPerEventId(): Map<String, Int> = mapNotNull { it.eventId }.groupingBy { it }.eachCount()

/**
 * As the table owner, in ONE transaction (the V3/V7 sealing triggers need the entry, its lines and its outbox row in
 * the same transaction): a balanced 1-cent CAD entry keyed [key] for [taxpayer], and an ACTIVITY_POSTED outbox row
 * whose stored body is [payload], verbatim. Bypasses `LedgerRepository`, so the body can be anything the V7 CHECK
 * (`payload LIKE '{%}'`) accepts. Returns the new event id. Use only while nothing else is appending.
 */
fun LedgerDb.appendRawOutboxEvent(
    key: String,
    taxpayer: String,
    payload: String,
): Long =
    inOwnerTransaction { c ->
        val entryId =
            c.prepareStatement(
                "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) " +
                    "VALUES (?, DATE '2025-10-01', 0) RETURNING entry_id",
            ).use { st ->
                st.setString(1, key)
                st.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        listOf(1 to "DEBIT", 2 to "CREDIT").forEach { (n, side) ->
            c.prepareStatement(
                "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) " +
                    "VALUES (?, ?, ?, 'CAD', ?, 1)",
            ).use { st ->
                st.setLong(1, entryId)
                st.setInt(2, n)
                st.setString(3, if (n == 1) "$taxpayer-NR1" else "SYS:EXTERNAL")
                st.setString(4, side)
                st.executeUpdate()
            }
        }
        c.prepareStatement(
            "INSERT INTO ledger.outbox (entry_id, activity_id, event_type, taxpayer_id, payload) " +
                "VALUES (?, NULL, 'ACTIVITY_POSTED', ?, ?) RETURNING outbox_id",
        ).use { st ->
            st.setLong(1, entryId)
            st.setString(2, taxpayer)
            st.setString(3, payload)
            st.executeQuery().use {
                it.next()
                it.getLong(1)
            }
        }
    }

/**
 * As the table owner, in ONE transaction: [count] balanced 1 CAD deposits keyed `TP-000t-2025-B<n>` spread round-robin
 * over [taxpayers] taxpayers, each with an ACTIVITY_POSTED outbox row whose body is about [bodyBytes] bytes of valid
 * JSON (the size of a real ACTIVITY_POSTED body is roughly 700 bytes). Seeds a large pending outbox in seconds
 * instead of [count] service postings; the relay reads only the outbox, so it cannot tell the difference.
 */
fun LedgerDb.bulkAppendOutboxEvents(
    count: Int,
    taxpayers: Int,
    bodyBytes: Int = 700,
) {
    val tp = "'TP-' || lpad((1 + (g % $taxpayers))::text, 4, '0')"
    val pad = maxOf(0, bodyBytes - 200)
    inOwnerTransaction { c ->
        c.createStatement().use { st ->
            st.execute(
                "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) " +
                    "SELECT $tp || '-2025-B' || lpad(g::text, 6, '0'), DATE '2025-03-01', g " +
                    "FROM generate_series(1, $count) g ORDER BY g",
            )
            st.execute(
                "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) " +
                    "SELECT e.entry_id, s.n, CASE s.n WHEN 1 THEN substr(e.idempotency_key, 1, 7) || '-NR1' " +
                    "ELSE 'SYS:EXTERNAL' END, 'CAD', CASE s.n WHEN 1 THEN 'DEBIT' ELSE 'CREDIT' END, 100 " +
                    "FROM ledger.entry e CROSS JOIN (VALUES (1), (2)) AS s(n) " +
                    "WHERE e.idempotency_key LIKE 'TP-____-2025-B%' ORDER BY e.entry_id, s.n",
            )
            st.execute(
                "INSERT INTO ledger.outbox (entry_id, activity_id, event_type, taxpayer_id, payload) " +
                    "SELECT e.entry_id, NULL, 'ACTIVITY_POSTED', substr(e.idempotency_key, 1, 7), " +
                    "'{\"eventType\":\"ACTIVITY_POSTED\",\"taxpayerId\":\"' || substr(e.idempotency_key, 1, 7) || " +
                    "'\",\"idempotencyKey\":\"' || e.idempotency_key || '\",\"entryId\":' || e.entry_id || " +
                    "',\"sequence\":' || e.entry_id || ',\"pad\":\"' || repeat('x', $pad) || '\"}' " +
                    "FROM ledger.entry e WHERE e.idempotency_key LIKE 'TP-____-2025-B%' ORDER BY e.entry_id",
            )
        }
    }
}

private fun <T> LedgerDb.inOwnerTransaction(block: (Connection) -> T): T =
    owner.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }
