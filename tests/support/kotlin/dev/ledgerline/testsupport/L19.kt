package dev.ledgerline.testsupport

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.ImportDefaults
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * L-19 broker adapters (docs/stories/L-19-broker-adapters.md). PROVISIONAL: the story is a Draft. Broker B is the
 * story's synthetic format; every value in the tests below is a count, a date, a key or an amount written in the
 * story or derived from the L-01 fixture `l01/ac1_valid.csv`. No tax value is asserted by hand: AC4 compares the
 * engine's own output for two renderings of the same trades.
 *
 * API CONTRACT (PROPOSED by QE; Dev may add members, not change these). Package `dev.ledgerline.ingest`:
 *
 * ```kotlin
 * enum class BrokerFormat { CANONICAL, BROKER_B }
 * data class ImportDefaults(val taxpayerId: String, val accountType: dev.ledgerline.core.AccountType)
 * object BrokerFormats {
 *     val BROKER_B_COLUMNS: List<String>   // the 12 header names of the story, in order
 *     /** First line only. A BOM, a trailing CR and surrounding spaces are tolerated. Null for any other header,
 *      *  an empty line or an empty file. Exact column names and order; no guessing. */
 *     fun detect(headerLine: String): BrokerFormat?
 *     /** Detects from the first line of [text], then parses with CanonicalCsv (CANONICAL; [defaults] ignored) or the
 *      *  Broker B mapping (BROKER_B; [defaults] supplies taxpayerId and accountType for every row). Reuses
 *      *  CanonicalCsv's ParseResult, Rejection and FileError. Never throws on bad content.
 *      *  - Unknown header: no activities, no rejections, one FileError with code HEADER_INVALID whose message
 *      *    contains both expected header lines in full (canonical joined by ",", Broker B joined by ",").
 *      *  - BROKER_B with [defaults] == null: no activities, no rejections, at least one FileError (QE PROPOSAL, from
 *      *    AC6: the upload must name the taxpayer and account type); the code is Dev's choice. */
 *     fun parse(text: String, defaults: ImportDefaults? = null): ParseResult
 * }
 * ```
 *
 * Row mapping (AC2): `Trade Date` and `Settle Date` (`MM/DD/YYYY`, blank settle allowed) to ISO dates; `Action` to
 * BUY, SELL, DEPOSIT, WITHDRAWAL, TRANSFER_IN, DIVIDEND; `Symbol` to securityId (blank for cash actions); `abs(Quantity)`
 * to the fixed-point quantity; `Gross Amount` (optional leading `$`, thousands commas) to amountMinor; `Fees` (same
 * rules) to commissionMinor; `Currency` kept; `Account` to accountId; key `BROKERB:<Account>:<Reference>`; taxpayer
 * and account type from [ImportDefaults]; `Price` and `Memo` do not affect the amount. Whether `Memo` becomes the
 * description is NOT asserted (the story is silent), so the tests compare activities with the description cleared.
 * `sourceSeq` follows the same row-position rule as the canonical parser, so the same trades on the same lines give
 * the same `sourceSeq`; the property test asserts it.
 *
 * Row errors (AC3): a `Rejection(line, code, field, message)` with `line` the physical line (header = 1; a quoted
 * field that spans lines counts physical lines, as in L-01) and `field` the Broker B column name exactly as in the
 * header. Codes (QE PROPOSAL, reuse L-01 where one exists): bad `Trade Date` or `Settle Date` is INVALID_DATE; an
 * `Action` outside the six is UNKNOWN_ACTIVITY_TYPE; an empty `Reference` is MISSING_FIELD. A SELL with a positive
 * `Quantity`: field `Quantity`, code Dev's choice (not blank). Other rows of the file are unaffected.
 *
 * Package `dev.ledgerline.app` (existing signatures keep working):
 *
 * ```kotlin
 * data class PreviewReport(..., val format: BrokerFormat?)   // null only for an unknown header; added last
 * object ImportPreview {
 *     fun classify(csv: String, stored: (String) -> String?, existing: List<PostedActivity> = emptyList(),
 *                  defaults: ImportDefaults? = null): PreviewReport
 * }
 * ```
 *
 * HTTP (not covered by this phase's tests; an integration spec can follow): `POST /v1/imports/preview` and
 * `POST /v1/imports/confirm` accept `?taxpayer=<id>&accountType=<NON_REGISTERED|TFSA|RRSP>`; the response gains
 * `"format":"CANONICAL"|"BROKER_B"` (JSON null for an unknown header). The confirm must be given the same query.
 */
object L19 {
    const val TP = "TP-0001"
    const val ACCOUNT = "TP-0001-NR1"
    val defaults = ImportDefaults(TP, AccountType.NON_REGISTERED)

    val BROKER_B_HEADER =
        "Trade Date,Settle Date,Account,Action,Symbol,Quantity,Price,Gross Amount,Fees,Currency,Reference,Memo"
    val CANONICAL_HEADER: String = CanonicalCsv.COLUMNS.joinToString(",")

    private val mmddyyyy = DateTimeFormatter.ofPattern("MM/dd/yyyy")

    /** A valid Broker B BUY row (the AC1 BUY: 100 SYN-AAA, 2025-01-07, settles 01-08, 3,012.50 CAD, fee 4.95). */
    fun row(vararg overrides: Pair<String, String>): String {
        val base =
            linkedMapOf(
                "Trade Date" to "01/07/2025", "Settle Date" to "01/08/2025", "Account" to ACCOUNT, "Action" to "Buy",
                "Symbol" to "SYN-AAA", "Quantity" to "100", "Price" to "30.125", "Gross Amount" to "\$3,012.50",
                "Fees" to "4.95", "Currency" to "CAD", "Reference" to "R2", "Memo" to "",
            )
        overrides.forEach { (k, v) ->
            require(k in base) { "unknown Broker B column $k" }
            base[k] = v
        }
        return base.values.joinToString(",")
    }

    /** The AC1 deposit as Broker B: 01/06/2025, 10,000.00 CAD, reference R1. */
    val depositRow: String =
        row(
            "Trade Date" to "01/06/2025", "Settle Date" to "01/06/2025", "Action" to "Deposit", "Symbol" to "",
            "Quantity" to "", "Price" to "", "Gross Amount" to "\$10,000.00", "Fees" to "0.00", "Reference" to "R1",
        )

    fun file(vararg rows: String): String = BROKER_B_HEADER + "\n" + rows.joinToString("") { it + "\n" }

    /** Broker B file for AC1's first two rows (lines 2 and 3). */
    val ac1LikeFile: String = file(depositRow, row())

    /** Clears what the tests do not compare across formats: the key and the description. */
    fun Activity.normalized(): Activity = copy(idempotencyKey = "", description = null)

    // ---------- rendering generated trades in both formats (AC4) ----------

    /** Plain decimal of [units] at [decimals] places with trailing zeros trimmed: 1_500_000 at 6 is "1.5". */
    fun plain(
        units: Long,
        decimals: Int,
    ): String {
        val scale = generateSequence(1L) { it * 10 }.elementAt(decimals)
        val whole = abs(units) / scale
        val frac = (abs(units) % scale).toString().padStart(decimals, '0').trimEnd('0')
        return (if (units < 0) "-" else "") + whole + if (frac.isEmpty()) "" else ".$frac"
    }

    /** Minor units as "1234.56", or with a leading dollar sign and thousands commas when [fancy]. Always 2 places. */
    fun money(
        minor: Long,
        fancy: Boolean,
    ): String {
        val whole = minor / 100
        val cents = (minor % 100).toString().padStart(2, '0')
        val w = if (fancy) "%,d".format(java.util.Locale.ROOT, whole) else whole.toString()
        return (if (fancy) "\$" else "") + "$w.$cents"
    }

    private fun action(t: ActivityType): String =
        when (t) {
            ActivityType.BUY -> "Buy"
            ActivityType.SELL -> "Sell"
            ActivityType.DEPOSIT -> "Deposit"
            ActivityType.WITHDRAWAL -> "Withdrawal"
            ActivityType.TRANSFER_IN -> "Transfer In"
            ActivityType.DIVIDEND -> "Dividend"
            else -> error("no Broker B action for $t")
        }

    /**
     * The Broker B file for [acts] (BUY, SELL, DEPOSIT, WITHDRAWAL, TRANSFER_IN, DIVIDEND only), one row each in the
     * given order, account [account], references R1, R2, ... . Money cells use `$` and commas when the row index is
     * odd, so both spellings are exercised. `Price` is a fixed placeholder.
     */
    fun renderBrokerB(
        acts: List<Activity>,
        account: String,
    ): String =
        BROKER_B_HEADER + "\n" +
            acts.withIndex().joinToString("") { (i, a) ->
                val fancy = i % 2 == 1
                val qty =
                    a.quantity?.let { if (a.activityType == ActivityType.SELL) "-" + plain(it, 6) else plain(it, 6) }
                listOf(
                    a.effectiveDate.format(mmddyyyy),
                    a.settlementDate?.format(mmddyyyy).orEmpty(),
                    account,
                    action(a.activityType),
                    a.securityId.orEmpty(),
                    qty.orEmpty(),
                    if (qty == null) "" else "1.0000",
                    money(a.amountMinor, fancy),
                    money(a.commissionMinor, fancy),
                    a.currency.name,
                    "R%04d".format(i + 1),
                    "",
                ).joinToString(",") + "\n"
            }

    fun renderCanonical(acts: List<Activity>): String = CanonicalCsv.serialize(acts)

    /** The date as the canonical file spells it, for assertions. */
    fun iso(d: LocalDate): String = d.toString()
}
