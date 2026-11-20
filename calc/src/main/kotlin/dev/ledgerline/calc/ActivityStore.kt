package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import java.sql.ResultSet
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Read-only view of a taxpayer's stored activities in `ledger.activity`, without the reversed ones (C19).
 * `calc.pool` remains the consumer's own state; replaying these rows gives pools with step history, which
 * reconciliation can compare with `calc.pool`.
 */
class ActivityStore(private val dataSource: DataSource) {
    fun effectiveActivities(taxpayer: String): List<PostedActivity> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT a.activity_id, a.entry_id, a.idempotency_key, a.taxpayer_id, a.account_id, a.account_type, " +
                    "a.activity_type, a.effective_date, a.settlement_date, a.security_id, a.quantity, a.currency, " +
                    "a.amount, a.commission, a.counter_currency, a.counter_amount, a.description, a.source_seq " +
                    "FROM ledger.activity a WHERE a.taxpayer_id = ? " +
                    "AND NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = a.entry_id) " +
                    "ORDER BY a.entry_id",
            ).use {
                it.setString(1, taxpayer)
                it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.posted()) } }
            }
        }
}

internal fun ResultSet.posted(): PostedActivity =
    PostedActivity(
        getString("activity_id"),
        getLong("entry_id"),
        Activity(
            idempotencyKey = getString("idempotency_key"),
            taxpayerId = getString("taxpayer_id"),
            accountId = getString("account_id"),
            accountType = AccountType.valueOf(getString("account_type")),
            activityType = ActivityType.valueOf(getString("activity_type")),
            effectiveDate = getObject("effective_date", LocalDate::class.java),
            settlementDate = getObject("settlement_date", LocalDate::class.java),
            securityId = getString("security_id"),
            quantity = getLong("quantity").takeUnless { wasNull() },
            currency = Currency.valueOf(getString("currency")),
            amountMinor = getLong("amount"),
            commissionMinor = getLong("commission"),
            counterCurrency = getString("counter_currency")?.let(Currency::valueOf),
            counterAmountMinor = getLong("counter_amount").takeUnless { wasNull() },
            description = getString("description"),
            sourceSeq = getLong("source_seq"),
        ),
    )
