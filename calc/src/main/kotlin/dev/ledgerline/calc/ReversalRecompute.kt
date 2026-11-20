package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate

/**
 * Applies an ENTRY_REVERSED event to the live pool (bug B-024). The pool of the reversed activity's (taxpayer,
 * security) is rebuilt by [AcbEngine.replay] over the stored activities that are not reversed, so it equals what a C19
 * replay gives: as if the reversed activity and its reversal had never happened.
 *
 * Runs on the caller's connection, inside the consumer's one transaction. Only activities of entries before the
 * reversal's own entry are read: events of one taxpayer are ordered, so those were already consumed, and later ones
 * are applied by their own events. A rejection from the replay is thrown, never clipped.
 */
internal class ReversalRecompute(private val engine: AcbEngine) {
    fun apply(
        c: Connection,
        reversedEntryId: Long,
        reversalEntryId: Long,
    ) {
        val (taxpayer, security) = reversedKey(c, reversedEntryId) ?: return
        val history = effectiveHistory(c, taxpayer, security, reversalEntryId)
        val replay = engine.replay(history)
        check(replay.rejections.isEmpty()) {
            "reversal of entry $reversedEntryId makes the replay of $taxpayer/$security reject: ${replay.rejections}"
        }
        val pool = replay.pools[PoolKey(taxpayer, security)]
        if (pool == null) {
            c.prepareStatement("DELETE FROM calc.pool WHERE taxpayer_id = ? AND security_id = ?").use {
                it.setString(1, taxpayer)
                it.setString(2, security)
                it.executeUpdate()
            }
        } else {
            c.prepareStatement(
                "INSERT INTO calc.pool (taxpayer_id, security_id, shares, total_acb) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (taxpayer_id, security_id) DO UPDATE SET shares = EXCLUDED.shares, " +
                    "total_acb = EXCLUDED.total_acb",
            ).use {
                it.setString(1, taxpayer)
                it.setString(2, security)
                it.setLong(3, pool.shares)
                it.setLong(4, pool.totalAcb)
                it.executeUpdate()
            }
        }
    }

    /** The (taxpayer, security) of a reversed pool activity, or null if it has none (nothing to recompute). */
    private fun reversedKey(
        c: Connection,
        entryId: Long,
    ): Pair<String, String>? =
        c.prepareStatement("SELECT taxpayer_id, security_id FROM ledger.activity WHERE entry_id = ?").use {
            it.setLong(1, entryId)
            it.executeQuery().use { rs ->
                if (rs.next() && rs.getString(2) != null) rs.getString(1) to rs.getString(2) else null
            }
        }

    private fun effectiveHistory(
        c: Connection,
        taxpayer: String,
        security: String,
        beforeEntryId: Long,
    ): List<PostedActivity> =
        c.prepareStatement(
            "SELECT a.activity_id, a.entry_id, a.idempotency_key, a.taxpayer_id, a.account_id, a.account_type, " +
                "a.activity_type, a.effective_date, a.settlement_date, a.security_id, a.quantity, a.currency, " +
                "a.amount, a.commission, a.counter_currency, a.counter_amount, a.description, a.source_seq " +
                "FROM ledger.activity a WHERE a.taxpayer_id = ? AND a.security_id = ? AND a.entry_id < ? " +
                "AND NOT EXISTS (SELECT 1 FROM ledger.reversal r WHERE r.original_entry_id = a.entry_id) " +
                "ORDER BY a.entry_id",
        ).use {
            it.setString(1, taxpayer)
            it.setString(2, security)
            it.setLong(3, beforeEntryId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.posted()) } }
        }

    private fun ResultSet.posted(): PostedActivity =
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
}
