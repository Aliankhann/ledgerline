package dev.ledgerline.ledger.outbox

import dev.ledgerline.core.Activity
import dev.ledgerline.ledger.PostedEntry
import java.sql.Connection

/**
 * Appends outbox rows inside the caller's ledger transaction, which holds the append lock, so event ids increase in
 * commit order (V7). Never commits.
 */
internal object OutboxWrites {
    /** ACTIVITY_POSTED for [entry], posted from [activity]; [activityId] is its `ledger.activity` row, if any. */
    fun activityPosted(
        c: Connection,
        entry: PostedEntry,
        activityId: String?,
        activity: Activity,
    ) {
        insert(
            c,
            entry.sequence,
            activityId,
            OutboxEventType.ACTIVITY_POSTED,
            activity.taxpayerId,
            OutboxPayloads.activityPosted(entry.sequence, activityId, activity),
        )
    }

    /**
     * OWNER-INPUT: C25. ENTRY_REVERSED for [reversal] of entry [originalSequence], keyed by the original's taxpayer.
     * An original that was never published (the L-03 direct `post`/`append` APIs write no outbox row and carry no
     * taxpayer) has nothing to retract, so its reversal is not published either.
     */
    fun entryReversed(
        c: Connection,
        reversal: PostedEntry,
        originalSequence: Long,
        reason: String,
    ) {
        val taxpayer = taxpayerOf(c, originalSequence) ?: return
        insert(
            c,
            reversal.sequence,
            null,
            OutboxEventType.ENTRY_REVERSED,
            taxpayer,
            OutboxPayloads.entryReversed(
                reversal.sequence,
                reversal.idempotencyKey,
                taxpayer,
                reversal.effectiveDate,
                reversal.sourceSeq,
                originalSequence,
                reason,
            ),
        )
    }

    /** The taxpayer of entry [entrySequence]'s event; rows from before V7 take it from their activity row. */
    private fun taxpayerOf(
        c: Connection,
        entrySequence: Long,
    ): String? =
        c.prepareStatement(
            "SELECT COALESCE(o.taxpayer_id, a.taxpayer_id) FROM ledger.outbox o " +
                "LEFT JOIN ledger.activity a ON a.activity_id = o.activity_id WHERE o.entry_id = ?",
        ).use { st ->
            st.setLong(1, entrySequence)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun insert(
        c: Connection,
        entryId: Long,
        activityId: String?,
        type: OutboxEventType,
        taxpayerId: String,
        payload: String,
    ) {
        c.prepareStatement(
            "INSERT INTO ledger.outbox (entry_id, activity_id, event_type, taxpayer_id, payload) " +
                "VALUES (?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, entryId)
            st.setString(2, activityId)
            st.setString(3, type.name)
            st.setString(4, taxpayerId)
            st.setString(5, payload)
            st.executeUpdate()
        }
    }
}
