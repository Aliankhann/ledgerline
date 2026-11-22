package dev.ledgerline.calc

/** One explained part of a difference; [amountMinor] is cents and the parts of a holding sum to its difference. */
data class Reason(val ruleId: String, val text: String, val entryIds: List<Long>, val amountMinor: Long)

/** `differenceCents = acbCents - bookValueCents`. */
data class HoldingDiff(
    val key: PoolKey,
    val bookValueCents: Long,
    val acbCents: Long,
    val differenceCents: Long,
    val reasons: List<Reason>,
)

/** One row of the full holdings list; a null book value or difference means the broker gave no value (C31). */
data class HoldingRow(
    val key: PoolKey,
    val bookValueCents: Long?,
    val acbCents: Long,
    val differenceCents: Long?,
    val reasons: List<Reason>,
)

/** Pure comparison of broker book value with pool ACB (L-11). Rule IDs and entry IDs come from real pool steps. */
object BookValueComparison {
    private val rules = CaIta2026v1

    // OWNER-INPUT: C29 a book value with no pool is shown with this pseudo rule ID (not a rule-set ID).
    const val NO_LEDGER_HISTORY = "NO-LEDGER-HISTORY"
    const val UNEXPLAINED = "UNEXPLAINED"

    // OWNER-INPUT: C31 placeholder pseudo rule IDs for the full holdings list; overlap with NO_LEDGER_HISTORY is open.
    const val NO_BROKER_VALUE = "NO_BROKER_VALUE"
    const val DATA_GAP = "DATA_GAP"

    /** Every key in either input, sorted; pools without a book value and book values without a pool are explained. */
    fun compareAll(
        pools: Map<PoolKey, AcbPool>,
        bookValues: Map<PoolKey, Long>,
    ): List<HoldingRow> =
        (pools.keys + bookValues.keys)
            .sortedWith(compareBy({ it.taxpayerId }, { it.securityId }))
            .map { key -> row(key, pools[key], bookValues[key]) }

    private fun row(
        key: PoolKey,
        pool: AcbPool?,
        book: Long?,
    ): HoldingRow {
        if (book != null) {
            val d = diff(key, pool, book)
            val reasons =
                if (pool == null && d.differenceCents != 0L) {
                    listOf(
                        Reason(
                            DATA_GAP,
                            "The book value has no matching activity in the ledger.",
                            emptyList(),
                            d.differenceCents,
                        ),
                    )
                } else {
                    d.reasons
                }
            return HoldingRow(key, book, d.acbCents, d.differenceCents, reasons)
        }
        val acb = rules.toReportedCents(checkNotNull(pool).totalAcb)
        val text = "No broker book value was given for this holding, so ACB cannot be compared."
        return HoldingRow(
            key,
            null,
            acb,
            null,
            listOf(Reason(NO_BROKER_VALUE, text, pool.history.map { it.entryId }, 0L)),
        )
    }

    fun compare(
        pools: Map<PoolKey, AcbPool>,
        bookValues: Map<PoolKey, Long>,
    ): List<HoldingDiff> =
        bookValues.keys
            .filter { key -> pools[key]?.let { it.shares > 0L || it.history.isNotEmpty() } ?: true }
            .sortedWith(compareBy({ it.taxpayerId }, { it.securityId }))
            .map { key -> diff(key, pools[key], bookValues.getValue(key)) }

    private fun diff(
        key: PoolKey,
        pool: AcbPool?,
        book: Long,
    ): HoldingDiff {
        val acb = pool?.let { rules.toReportedCents(it.totalAcb) } ?: 0L
        val difference = Math.subtractExact(acb, book)
        return HoldingDiff(key, book, acb, difference, if (difference == 0L) emptyList() else reasons(pool, difference))
    }

