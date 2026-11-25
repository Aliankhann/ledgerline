package dev.ledgerline.ingest

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity

enum class BrokerFormat { CANONICAL, BROKER_B }

/** What a Broker B file does not say: the taxpayer and the account type, named by the upload. */
data class ImportDefaults(val taxpayerId: String, val accountType: AccountType)

/** L-19: format detection from the header row and the Broker B (synthetic) adapter. */
object BrokerFormats {
    val BROKER_B_COLUMNS: List<String> =
        listOf(
            "Trade Date", "Settle Date", "Account", "Action", "Symbol", "Quantity", "Price", "Gross Amount", "Fees",
            "Currency", "Reference", "Memo",
        )

    private val BOM = 0xFEFF.toChar().toString()
    private val datePattern = Regex("^(\\d{2})/(\\d{2})/(\\d{4})$")
    private val actions =
        mapOf(
            "Buy" to "BUY",
            "Sell" to "SELL",
            "Deposit" to "DEPOSIT",
            "Withdrawal" to "WITHDRAWAL",
            "Transfer In" to "TRANSFER_IN",
            "Dividend" to "DIVIDEND",
        )

    /** Canonical column name to the Broker B column it comes from, for naming the column in a row error. */
    private val columnOf =
        mapOf(
            "idempotency_key" to "Reference", "account_id" to "Account", "activity_type" to "Action",
            "effective_date" to "Trade Date", "settlement_date" to "Settle Date", "security_id" to "Symbol",
            "quantity" to "Quantity", "currency" to "Currency", "amount" to "Gross Amount", "commission" to "Fees",
        )

    fun detect(headerLine: String): BrokerFormat? {
        val cols = headerLine.removePrefix(BOM).trim().split(",")
        return when (cols) {
            CanonicalCsv.COLUMNS -> BrokerFormat.CANONICAL
            BROKER_B_COLUMNS -> BrokerFormat.BROKER_B
            else -> null
        }
    }

    /** The format of a whole file, from its first line. */
    fun detectFile(text: String): BrokerFormat? = detect(text.removePrefix(BOM).lineSequence().first().trimEnd('\r'))

    fun parse(
        text: String,
        defaults: ImportDefaults? = null,
    ): ParseResult =
        when (detectFile(text)) {
            BrokerFormat.CANONICAL -> CanonicalCsv.parse(text)
            BrokerFormat.BROKER_B -> if (defaults == null) needDefaults() else parseBrokerB(text, defaults)
            null -> unknownHeader(text)
        }

    /** The canonical parser names the column it stopped at (L-20); the message adds both expected headers. */
    private fun unknownHeader(text: String): ParseResult {
        val canonical = CanonicalCsv.parse(text).fileErrors.single()
        val message =
            "${canonical.message}. Expected the canonical columns '${CanonicalCsv.COLUMNS.joinToString(",")}' " +
                "or the Broker B columns '${BROKER_B_COLUMNS.joinToString(",")}'"
        return ParseResult(emptyList(), emptyList(), listOf(canonical.copy(message = message)))
    }

    private fun fileError(
        code: String,
        message: String,
    ) = ParseResult(emptyList(), emptyList(), listOf(FileError(code, message, null)))

    private fun needDefaults() =
        fileError("IMPORT_DEFAULTS_REQUIRED", "A Broker B file needs the taxpayer and the account type of the upload")

    private fun parseBrokerB(
        text: String,
        defaults: ImportDefaults,
    ): ParseResult {
        val activities = mutableListOf<Activity>()
        val lines = mutableListOf<Int>()
        val rejections = mutableListOf<Rejection>()
        for ((line, fields) in CanonicalCsv.records(text).drop(1)) {
            when (val r = row(fields, defaults)) {
                is FieldsResult.Valid -> {
                    activities += r.activity
                    lines += line
                }
                is FieldsResult.Invalid -> rejections += Rejection(line, r.code, r.field, r.message)
            }
        }
        return ParseResult(activities, rejections, emptyList(), lines)
    }

