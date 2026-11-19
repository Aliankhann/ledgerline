package dev.ledgerline.testsupport

import dev.ledgerline.core.Activity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand (L-02 AC5 needs two submissions at known times). */
class MutableClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

/** Story L-02's base activity A1 as a domain object (docs/stories/L-02-duplicate-safe-ingestion.md). */
object L02Activities {
    val a1: Activity = L03Activities.ac1.copy(settlementDate = LocalDate.of(2025, 1, 8))

    const val KEY = "TP-0001-2025-0002"
}

/** Story L-02's A1 as the JSON request body, with every amount and quantity as a decimal string. */
object L02Json {
    val a1: Map<String, String> =
        linkedMapOf(
            "idempotencyKey" to "TP-0001-2025-0002",
            "taxpayerId" to "TP-0001",
            "accountId" to "TP-0001-NR1",
            "accountType" to "NON_REGISTERED",
            "activityType" to "BUY",
            "effectiveDate" to "2025-01-07",
            "settlementDate" to "2025-01-08",
            "securityId" to "SYN-AAA",
            "quantity" to "100",
            "currency" to "CAD",
            "amount" to "3012.50",
            "commission" to "4.95",
        )

    /** A1 with [overrides] applied (a null value removes the field), rendered compactly or spread over many lines. */
    fun body(
        vararg overrides: Pair<String, String?>,
        reversed: Boolean = false,
        spaced: Boolean = false,
    ): String {
        val fields = LinkedHashMap<String, String?>(a1).apply { putAll(overrides) }
        val entries = fields.entries.filter { it.value != null }.let { if (reversed) it.reversed() else it }
        val pairs = entries.map { "\"${it.key}\"${if (spaced) " : " else ":"}\"${it.value}\"" }
        return if (spaced) pairs.joinToString(" ,\n\t", "  {  \n\t", "  \n}  \n") else pairs.joinToString(",", "{", "}")
    }
}

object Fixtures {
    /** Text of a file under tests/fixtures. */
    fun text(path: String): String =
        requireNotNull(Fixtures::class.java.classLoader.getResource(path)) { "missing fixture $path" }.readText()
}
