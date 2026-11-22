package dev.ledgerline.testsupport

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.core.Activity
import java.io.File
import java.math.BigDecimal

/** Ledgerline's view of one sell, in reported cents (`ROUND-BOUNDARY`: allowed = rounded loss - rounded denied). */
data class LedgerlineSale(
    val key: String,
    val gainCents: Long,
    val deniedCents: Long,
    val acbAddedCents: Long,
)

data class LedgerlineSecurity(
    val security: String,
    val finalShares: Long,
    val finalAcbCents: Long,
    val sales: List<LedgerlineSale>,
)

/** One mismatch. [ac] is the acceptance criterion it belongs to (2, 3 or 4). */
data class Difference(
    val ac: Int,
    val seed: Long,
    val security: String,
    val field: String,
    val ledgerline: String,
    val acb: String,
) {
    /** AC5 format: `seed=7 security=SYN-AAA field=<name> ledgerline=<v> acb=<v>`. */
    val message: String = "seed=$seed security=$security field=$field ledgerline=$ledgerline acb=$acb"
}

object L15Compare {
    private val engine = AcbEngine(CaIta2026v1)

    fun ledgerline(activities: List<Activity>): Map<String, LedgerlineSecurity> {
        val posted = activities.mapIndexed { i, a -> L06Activities.posted(a, 1_000L + i) }
        val replay = engine.replaySuperficial(posted)
        check(replay.rejections.isEmpty()) { "Ledgerline rejected generated activities: ${replay.rejections}" }
        val cents = CaIta2026v1::toReportedCents
        return replay.pools.entries.associate { (key, pool) ->
            val sales =
                replay.sales.filter { s -> pool.history.any { it.activityId == s.activityId } }.map { s ->
                    val added =
                        pool.history.filter { it.activityId == s.activityId && "SL-ADDBACK-TARGET" in it.ruleIds }
                            .sumOf { it.acbDelta }
                    LedgerlineSale(
                        key = s.activityId.removePrefix("act-"),
                        gainCents = cents(s.gainBeforeRule) + cents(s.denied),
                        deniedCents = cents(s.denied),
                        acbAddedCents = cents(added),
                    )
                }
            key.securityId to LedgerlineSecurity(key.securityId, pool.shares, cents(pool.totalAcb), sales)
        }
    }

    fun compare(
        seed: Long,
        ledgerline: Map<String, LedgerlineSecurity>,
        acb: AcbRun,
    ): List<Difference> {
        val out = mutableListOf<Difference>()

        fun diff(
            ac: Int,
            security: String,
            field: String,
            l: Any,
            a: Any,
        ) {
            if (l != a) out += Difference(ac, seed, security, field, l.toString(), a.toString())
        }
        diff(
            2,
            "*",
            "securities",
            ledgerline.keys.sorted().joinToString("|"),
            acb.securities.keys.sorted().joinToString("|"),
        )
        for (name in ledgerline.keys.intersect(acb.securities.keys).sorted()) {
            val l = ledgerline.getValue(name)
            val a = acb.securities.getValue(name)
            diff(2, name, "finalShares", shares(l.finalShares), shares(a.finalShares))
            diff(2, name, "finalAcb", money(l.finalAcbCents), money(a.finalAcbCents))
            diff(3, name, "sells", l.sales.map { it.key }, a.sells.map { it.key })
            val byKey = a.sells.associateBy { it.key }
            for (sale in l.sales) {
                val s = byKey[sale.key] ?: continue
                diff(3, name, "gain[${sale.key}]", money(sale.gainCents), money(s.gainCents))
                diff(4, name, "denied[${sale.key}]", money(sale.deniedCents), money(s.deniedCents))
                diff(4, name, "acbAdded[${sale.key}]", money(sale.acbAddedCents), money(s.acbAddedCents))
            }
            val ledgerDenials = l.sales.filter { it.deniedCents != 0L }.map { it.key }
            val acbDenials = a.sells.filter { it.deniedCents != 0L }.map { it.key }
            diff(4, name, "deniedSells", ledgerDenials, acbDenials)
        }
        return out
    }

    fun shares(units: Long): String = BigDecimal.valueOf(units, 6).stripTrailingZeros().toPlainString()

    fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /** Runs Ledgerline and acb on seed history and returns both views (the exporter is the code under test). */
    fun both(
        seed: Long,
        bin: File,
    ): Pair<Map<String, LedgerlineSecurity>, AcbRun> {
        val activities = L15Histories.history(seed)
        val csv = dev.ledgerline.ingest.AcbCsvExporter.export(activities)
        return ledgerline(activities) to L15Oracle.run(bin, csv)
    }
}
