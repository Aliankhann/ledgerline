package dev.ledgerline.testsupport

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import java.time.LocalDate
import java.util.Properties

/**
 * One published worked example from `tests/fixtures/cra/` (CRA pages, a CRA technical interpretation, or an
 * independent tool; each file says which). Same discipline as the L-06 and L-07 fixtures: cash in integer cents,
 * shares in units of 10^-6 share, expected values read from the file and never computed by a test. Unlike them, a trade
 * row gives its quoted gross amount directly (`tN.gross.cents`), because several published examples quote totals, not
 * unit prices. [load] refuses a file without an https `source.url`, a `source.section` and a `source.heading`.
 */
class CraFixture private constructor(val name: String, private val props: Properties) {
    val security: String get() = string("security")

    fun string(key: String): String = requireNotNull(props.getProperty(key)) { "$name: missing key '$key'" }.trim()

    fun long(key: String): Long = string(key).toLong()

    fun date(key: String): LocalDate = LocalDate.parse(string(key))

    fun has(key: String): Boolean = props.containsKey(key)

    /** Row `prefix` (`type`, `date`, and `shares.units` + `gross.cents` + `commission.cents`, or `amount.cents` for ROC). */
    fun row(
        prefix: String,
        seq: Long,
    ): PostedActivity {
        val key = "CRA-$name-$prefix"
        return when (val type = ActivityType.valueOf(string("$prefix.type"))) {
            ActivityType.RETURN_OF_CAPITAL ->
                L06Activities.roc(key, date("$prefix.date"), security, long("$prefix.amount.cents"), seq)
            else ->
                L06Activities.trade(
                    key,
                    type,
                    date("$prefix.date"),
                    security,
                    long("$prefix.shares.units"),
                    long("$prefix.gross.cents"),
                    long("$prefix.commission.cents"),
                    seq,
                )
        }
    }

    /** Rows `t1`..`tN` in file order, with `sourceSeq` 1..N. */
    fun rows(count: Int): List<PostedActivity> = (1..count).map { row("t$it", it.toLong()) }

    /** Activity ID of row `prefix` as [row] posts it. */
    fun id(prefix: String): String = "act-CRA-$name-$prefix"

    companion object {
        fun load(name: String): CraFixture {
            val path = "/cra/$name.properties"
            val stream = requireNotNull(CraFixture::class.java.getResourceAsStream(path)) { "fixture $path not found" }
            val props = Properties()
            stream.reader(Charsets.UTF_8).use { props.load(it) }
            return CraFixture(name, props).also { f ->
                // Traceability gate: a tax fixture without a cited source must not load.
                require(f.string("source.url").startsWith("https://")) { "$path: source.url must be an https URL" }
                require(f.string("source.section").isNotBlank()) { "$path: source.section is blank" }
                require(f.string("source.heading").isNotBlank()) { "$path: source.heading is blank" }
            }
        }
    }
}
