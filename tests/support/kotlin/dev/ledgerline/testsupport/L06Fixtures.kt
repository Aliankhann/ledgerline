package dev.ledgerline.testsupport

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import java.time.LocalDate
import java.util.Properties

/**
 * One L-06 worked example from `tests/fixtures/l06/`. Each file names its published source (`source.url`,
 * `source.heading`) and restates the source's values in the story's units: cash in integer cents, shares in units of
 * 10^-6 share ([Activities.SHARE]). Expected values are read from the file, never computed by the tests. [load]
 * refuses a file without `source.url` (https) and a non-blank `source.heading`.
 */
class L06Fixture private constructor(val name: String, private val props: Properties) {
    val sourceUrl: String get() = string("source.url")
    val sourceHeading: String get() = string("source.heading")
    val security: String get() = string("security")

    fun string(key: String): String = requireNotNull(props.getProperty(key)) { "$name: missing key '$key'" }.trim()

    fun long(key: String): Long = string(key).toLong()

    fun has(key: String): Boolean = props.containsKey(key)

    fun date(key: String): LocalDate = LocalDate.parse(string(key))

    /**
     * The gross cash amount (`amountMinor`, L-01: price x quantity, direction from the type) of a trade row `prefix`,
     * from its `price.cents` and `shares.units`. This restates the source's own "price x shares" input; the product
     * must be exact in cents.
     */
    fun grossCents(prefix: String): Long {
        val product = Math.multiplyExact(long("$prefix.price.cents"), long("$prefix.shares.units"))
        require(product % Activities.SHARE == 0L) { "$name: $prefix gross is not a whole number of cents" }
        return product / Activities.SHARE
    }

    companion object {
        fun load(name: String): L06Fixture {
            val path = "/l06/$name.properties"
            val stream = requireNotNull(L06Fixture::class.java.getResourceAsStream(path)) { "fixture $path not found" }
            val props = Properties()
            stream.reader(Charsets.UTF_8).use { props.load(it) }
            // Traceability gate: a tax fixture without a cited source must not load.
            return L06Fixture(name, props).also { f ->
                require(f.sourceUrl.startsWith("https://")) { "$path: source.url must be an https URL" }
                require(f.sourceHeading.isNotBlank()) { "$path: source.heading is blank" }
            }
        }
    }
}

/** Builders for the L-06 specs and properties. The pure engine takes activities already posted to the ledger. */
object L06Activities {
    const val TAXPAYER = Activities.TAXPAYER
    const val NR_A = "TP-0001-NR1"
    const val NR_B = "TP-0001-NR2"
    const val TFSA = "TP-0001-TFSA1"

    /** Wraps [activity] as posted: activity ID `act-<key>` and ledger entry ID [entryId]. */
    fun posted(
        activity: Activity,
        entryId: Long,
    ): PostedActivity =
        PostedActivity(
            activityId = "act-${activity.idempotencyKey}",
            entryId = entryId,
            activity = activity,
        )

    fun trade(
        key: String,
        type: ActivityType,
        date: LocalDate,
        security: String,
        shares: Long,
        grossCents: Long,
        commissionCents: Long,
        seq: Long,
        accountId: String = NR_A,
        accountType: AccountType = AccountType.NON_REGISTERED,
        taxpayerId: String = TAXPAYER,
    ): PostedActivity =
        posted(
            Activities.activity(
                key = key,
                type = type,
                date = date,
                amountMinor = grossCents,
                securityId = security,
                quantity = shares,
                commissionMinor = commissionCents,
                accountId = accountId,
                accountType = accountType,
                taxpayerId = taxpayerId,
                sourceSeq = seq,
            ),
            entryId = 1_000L + seq,
        )

    /** A return of capital entered as a total amount in cents (rule ROC-INPUT), with no quantity. */
    fun roc(
        key: String,
        date: LocalDate,
        security: String,
        amountCents: Long,
        seq: Long,
        accountId: String = NR_A,
        accountType: AccountType = AccountType.NON_REGISTERED,
        taxpayerId: String = TAXPAYER,
    ): PostedActivity =
        posted(
            Activities.activity(
                key = key,
                type = ActivityType.RETURN_OF_CAPITAL,
                date = date,
                amountMinor = amountCents,
                securityId = security,
                accountId = accountId,
                accountType = accountType,
                taxpayerId = taxpayerId,
                sourceSeq = seq,
            ),
            entryId = 1_000L + seq,
        )

    /** Row `prefix` of fixture [f] (`tN.type` BUY, SELL or RETURN_OF_CAPITAL) as a posted activity. */
    fun row(
        f: L06Fixture,
        prefix: String,
        seq: Long,
        accountId: String = NR_A,
        accountType: AccountType = AccountType.NON_REGISTERED,
    ): PostedActivity {
        val key = "L06-${f.name}-$prefix"
        return when (val type = ActivityType.valueOf(f.string("$prefix.type"))) {
            ActivityType.RETURN_OF_CAPITAL ->
                roc(
                    key,
                    f.date("$prefix.date"),
                    f.security,
                    f.long("$prefix.amount.cents"),
                    seq,
                    accountId,
                    accountType,
                )
            else ->
                trade(
                    key, type, f.date("$prefix.date"), f.security, f.long("$prefix.shares.units"), f.grossCents(prefix),
                    f.long("$prefix.commission.cents"), seq, accountId, accountType,
                )
        }
    }

    /** A buy from fixture keys `prefix.date`, `.shares.units`, `.price.cents`, `.commission.cents`. */
    fun buy(
        f: L06Fixture,
        prefix: String,
        seq: Long,
        accountId: String = NR_A,
        accountType: AccountType = AccountType.NON_REGISTERED,
    ): PostedActivity =
        trade(
            "L06-${f.name}-$prefix", ActivityType.BUY,
            f.date(
                "$prefix.date",
            ),
            f.security, f.long("$prefix.shares.units"),
            f.grossCents(prefix), f.long("$prefix.commission.cents"), seq, accountId, accountType,
        )
}
