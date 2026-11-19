package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType

/**
 * Pure posting rules: an activity becomes balanced double-entry lines, per unit. No database.
 *
 * Lines that belong to the investor's account use `activity.accountId`; the other side of every movement is one of
 * the system accounts below (draft ADR 0005). Share holdings are lines in unit `SEC:<securityId>`.
 */
object Posting {
    const val SECURITY_UNIT_PREFIX = "SEC:"
    const val SYSTEM_ACCOUNT_PREFIX = "SYS:"

    /** Cash and securities entering or leaving the brokerage from outside: deposits, withdrawals, transfers in. */
    const val EXTERNAL = "SYS:EXTERNAL"

    /** The trade counterparty for buys and sells. */
    const val MARKET = "SYS:MARKET"
    const val COMMISSION_EXPENSE = "SYS:EXPENSE:COMMISSION"
    const val FEE_EXPENSE = "SYS:EXPENSE:FEE"
    const val DIVIDEND_INCOME = "SYS:INCOME:DIVIDEND"
    const val RETURN_OF_CAPITAL = "SYS:ISSUER:RETURN_OF_CAPITAL"

    /** Clearing account that carries each currency leg of an FX conversion. */
    const val FX_CLEARING = "SYS:FX:CLEARING"

    /** Activity types whose only movement is their cash amount; with amount 0 they post nothing (C15). */
    val CASH_ONLY_TYPES: Set<ActivityType> =
        setOf(
            ActivityType.DEPOSIT,
            ActivityType.WITHDRAWAL,
            ActivityType.FEE,
            ActivityType.DIVIDEND,
            ActivityType.RETURN_OF_CAPITAL,
        )

    // OWNER-INPUT: C3 share scale. Share lines carry Activity.quantity unchanged, in units of 10^-Scale.SHARE_DECIMALS.
    fun securityUnit(securityId: String): String = SECURITY_UNIT_PREFIX + securityId

    /**
     * The lines for [activity]. Zero movements produce no line; each account and unit nets to at most one line.
     * A cash-only activity (see [CASH_ONLY_TYPES]) with amount 0 is refused with [ZeroAmountException].
     */
    fun linesFor(activity: Activity): List<LedgerLine> {
        val a = activity
        val key = a.idempotencyKey
        require(a.accountId.isNotBlank()) { "$key: accountId is blank" }
        require(!a.accountId.startsWith(SYSTEM_ACCOUNT_PREFIX)) { "$key: accountId uses the system prefix" }
        require(a.amountMinor >= 0 && a.commissionMinor >= 0) { "$key: negative amount or commission" }
        // OWNER-INPUT: C15 zero amounts. A cash-only activity with amount 0 moves nothing and is refused here.
        if (a.activityType in CASH_ONLY_TYPES && a.amountMinor == 0L) throw ZeroAmountException(key, a.activityType)
        val acct = a.accountId
        val cash = a.currency.name
        val m = Movements()
        when (a.activityType) {
            ActivityType.DEPOSIT -> m.move(cash, a.amountMinor, from = EXTERNAL, to = acct)
            ActivityType.WITHDRAWAL -> m.move(cash, a.amountMinor, from = acct, to = EXTERNAL)
            ActivityType.FEE -> m.move(cash, a.amountMinor, from = acct, to = FEE_EXPENSE)
            ActivityType.DIVIDEND -> m.move(cash, a.amountMinor, from = DIVIDEND_INCOME, to = acct)
            ActivityType.RETURN_OF_CAPITAL -> m.move(cash, a.amountMinor, from = RETURN_OF_CAPITAL, to = acct)
            ActivityType.BUY -> {
                m.move(cash, a.amountMinor, from = acct, to = MARKET)
                m.move(cash, a.commissionMinor, from = acct, to = COMMISSION_EXPENSE)
                m.move(shareUnit(a), shares(a), from = MARKET, to = acct)
            }
            ActivityType.SELL -> {
                m.move(cash, a.amountMinor, from = MARKET, to = acct)
                m.move(cash, a.commissionMinor, from = acct, to = COMMISSION_EXPENSE)
                m.move(shareUnit(a), shares(a), from = acct, to = MARKET)
            }
            // The transferred-in book value (amountMinor) is not a cash movement; calculation reads it from the
            // activity, not from the lines.
            ActivityType.TRANSFER_IN -> m.move(shareUnit(a), shares(a), from = EXTERNAL, to = acct)
            ActivityType.FX_CONVERSION -> {
                val counter = requireNotNull(a.counterCurrency) { "$key: FX conversion needs counterCurrency" }
                val counterAmount =
                    requireNotNull(a.counterAmountMinor) { "$key: FX conversion needs counterAmountMinor" }
                require(counter != a.currency) { "$key: FX conversion within one currency" }
                require(counterAmount >= 0) { "$key: negative counterAmountMinor" }
                m.move(cash, a.amountMinor, from = acct, to = FX_CLEARING)
                m.move(counter.name, counterAmount, from = FX_CLEARING, to = acct)
            }
        }
        return m.lines()
    }

