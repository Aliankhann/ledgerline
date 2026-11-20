package dev.ledgerline.ledger.outbox

import dev.ledgerline.ledger.LedgerRepository
import org.apache.kafka.clients.producer.RecordMetadata
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Reads `ledger.outbox` and appends to `ledger.outbox_delivery`. Works as the app role (SELECT and INSERT only).
 * OWNER-INPUT: C5: an event is pending while it has no delivery row; nothing is ever updated.
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

    /** Up to [max] pending events with id above [afterId], ascending, ready to send. One query (legacy rows aside). */
    internal fun pendingBatch(
        afterId: Long,
        max: Int,
    ): List<PendingEvent> {
        val rows =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT o.outbox_id, o.entry_id, o.activity_id, o.taxpayer_id, o.payload FROM ledger.outbox o " +
                        "$PENDING AND o.outbox_id > ? ORDER BY o.outbox_id LIMIT ?",
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

    /** Appends one delivery row per acknowledged event, in one transaction. A row already recorded is left as is. */
    internal fun recordDeliveries(
        topic: String,
        acked: List<Pair<PendingEvent, RecordMetadata>>,
    ) {
        if (acked.isEmpty()) return
        dataSource.connection.use { c ->
            val previous = c.autoCommit
            c.autoCommit = false
            try {
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
                c.commit()
            } catch (e: Throwable) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = previous
            }
        }
    }

    /** A row written before V7 (no event columns): always an ACTIVITY_POSTED with an activity row. */
    private fun legacy(r: Row): PendingEvent {
        val key =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT idempotency_key FROM ledger.activity WHERE activity_id = ?").use { st ->
                    st.setString(1, r.activityId)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }
        val record =
            checkNotNull(key?.let { LedgerRepository(dataSource).findActivity(it) }) {
                "outbox row ${r.eventId} has no event payload and no activity row"
            }
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
    ) {
        companion object {
            fun of(rs: ResultSet) = Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5))
        }
    }

    private companion object {
        const val PENDING =
            "WHERE NOT EXISTS (SELECT 1 FROM ledger.outbox_delivery d WHERE d.outbox_id = o.outbox_id)"
    }
}

/** An event ready to send: key [taxpayerId], stored body [payload] (without `eventId`). */
internal class PendingEvent(val eventId: Long, val taxpayerId: String, val payload: String)
