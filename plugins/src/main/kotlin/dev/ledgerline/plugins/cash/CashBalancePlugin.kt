package dev.ledgerline.plugins.cash

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.DomainPlugin
import dev.ledgerline.core.Validation
import java.time.LocalDate

data class CashKey(val accountId: String, val currency: Currency)

data class CashEntry(val idempotencyKey: String, val effectiveDate: LocalDate, val signedAmountMinor: Long)

/** Running cash per account and currency, in integer minor units, with the entries that built each balance. */
data class CashState(val balances: Map<CashKey, Long>, val entries: Map<CashKey, List<CashEntry>>) {
    /** The balance, or 0 when the account never held that currency. */
    fun balanceOf(
        accountId: String,
        currency: Currency,
    ): Long = balances[CashKey(accountId, currency)] ?: 0L

    /** Integers only, LF only, keys sorted: the input to a state hash. */
    fun canonicalText(): String =
        balances.keys.sortedWith(compareBy({ it.accountId }, { it.currency.name })).joinToString("") { k ->
            val head = "${k.accountId}|${k.currency}|${balances.getValue(k)}\n"
            head +
                entries[k].orEmpty().joinToString("") {
                    "  ${it.idempotencyKey}|${it.effectiveDate}|${it.signedAmountMinor}\n"
                }
        }
}

data class CashExplanation(val state: CashState) {
    /** Entries behind one balance, in apply order. */
    fun entriesFor(
        accountId: String,
        currency: Currency,
    ): List<CashEntry> = state.entries[CashKey(accountId, currency)].orEmpty()
}

/**
 * Cash-balance tracker: pure integer arithmetic, no tax logic.
 * OWNER-INPUT: C30 overdraft policy. Placeholder: refuse (see [validateAgainst]); never allow a negative balance.
 */
object CashBalancePlugin : DomainPlugin<CashState, CashExplanation> {
    fun empty(): CashState = CashState(emptyMap(), emptyMap())

    override fun validate(activity: Activity): Validation =
        when {
            activity.accountId.isBlank() -> Validation.Rejected("accountId is blank")
            activity.amountMinor < 0 -> Validation.Rejected("amountMinor is negative")
            activity.commissionMinor < 0 -> Validation.Rejected("commissionMinor is negative")
            else -> Validation.Accepted
        }

    /** Stateful overdraft check. OWNER-INPUT: C30. */
    fun validateAgainst(
        state: CashState,
        activity: Activity,
    ): Validation {
        val shape = validate(activity)
        if (shape is Validation.Rejected) return shape
        for ((key, delta) in effects(activity)) {
            val available = state.balances[key] ?: 0L
            if (available + delta < 0) {
                return Validation.Rejected(
                    "INSUFFICIENT_CASH: account ${key.accountId} currency ${key.currency} available $available",
                )
            }
        }
        return Validation.Accepted
    }

    override fun apply(
        state: CashState,
        activity: Activity,
    ): CashState {
        var balances = state.balances
        var entries = state.entries
        for ((key, delta) in effects(activity)) {
            balances = balances + (key to (balances[key] ?: 0L) + delta)
            val entry = CashEntry(activity.idempotencyKey, activity.effectiveDate, delta)
            entries = entries + (key to entries[key].orEmpty() + entry)
        }
        return CashState(balances, entries)
    }

    override fun explain(state: CashState): CashExplanation = CashExplanation(state)

    private fun effects(a: Activity): List<Pair<CashKey, Long>> {
        val own = CashKey(a.accountId, a.currency)
        val list =
            when (a.activityType) {
                ActivityType.DEPOSIT, ActivityType.DIVIDEND, ActivityType.RETURN_OF_CAPITAL ->
                    listOf(own to a.amountMinor)
                ActivityType.WITHDRAWAL, ActivityType.FEE -> listOf(own to -a.amountMinor)
                ActivityType.BUY -> listOf(own to -(a.amountMinor + a.commissionMinor))
                ActivityType.SELL -> listOf(own to a.amountMinor - a.commissionMinor)
                ActivityType.FX_CONVERSION ->
                    listOfNotNull(
                        own to -a.amountMinor,
                        a.counterCurrency?.let { c -> a.counterAmountMinor?.let { CashKey(a.accountId, c) to it } },
                    )
                ActivityType.TRANSFER_IN -> emptyList()
            }
        return list.filter { it.second != 0L }
    }
}