    /** Pure. The lines that cancel [lines]: same order, account, unit and amount, opposite side. */
    fun reversalOf(lines: List<LedgerLine>): List<LedgerLine> =
        lines.map { it.copy(side = if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT) }

    /**
     * Throws [UnbalancedEntryException] for the first unit, in line order, whose debits differ from its credits.
     * Every line must have a non-blank account and unit and a positive amount.
     *
     * Debits and credits are summed separately per unit, with exact arithmetic. All amounts are positive, so each
     * total only grows and the outcome depends on the set of lines, not their order: if any unit's total debits or
     * total credits exceeds [Long.MAX_VALUE], the whole entry is refused with an [ArithmeticException] naming it.
     */
    fun requireBalanced(lines: List<LedgerLine>) {
        require(lines.isNotEmpty()) { "an entry needs at least one line" }
        lines.forEach { l ->
            require(l.account.isNotBlank() && l.unit.isNotBlank()) { "blank account or unit in $l" }
            require(l.amount > 0) { "line amount must be positive: $l" }
        }
        val debits = LinkedHashMap<String, Long>()
        val credits = LinkedHashMap<String, Long>()
        lines.forEach { l ->
            val totals = if (l.side == Side.DEBIT) debits else credits
            totals[l.unit] = addTotal(totals[l.unit] ?: 0L, l)
        }
        lines.map { it.unit }.distinct().firstNotNullOfOrNull { unit ->
            val d = debits[unit] ?: 0L
            val c = credits[unit] ?: 0L
            if (d == c) null else UnbalancedEntryException(unit, d - c)
        }?.let { throw it }
    }

    private fun addTotal(
        total: Long,
        line: LedgerLine,
    ): Long =
        try {
            Math.addExact(total, line.amount)
        } catch (e: ArithmeticException) {
            throw ArithmeticException(
                "total ${line.side.name.lowercase()}s in ${line.unit} exceed the representable range",
            )
                .also { it.initCause(e) }
        }

    private fun shareUnit(a: Activity): String =
        securityUnit(requireNotNull(a.securityId) { "${a.idempotencyKey}: ${a.activityType} needs securityId" })

    private fun shares(a: Activity): Long {
        val q = requireNotNull(a.quantity) { "${a.idempotencyKey}: ${a.activityType} needs quantity" }
        require(q > 0) { "${a.idempotencyKey}: quantity must be positive" }
        return q
    }

    /** Signed net movement per (account, unit), in first-touched order. Debit is positive. */
    private class Movements {
        private val net = LinkedHashMap<Pair<String, String>, Long>()

        fun move(
            unit: String,
            amount: Long,
            from: String,
            to: String,
        ) {
            add(from, unit, Math.negateExact(amount))
            add(to, unit, amount)
        }

        private fun add(
            account: String,
            unit: String,
            signed: Long,
        ) {
            net.merge(account to unit, signed, Math::addExact)
        }

        fun lines(): List<LedgerLine> =
            net.filterValues { it != 0L }.map { (key, v) ->
                val side = if (v > 0) Side.DEBIT else Side.CREDIT
                LedgerLine(key.first, key.second, side, Math.absExact(v))
            }
    }
}
