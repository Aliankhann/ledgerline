package dev.ledgerline.ledger

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

internal const val ACTIVITY_COLUMNS =
    "activity_id, entry_id, recorded_at, taxpayer_id, account_id, account_type, activity_type, " +
        "effective_date, settlement_date, security_id, quantity, currency, amount, commission, " +
        "counter_currency, counter_amount, description, source_seq, idempotency_key"

/** Maps a row selected with [ACTIVITY_COLUMNS]. */
internal fun ResultSet.toActivityRecord(): ActivityRecord {
    val activity =
        Activity(
            idempotencyKey = getString(19),
            taxpayerId = getString(4),
            accountId = getString(5),
            accountType = AccountType.valueOf(getString(6)),
            activityType = ActivityType.valueOf(getString(7)),
            effectiveDate = getObject(8, LocalDate::class.java),
            settlementDate = getObject(9, LocalDate::class.java),
            securityId = getString(10),
            quantity = getLong(11).takeUnless { wasNull() },
            currency = Currency.valueOf(getString(12)),
            amountMinor = getLong(13),
            commissionMinor = getLong(14),
            counterCurrency = getString(15)?.let { Currency.valueOf(it) },
            counterAmountMinor = getLong(16).takeUnless { wasNull() },
            description = getString(17),
            sourceSeq = getLong(18),
        )
    return ActivityRecord(getString(1), getLong(2), activity, getObject(3, OffsetDateTime::class.java).toInstant())
}

/** Read-only queries on one read-only connection; obtained from [LedgerRepository.readOnly]. */
class LedgerReader internal constructor(private val c: Connection) {
    /** The stored payload hash of each of [keys] that exists. */
    fun payloadHashes(keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        return c.prepareStatement(
            "SELECT idempotency_key, payload_hash FROM ledger.activity WHERE idempotency_key = ANY (?)",
        ).use { st ->
            st.setArray(1, c.createArrayOf("text", keys.toSet().toTypedArray()))
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
        }
    }

    /** The stored activities of [taxpayers] that are not reversed (C19), ascending by entry. */
    fun activities(taxpayers: Collection<String>): List<ActivityRecord> {
        if (taxpayers.isEmpty()) return emptyList()
        return c.prepareStatement(
            "SELECT $ACTIVITY_COLUMNS FROM ledger.activity a WHERE a.taxpayer_id = ANY (?) " +
                "AND NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = a.entry_id) " +
                "ORDER BY a.entry_id",
        ).use { st ->
            st.setArray(1, c.createArrayOf("text", taxpayers.toSet().toTypedArray()))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toActivityRecord()) } }
        }
    }
}