    private fun invalid(
        code: String,
        field: String,
        message: String,
    ) = FieldsResult.Invalid(code, field, message)

    private fun row(
        raw: List<String>,
        defaults: ImportDefaults,
    ): FieldsResult {
        val cells = rejoinThousands(raw)
        if (cells.size != BROKER_B_COLUMNS.size) {
            return invalid("WRONG_COLUMN_COUNT", "", "Expected ${BROKER_B_COLUMNS.size} fields but found ${cells.size}")
        }
        val f = BROKER_B_COLUMNS.zip(cells.map { it.trim() }).toMap()
        val reference = f.getValue("Reference")
        if (reference.isEmpty()) return invalid("MISSING_FIELD", "Reference", "Reference is required")
        val action =
            actions[f.getValue("Action")]
                ?: return invalid("UNKNOWN_ACTIVITY_TYPE", "Action", "Unknown action '${f.getValue("Action")}'")
        val quantity = f.getValue("Quantity")
        if (action == "SELL" && quantity.isNotEmpty() && !quantity.startsWith("-")) {
            return invalid("SELL_QUANTITY_NOT_NEGATIVE", "Quantity", "A sell must have a negative Quantity")
        }
        val trade = iso(f.getValue("Trade Date"))
        val settle = iso(f.getValue("Settle Date"))
        if (trade == null) {
            return invalid(
                "INVALID_DATE",
                "Trade Date",
                "'${f.getValue("Trade Date")}' is not MM/DD/YYYY",
            )
        }
        if (settle == null) {
            return invalid("INVALID_DATE", "Settle Date", "'${f.getValue("Settle Date")}' is not MM/DD/YYYY")
        }
        val canonical =
            mapOf(
                "idempotency_key" to "BROKERB:${f.getValue("Account")}:$reference",
                "taxpayer_id" to defaults.taxpayerId,
                "account_id" to f.getValue("Account"),
                "account_type" to defaults.accountType.name,
                "activity_type" to action,
                "effective_date" to trade,
                "settlement_date" to settle,
                "security_id" to f.getValue("Symbol"),
                "quantity" to quantity.removePrefix("-"),
                "currency" to f.getValue("Currency"),
                "amount" to f.getValue("Gross Amount").removePrefix("$").replace(",", ""),
                "commission" to f.getValue("Fees").removePrefix("$").replace(",", ""),
                "counter_currency" to "",
                "counter_amount" to "",
                "description" to f.getValue("Memo"),
            )
        return when (val r = CanonicalCsv.parseFields(canonical)) {
            is FieldsResult.Invalid -> r.copy(field = columnOf[r.field] ?: r.field)
            else -> r
        }
    }

    private val thousandsHead = Regex("^\\$?\\d{1,3}(,\\d{3})*$")
    private val thousandsTail = Regex("^\\d{3}(\\.\\d+)?$")

    /**
     * An unquoted amount such as `$3,012.50` splits at its comma. While a row has too many cells, the money columns
     * (Gross Amount, then Fees) take back the following cell if it is exactly a thousands group.
     */
    private fun rejoinThousands(cells: List<String>): List<String> {
        val out = cells.toMutableList()
        for (col in listOf(7, 8)) {
            while (out.size > BROKER_B_COLUMNS.size && col + 1 < out.size &&
                thousandsHead.matches(out[col].trim()) && thousandsTail.matches(out[col + 1].trim())
            ) {
                out[col] = out[col] + "," + out[col + 1]
                out.removeAt(col + 1)
            }
        }
        return out
    }

    /** `MM/DD/YYYY` to ISO text; blank stays blank; null when the shape is wrong (the calendar is checked later). */
    private fun iso(v: String): String? =
        if (v.isEmpty()) {
            ""
        } else {
            datePattern.matchEntire(v)?.let {
                "${it.groupValues[3]}-${it.groupValues[1]}-${it.groupValues[2]}"
            }
        }
}
