package dev.ledgerline.testsupport

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import java.time.LocalDate
import java.util.Properties

/**
 * One L-07 worked example from `tests/fixtures/l07/`. Like the L-06 fixtures, each file cites `source.url` (https) and
 * `source.heading`, and expected values are read from the file, never computed by a test. [load] refuses a file
 * without them.
 */
class L07Fixture private constructor(val name: String, private val props: Properties) {
    val security: String get() = string("security")

    fun string(key: String): String = requireNotNull(props.getProperty(key)) { "$name: missing key '$key'" }.trim()

    fun long(key: String): Long = string(key).toLong()

    fun date(key: String): LocalDate = LocalDate.parse(string(key))

    fun bool(key: String): Boolean = string(key).toBooleanStrict()

    fun has(key: String): Boolean = props.containsKey(key)

    companion object {
        fun load(name: String): L07Fixture {
            val path = "/l07/$name.properties"
            val stream = requireNotNull(L07Fixture::class.java.getResourceAsStream(path)) { "fixture $path not found" }
            val props = Properties()
            stream.reader(Charsets.UTF_8).use { props.load(it) }
            return L07Fixture(name, props).also { f ->
                require(f.string("source.url").startsWith("https://")) { "$path: source.url must be an https URL" }
                require(f.string("source.heading").isNotBlank()) { "$path: source.heading is blank" }
            }
        }
    }
}

/** Builders for the L-07 specs. Accounts: `NR_A`, `NR_B` (non-registered), `TFSA`, `RRSP`, all of one taxpayer. */
object L07Activities {
    const val RRSP_ACCOUNT = "TP-0001-RRSP1"

    fun accountId(label: String): String =
        when (label) {
            "NR_A" -> L06Activities.NR_A
            "NR_B" -> L06Activities.NR_B
            "TFSA" -> L06Activities.TFSA
            "RRSP" -> RRSP_ACCOUNT
            else -> error("unknown account label '$label'")
        }

    fun accountType(label: String): AccountType =
        when (label) {
            "TFSA" -> AccountType.TFSA
            "RRSP" -> AccountType.RRSP
            else -> AccountType.NON_REGISTERED
        }

    /** Trade row `prefix` of [f] (`type`, `date`, `shares.units`, `price.cents`, optional `account`), no commission. */
    fun row(
        f: L07Fixture,
        prefix: String,
        seq: Long,
        accountOverride: String? = null,
    ): PostedActivity {
        val label = accountOverride ?: if (f.has("$prefix.account")) f.string("$prefix.account") else "NR_A"
        val shares = f.long("$prefix.shares.units")
        val product = Math.multiplyExact(f.long("$prefix.price.cents"), shares)
        require(product % Activities.SHARE == 0L) { "${f.name}: $prefix gross is not a whole number of cents" }
        return L06Activities.trade(
            key = "L07-${f.name}-$prefix",
            type = ActivityType.valueOf(f.string("$prefix.type")),
            date = f.date("$prefix.date"),
            security = f.security,
            shares = shares,
            grossCents = product / Activities.SHARE,
            commissionCents = 0L,
            seq = seq,
            accountId = accountId(label),
            accountType = accountType(label),
        )
    }

    /** Activity ID of row `prefix` as [row] posts it. */
    fun id(
        f: L07Fixture,
        prefix: String,
    ): String = "act-L07-${f.name}-$prefix"
}
