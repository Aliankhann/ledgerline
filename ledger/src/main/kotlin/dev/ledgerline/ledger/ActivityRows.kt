package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The `ledger.activity` insert, shared by single and batched submission. */
internal object ActivityRows {
    const val INSERT =
        "INSERT INTO ledger.activity (activity_id, entry_id, idempotency_key, payload_hash, recorded_at, " +
            "taxpayer_id, account_id, account_type, activity_type, effective_date, settlement_date, " +
            "security_id, quantity, currency, amount, commission, counter_currency, counter_amount, " +
            "description, source_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    fun bind(
        st: PreparedStatement,
        activityId: String,
        entryId: Long,
        hash: String,
        recordedAt: Instant,
        a: Activity,
    ) {
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
    }
}
