package dev.ledgerline.testsupport

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ledger.PayloadHash
import java.time.LocalDate

/**
 * Prints the payload hashes of a fixed set of activities, one per line. Run in a separate JVM with other locale,
 * time zone and charset settings (L-02 attack: the hash must be identical on any machine, invariant 5).
 * The same function runs in-process for the reference value.
 */
object HashProbe {
    /** Parsed from CSV text, so the parser's locale handling is covered too. */
    private val csvRow =
        CsvRows.file(
            CsvRows.row(
                "idempotency_key" to "PROBE-1",
                "amount" to "3012.50",
                "quantity" to "100.5",
                "security_id" to "SYN-AAA",
            ),
            CsvRows.row("idempotency_key" to "PROBE-2", "activity_type" to "SELL", "account_type" to "RRSP"),
            CsvRows.row(
                "idempotency_key" to "PROBE-3",
                "activity_type" to "FX_CONVERSION",
                "security_id" to "",
                "quantity" to "",
                "amount" to "1000.00",
                "counter_currency" to "USD",
                "counter_amount" to "731.07",
            ),
        )

    /** Built directly, with non-ASCII text that a platform charset could mangle. */
    private val direct =
        Activity(
            "PROBE-4", "TP-é中", "ACC-İIıi", AccountType.TFSA, ActivityType.BUY,
            LocalDate.of(2025, 1, 7), LocalDate.of(2025, 1, 8), "SYN-K", 1_000_000L, Currency.CAD, 301_250L, 495L,
            null, null, null,
        )

    fun lines(): List<String> = (CanonicalCsv.parse(csvRow).activities + direct).map { PayloadHash.of(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        lines().forEach { println(it) }
    }
}
