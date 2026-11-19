package dev.ledgerline.ingest

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.FixedPoint
import dev.ledgerline.core.Scale
import java.time.DateTimeException
import java.time.LocalDate

/** line: 1-based physical line of the row's first character. */
data class Rejection(val line: Int, val code: String, val field: String, val message: String)

data class FileError(val code: String, val message: String, val column: String?)

data class ParseResult(
    val activities: List<Activity>,
    val rejections: List<Rejection>,
    val fileErrors: List<FileError>,
)

/** The Ledgerline canonical CSV (v1): parser and serializer. */
object CanonicalCsv {
    val COLUMNS: List<String> =
        listOf(
            "idempotency_key", "taxpayer_id", "account_id", "account_type", "activity_type", "effective_date",
            "settlement_date", "security_id", "quantity", "currency", "amount", "commission", "counter_currency",
            "counter_amount", "description",
        )

    private val keyPattern = Regex("^[A-Za-z0-9._:-]{1,64}$")
    private val datePattern = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val securityTypes =
        setOf(
            ActivityType.BUY,
            ActivityType.SELL,
            ActivityType.DIVIDEND,
            ActivityType.RETURN_OF_CAPITAL,
            ActivityType.TRANSFER_IN,
        )
    private val quantityTypes = setOf(ActivityType.BUY, ActivityType.SELL, ActivityType.TRANSFER_IN)

    private class RowError(val code: String, val field: String, detail: String) : Exception(
        detail,
        null,
        false,
        false,
    )

    private class Record(val line: Int, val fields: List<String>)

    fun parse(text: String): ParseResult {
        val records = tokenize(text.removePrefix(""))
        val header = records.firstOrNull()
        if (header == null) {
            return fileError("File is empty: header row required", null)
        }
        val missing = COLUMNS.firstOrNull { it !in header.fields }
        val badColumn = missing ?: if (header.fields == COLUMNS) null else firstMismatch(header.fields)
        if (badColumn != null) {
            return fileError("Header does not match the canonical columns; problem at '$badColumn'", badColumn)
        }
        val activities = mutableListOf<Activity>()
        val rejections = mutableListOf<Rejection>()
        for (rec in records.drop(1)) {
            try {
                activities += toActivity(rec)
            } catch (e: RowError) {
                rejections += Rejection(rec.line, e.code, e.field, e.message ?: e.code)
            }
        }
        return ParseResult(activities, rejections, emptyList())
    }

    private fun fileError(
        message: String,
        column: String?,
    ) = ParseResult(emptyList(), emptyList(), listOf(FileError("HEADER_INVALID", message, column)))

    private fun firstMismatch(fields: List<String>): String? =
        fields.zip(COLUMNS).firstOrNull { (a, b) -> a != b }?.first
            ?: fields.drop(COLUMNS.size).firstOrNull()

    private fun toActivity(rec: Record): Activity {
        if (rec.fields.size != COLUMNS.size) {
            throw RowError("WRONG_COLUMN_COUNT", "", "Expected ${COLUMNS.size} fields but found ${rec.fields.size}")
        }
        val f = COLUMNS.zip(rec.fields).toMap()

        fun opt(name: String): String? = f.getValue(name).trim().ifEmpty { null }

        fun req(name: String): String = opt(name) ?: throw RowError("MISSING_FIELD", name, "$name is required")

        val key = req("idempotency_key")
        if (!keyPattern.matches(key)) throw RowError("INVALID_IDEMPOTENCY_KEY", "idempotency_key", "Invalid key '$key'")
        val taxpayer = req("taxpayer_id")
        val account = req("account_id")
        val accountType =
            req("account_type").let { v ->
                AccountType.entries.firstOrNull { it.name == v }
                    ?: throw RowError("UNKNOWN_ACCOUNT_TYPE", "account_type", "Unknown account type '$v'")
            }
        val type =
            req("activity_type").let { v ->
                ActivityType.entries.firstOrNull { it.name == v }
                    ?: throw RowError("UNKNOWN_ACTIVITY_TYPE", "activity_type", "Unknown activity type '$v'")
            }
        val effective = date(req("effective_date"), "effective_date")
        val settlement = opt("settlement_date")?.let { date(it, "settlement_date") }
        if (settlement != null && settlement.isBefore(effective)) {
            throw RowError("SETTLEMENT_BEFORE_EFFECTIVE", "settlement_date", "Settlement is before the effective date")
        }
        val security = if (type in securityTypes) req("security_id") else opt("security_id")
        val quantityText = if (type in quantityTypes) req("quantity") else opt("quantity")
        val quantity = quantityText?.let { quantity(it) }
        val currency = currency(req("currency"), "currency")
        val amount = money(req("amount"), "amount", positive = false)
        val commission = opt("commission")?.let { money(it, "commission", positive = false) } ?: 0L
        var counterCurrency: Currency? = null
        var counterAmount: Long? = null
        if (type == ActivityType.FX_CONVERSION) {
            counterCurrency = currency(req("counter_currency"), "counter_currency")
            if (counterCurrency == currency) {
                throw RowError("INVALID_COUNTER_CURRENCY", "counter_currency", "Must differ from currency")
            }
            counterAmount = money(req("counter_amount"), "counter_amount", positive = true)
        }
        return Activity(
            key, taxpayer, account, accountType, type, effective, settlement, security, quantity, currency, amount,
            commission, counterCurrency, counterAmount, opt("description"),
        )
    }

