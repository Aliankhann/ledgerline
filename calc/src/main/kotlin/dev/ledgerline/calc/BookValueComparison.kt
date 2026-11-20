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

/** Pure comparison of broker book value with pool ACB (L-11). Rule IDs and entry IDs come from real pool steps. */
object BookValueComparison {
    private val rules = CaIta2026v1

    // OWNER-INPUT: C29 a book value with no pool is shown with this pseudo rule ID (not a rule-set ID).
    const val NO_LEDGER_HISTORY = "NO-LEDGER-HISTORY"

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
        val rocs = pool.history.filter { "ROC-REDUCES-ACB" in it.ruleIds }
        val rocCents = rules.toReportedCents(rocs.sumOf { it.acbDelta })
        val fits = rocCents != 0L && java.lang.Long.signum(rocCents) == java.lang.Long.signum(difference)
        val rocPart = if (fits && Math.abs(rocCents) <= Math.abs(difference)) rocCents else 0L
        val result = mutableListOf<Reason>()
        if (rocPart != 0L) {
            result +=
                Reason(
                    "ROC-REDUCES-ACB",
                    "Returns of capital lower ACB by ${money(-rocPart)}; book value that ignores them is higher.",
                    rocs.map { it.entryId },
                    rocPart,
                )
        }
        val rest = difference - rocPart
        if (rest != 0L) {
            // OWNER-INPUT: C29 provisional: the remainder is attributed to the pooled entries (POOL-IDENTICAL).
            val pooled = pool.history.filter { "POOL-IDENTICAL" in it.ruleIds }
            val source = pooled.ifEmpty { pool.history }
            val rule = if (pooled.isEmpty()) pool.history.first().ruleIds.min() else "POOL-IDENTICAL"
            result +=
                Reason(
                    rule,
                    "The remaining ${money(rest)} is not explained by a return of capital; ACB is built from these " +
                        "pooled entries, so the broker's book value follows a different method.",
                    source.map { it.entryId },
                    rest,
                )
        }
        return result
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
