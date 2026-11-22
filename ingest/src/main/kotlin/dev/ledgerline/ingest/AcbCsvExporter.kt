package dev.ledgerline.ingest

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Scale

/** Writes BUY and SELL activities as the CSV read by tsiemens/acb (story L-15, AC1). Integer arithmetic only. */
object AcbCsvExporter {
    private const val HEADER =
        "security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo"
    private const val SHARE_UNIT: Long = 1_000_000L // 10^Scale.SHARE_DECIMALS

    init {
        check(SHARE_UNIT == pow10(Scale.SHARE_DECIMALS)) { "share scale changed" }
    }

    private fun pow10(n: Int): Long = (1..n).fold(1L) { acc, _ -> acc * 10 }

    private fun taxDate(a: Activity) = a.settlementDate ?: a.effectiveDate

    fun export(activities: List<Activity>): String {
        val rows =
            activities
                .filter { it.activityType == ActivityType.BUY || it.activityType == ActivityType.SELL }
                .sortedWith(
                    compareBy<Activity> { taxDate(it) }.thenBy { it.sourceSeq }.thenBy { it.idempotencyKey },
                )
                .map(::row)
        return (listOf(HEADER) + rows).joinToString("\n", postfix = "\n")
    }

    private fun row(a: Activity): String {
        val key = a.idempotencyKey
        val security = requireNotNull(a.securityId) { "$key: missing security" }
        val quantity = requireNotNull(a.quantity) { "$key: missing quantity" }
        require(quantity > 0) { "$key: quantity must be positive" }
        val date = taxDate(a)
        val action = if (a.activityType == ActivityType.BUY) "Buy" else "Sell"
        return listOf(
            security,
            date,
            date,
            action,
            shares(quantity),
            unitPrice(key, a.amountMinor, quantity),
            "CAD",
            "",
            cents(a.commissionMinor),
            key,
        ).joinToString(",")
    }

    private fun shares(units: Long): String {
        val whole = units / SHARE_UNIT
        val frac = (units % SHARE_UNIT).toString().padStart(Scale.SHARE_DECIMALS, '0').trimEnd('0')
        return if (frac.isEmpty()) "$whole" else "$whole.$frac"
    }

    /** amount / quantity in cents = amountCents * 10^6 / units; rejected unless exact. */
    private fun unitPrice(
        key: String,
        amountCents: Long,
        units: Long,
    ): String {
        val numerator = Math.multiplyExact(amountCents, SHARE_UNIT)
        require(numerator % units == 0L) { "$key: unit price is not an exact number of cents" }
        return cents(numerator / units)
    }

    private fun cents(v: Long): String {
        require(v >= 0) { "negative amount: $v" }
        return "${v / 100}.${(v % 100).toString().padStart(2, '0')}"
    }
}