    private fun date(
        v: String,
        field: String,
    ): LocalDate {
        val m = datePattern.matchEntire(v) ?: throw RowError("INVALID_DATE", field, "'$v' is not YYYY-MM-DD")
        return try {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        } catch (e: DateTimeException) {
            throw RowError("INVALID_DATE", field, "'$v' is not a real calendar date: ${e.message}")
        }
    }

    private fun currency(
        v: String,
        field: String,
    ): Currency =
        Currency.entries.firstOrNull { it.name == v }
            ?: throw RowError("UNSUPPORTED_CURRENCY", field, "Unsupported currency '$v'")

    private fun quantity(v: String): Long =
        when (val p = FixedPoint.parse(v, Scale.SHARE_DECIMALS)) {
            is FixedPoint.Parsed.Ok ->
                if (p.value <= 0) {
                    throw RowError(
                        "NON_POSITIVE_QUANTITY",
                        "quantity",
                        "Quantity must be positive",
                    )
                } else {
                    p.value
                }
            FixedPoint.Parsed.TooPrecise ->
                throw RowError("QUANTITY_TOO_PRECISE", "quantity", "At most ${Scale.SHARE_DECIMALS} decimal places")
            FixedPoint.Parsed.Invalid -> throw RowError("INVALID_NUMBER", "quantity", "'$v' is not a decimal number")
        }

    private fun money(
        v: String,
        field: String,
        positive: Boolean,
    ): Long =
        when (val p = FixedPoint.parse(v, Scale.MONEY_DECIMALS)) {
            is FixedPoint.Parsed.Ok ->
                when {
                    p.value < 0 -> throw RowError("NEGATIVE_AMOUNT", field, "$field must not be negative")
                    positive && p.value == 0L -> throw RowError("NON_POSITIVE_AMOUNT", field, "$field must be positive")
                    else -> p.value
                }
            FixedPoint.Parsed.TooPrecise ->
                throw RowError(
                    if (field == "amount") "AMOUNT_TOO_PRECISE" else "${field.uppercase()}_TOO_PRECISE",
                    field,
                    "At most ${Scale.MONEY_DECIMALS} decimal places",
                )
            FixedPoint.Parsed.Invalid -> throw RowError("INVALID_NUMBER", field, "'$v' is not a decimal number")
        }

    /** RFC 4180 tokenizer. Blank lines are skipped; a record's line is where it starts. */
    private fun tokenize(text: String): List<Record> {
        val out = mutableListOf<Record>()
        var fields = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var wasQuoted = false
        var line = 1
        var startLine = 1
        var i = 0

        fun endRecord() {
            fields += cur.toString()
            cur.clear()
            if (!(fields.size == 1 && fields[0].isEmpty() && !wasQuoted)) out += Record(startLine, fields)
            fields = mutableListOf()
            wasQuoted = false
        }
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && text.getOrNull(i + 1) == '"' -> {
                        cur.append('"')
                        i++
                    }
                    c == '"' -> quoted = false
                    else -> {
                        if (c == '\n') line++
                        cur.append(c)
                    }
                }
            } else {
                when {
                    c == '"' && cur.isEmpty() -> {
                        quoted = true
                        wasQuoted = true
                    }
                    c == ',' -> {
                        fields += cur.toString()
                        cur.clear()
                    }
                    c == '\r' || c == '\n' -> {
                        if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                        endRecord()
                        line++
                        startLine = line
                    }
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (cur.isNotEmpty() || fields.isNotEmpty() || wasQuoted) endRecord()
        return out
    }

    fun serialize(activities: List<Activity>): String {
        val sb = StringBuilder()
        sb.append(COLUMNS.joinToString(",")).append('\n')
        for (a in activities) {
            val row =
                listOf(
                    a.idempotencyKey, a.taxpayerId, a.accountId, a.accountType.name, a.activityType.name,
                    a.effectiveDate.toString(), a.settlementDate?.toString().orEmpty(), a.securityId.orEmpty(),
                    a.quantity?.let { FixedPoint.format(it, Scale.SHARE_DECIMALS, minimal = true) }.orEmpty(),
                    a.currency.name, cents(a.amountMinor), cents(a.commissionMinor),
                    a.counterCurrency?.name.orEmpty(), a.counterAmountMinor?.let { cents(it) }.orEmpty(),
                    a.description.orEmpty(),
                )
            sb.append(row.joinToString(",") { quote(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun cents(v: Long) = FixedPoint.format(v, Scale.MONEY_DECIMALS, minimal = false)

    private fun quote(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