    private fun reasons(
        pool: AcbPool?,
        difference: Long,
    ): List<Reason> {
        if (pool == null || pool.history.isEmpty()) {
            return listOf(
                Reason(
                    NO_LEDGER_HISTORY,
                    "The ledger has no entries for this holding, so the whole difference cannot be traced.",
                    emptyList(),
                    difference,
                ),
            )
        }
        // Each candidate is a rule with its own recorded amount in the pool history; only those that point the same
        // way as the difference and fit inside what is left are used, so the parts always sum to the difference.
        val ruleNames = pool.history.flatMap { it.ruleAmounts.keys }.distinct().sorted()
        val candidates = (listOf("ROC-REDUCES-ACB") + ruleNames).map { candidate(pool, it) }
        val result = mutableListOf<Reason>()
        var rest = difference
        for (c in candidates) {
            val fits = c.cents != 0L && java.lang.Long.signum(c.cents) == java.lang.Long.signum(rest)
            if (fits && Math.abs(c.cents) <= Math.abs(rest)) {
                result += Reason(c.rule, describe(c.rule, c.cents), c.entryIds, c.cents)
                rest -= c.cents
            }
        }
        if (rest != 0L) {
            // OWNER-INPUT: C29 what no recorded pool step explains is shown as such, never given to an unrelated rule.
            result +=
                Reason(
                    UNEXPLAINED,
                    "${money(rest)} of the difference is not explained by any recorded pool step; " +
                        "the broker's book value may follow a different method.",
                    pool.history.map { it.entryId },
                    rest,
                )
        }
        return result
    }

    private class Candidate(val rule: String, val cents: Long, val entryIds: List<Long>)

    private fun candidate(
        pool: AcbPool,
        rule: String,
    ): Candidate {
        val roc = rule == "ROC-REDUCES-ACB"
        val changes = pool.history.filter { if (roc) rule in it.ruleIds else rule in it.ruleAmounts }
        val units = changes.sumOf { if (roc) it.acbDelta else it.ruleAmounts.getValue(rule) }
        return Candidate(rule, rules.toReportedCents(units), changes.map { it.entryId })
    }

    private fun describe(
        rule: String,
        cents: Long,
    ): String =
        when {
            rule == "ROC-REDUCES-ACB" ->
                "Returns of capital lower ACB by ${money(-cents)}; book value that ignores them is higher."
            rule == "COST-BUY-COMMISSION" ->
                "Buy commissions of ${money(cents)} are added to ACB; book value that omits them is lower."
            rule.startsWith("SL-") ->
                "Denied superficial losses of ${money(cents)} are added back to ACB; book value without them is lower."
            else -> "$rule accounts for ${money(cents)}."
        }

    private fun money(cents: Long): String {
        val a = Math.abs(cents)
        return "${if (cents < 0) "-" else ""}$${a / 100}.${"%02d".format(a % 100)}"
    }
}

/** Book value CSV reader. Integer parsing only. */
object BookValueCsv {
    private val amount = Regex("""(\d+)(?:\.(\d{2}))?""")

    // OWNER-INPUT: B2 provisional format: header `security_id,book_value`, dollars with an optional 2-decimal fraction.
    fun parse(
        taxpayerId: String,
        csv: String,
    ): Map<PoolKey, Long> {
        val lines = csv.lines().filter { it.isNotBlank() }
        require(lines.firstOrNull()?.trim() == "security_id,book_value") { "Expected header security_id,book_value" }
        val out = linkedMapOf<PoolKey, Long>()
        for (line in lines.drop(1)) {
            val cols = line.split(",").map { it.trim() }
            require(cols.size == 2 && cols[0].isNotEmpty()) { "Malformed row: $line" }
            val m = requireNotNull(amount.matchEntire(cols[1])) { "Malformed book value: ${cols[1]}" }
            val dollars = m.groupValues[1].toLong()
            val fraction = m.groupValues[2].ifEmpty { "0" }.toLong()
            val cents = Math.addExact(Math.multiplyExact(dollars, 100L), fraction)
            require(out.put(PoolKey(taxpayerId, cols[0]), cents) == null) { "Duplicate security ${cols[0]}" }
        }
        return out
    }
}
