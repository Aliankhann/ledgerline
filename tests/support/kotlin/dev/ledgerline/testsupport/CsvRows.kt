package dev.ledgerline.testsupport

import dev.ledgerline.ingest.CanonicalCsv

/** Builders for canonical CSV text used by L-01 attack tests. */
object CsvRows {
    /** U+FEFF built from its code point so formatters cannot strip it from source. */
    val BOM: String = 0xFEFF.toChar().toString()

    val header: String = CanonicalCsv.COLUMNS.joinToString(",")

    /** One BUY row; any column can be overridden by name. Values are written verbatim (caller quotes). */
    fun row(vararg overrides: Pair<String, String>): String {
        val base =
            mutableMapOf(
                "idempotency_key" to "K-1", "taxpayer_id" to "TP-0001", "account_id" to "TP-0001-NR1",
                "account_type" to "NON_REGISTERED", "activity_type" to "BUY", "effective_date" to "2025-02-05",
                "settlement_date" to "", "security_id" to "SYN-AAA", "quantity" to "10", "currency" to "CAD",
                "amount" to "301.25", "commission" to "0.00", "counter_currency" to "", "counter_amount" to "",
                "description" to "",
            )
        overrides.forEach { (k, v) ->
            require(k in base) { "unknown column $k" }
            base[k] = v
        }
        return CanonicalCsv.COLUMNS.joinToString(",") { base.getValue(it) }
    }

    fun file(vararg rows: String): String = header + "\n" + rows.joinToString("") { it + "\n" }
}
