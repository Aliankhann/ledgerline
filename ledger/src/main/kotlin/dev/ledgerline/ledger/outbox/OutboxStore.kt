package dev.ledgerline.ledger.outbox

import dev.ledgerline.ledger.LedgerRepository
import org.apache.kafka.clients.producer.RecordMetadata
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Reads `ledger.outbox` and appends to `ledger.outbox_delivery` and `ledger.outbox_parked`. Works as the app role
 * (SELECT and INSERT only). OWNER-INPUT: C5: an event is pending while it has no delivery row; nothing is ever updated.
 */
class OutboxStore(private val dataSource: DataSource) {
    /** Every outbox row, ascending [OutboxEvent.eventId]. */
    fun events(): List<OutboxEvent> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT o.outbox_id, COALESCE(o.event_type, 'ACTIVITY_POSTED'), " +
                    "COALESCE(o.taxpayer_id, a.taxpayer_id), o.entry_id, e.idempotency_key " +
                    "FROM ledger.outbox o JOIN ledger.entry e ON e.entry_id = o.entry_id " +
                    "LEFT JOIN ledger.activity a ON a.activity_id = o.activity_id ORDER BY o.outbox_id",
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OutboxEvent(
                                    rs.getLong(1),
                                    OutboxEventType.valueOf(rs.getString(2)),
                                    rs.getString(3),
                                    rs.getLong(4),
                                    rs.getString(5),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /** Event ids with no delivery row, ascending: an anti-join on the two primary keys. */
    fun pendingEventIds(): List<Long> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT o.outbox_id FROM ledger.outbox o $PENDING ORDER BY o.outbox_id").use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    /** Every parked event (OWNER-INPUT: C27), ascending event id. Parked events are also in [pendingEventIds]. */
    fun parkedEvents(): List<ParkedEvent> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT outbox_id, taxpayer_id, reason_code, detail FROM ledger.outbox_parked ORDER BY outbox_id",
            ).use { st ->
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ParkedEvent(
                                    rs.getLong(1),
                                    rs.getString(2),
                                    ParkReason.valueOf(rs.getString(3)),
                                    rs.getString(4),
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * Up to [max] sendable pending rows with id above [afterId], ascending. One query (legacy rows aside). Leaves out
     * parked rows and every row of a taxpayer that has a parked row before it (OWNER-INPUT: C27), so a parked event
     * holds back only its own taxpayer and is never retried. A row whose event cannot be built is returned as an
     * [UnreadableRow] instead of throwing (B-017); database errors still throw.
     */
    internal fun pendingBatch(
        afterId: Long,
        max: Int,
    ): List<PendingRow> {
        val rows =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT o.outbox_id, o.entry_id, o.activity_id, o.taxpayer_id, o.payload, " +
                        "COALESCE(o.taxpayer_id, a.taxpayer_id) FROM ledger.outbox o " +
                        "LEFT JOIN ledger.activity a ON o.taxpayer_id IS NULL AND a.activity_id = o.activity_id " +
                        "$PENDING AND o.outbox_id > ? " +
                        "AND NOT EXISTS (SELECT 1 FROM ledger.outbox_parked p WHERE p.outbox_id = o.outbox_id) " +
                        "AND NOT EXISTS (SELECT 1 FROM ledger.outbox_parked p " +
                        "WHERE p.taxpayer_id = COALESCE(o.taxpayer_id, a.taxpayer_id) AND p.outbox_id < o.outbox_id) " +
                        "ORDER BY o.outbox_id LIMIT ?",
                ).use { st ->
                    st.setLong(1, afterId)
                    st.setInt(2, max)
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(Row.of(rs)) } }
                }
            }
        return rows.map { r ->
            if (r.payload != null && r.taxpayerId != null) {
                PendingEvent(
                    r.eventId,
                    r.taxpayerId,
                    r.payload,
                )
            } else {
                legacy(r)
            }
        }
    }

    /**
     * In one transaction: one delivery row per acknowledged event and one parked row per event in [parked]. A row
     * already recorded (another relay won the race) is left as is.
     */
    internal fun record(
        topic: String,
        acked: List<Pair<PendingEvent, RecordMetadata>>,
        parked: List<ParkedEvent>,
    ) {
        if (acked.isEmpty() && parked.isEmpty()) return
        dataSource.connection.use { c ->
            val previous = c.autoCommit
            c.autoCommit = false
            try {
                if (acked.isNotEmpty()) insertDeliveries(c, topic, acked)
                if (parked.isNotEmpty()) insertParked(c, parked)
                c.commit()
            } catch (e: Throwable) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = previous
            }
        }
    }

    private fun insertDeliveries(
        c: Connection,
        topic: String,
        acked: List<Pair<PendingEvent, RecordMetadata>>,
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.outbox_delivery (outbox_id, topic, kafka_partition, kafka_offset) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (outbox_id) DO NOTHING",
        ).use { st ->
            acked.forEach { (event, meta) ->
                st.setLong(1, event.eventId)
                st.setString(2, topic)
                st.setInt(3, meta.partition())
                st.setLong(4, maxOf(meta.offset(), 0L))
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun insertParked(
        c: Connection,
        parked: List<ParkedEvent>,
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.outbox_parked (outbox_id, taxpayer_id, reason_code, detail) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (outbox_id) DO NOTHING",
        ).use { st ->
            parked.forEach { p ->
                st.setLong(1, p.eventId)
                st.setString(2, p.taxpayerId)
                st.setString(3, p.reason.name)
                st.setString(4, p.detail)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /** A row written before V7 (no event columns): an ACTIVITY_POSTED built from its activity row, if that exists. */
    private fun legacy(r: Row): PendingRow {
        val key =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT idempotency_key FROM ledger.activity WHERE activity_id = ?").use { st ->
                    st.setString(1, r.activityId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        val record =
            try {
                key?.let { LedgerRepository(dataSource).findActivity(it) }
            } catch (e: IllegalArgumentException) {
                // A stored value the domain types refuse (for example an unknown enum name).
                return UnreadableRow(r.eventId, r.keyTaxpayerId, ParkReason.LEGACY_UNREADABLE, "activity row: $e")
            } ?: return UnreadableRow(
                r.eventId,
                r.keyTaxpayerId,
                ParkReason.LEGACY_UNREADABLE,
                "outbox row ${r.eventId} has no event payload and no activity row",
            )
        return PendingEvent(
            r.eventId,
            record.activity.taxpayerId,
            OutboxPayloads.activityPosted(r.entryId, record.activityId, record.activity),
        )
    }

    private class Row(
        val eventId: Long,
        val entryId: Long,
        val activityId: String?,
        val taxpayerId: String?,
        val payload: String?,
        /** The message key: the row's own taxpayer, or a legacy row's activity taxpayer; null if neither exists. */
        val keyTaxpayerId: String?,
    ) {
        companion object {
            fun of(rs: ResultSet) =
                Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }
    }

    private companion object {
        const val PENDING =
            "WHERE NOT EXISTS (SELECT 1 FROM ledger.outbox_delivery d WHERE d.outbox_id = o.outbox_id)"
    }
}

/** A pending outbox row as the relay reads it. */
internal sealed interface PendingRow {
    val eventId: Long
}

/** An event to send: key [taxpayerId], stored body [payload] (without `eventId`; not yet checked to be JSON). */
internal class PendingEvent(override val eventId: Long, val taxpayerId: String, val payload: String) : PendingRow

/** A row whose event cannot be built; the relay parks it ([taxpayerId] null if the key is unknown). */
internal class UnreadableRow(
    override val eventId: Long,
    val taxpayerId: String?,
    val reason: ParkReason,
    val detail: String,
) : PendingRow
