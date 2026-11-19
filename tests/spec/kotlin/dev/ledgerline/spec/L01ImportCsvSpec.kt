package dev.ledgerline.spec

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * Spec for story L-01 (docs/stories/L-01-import-csv.md), written provisionally while the story is Draft.
 *
 * PLACEHOLDERS (OWNER-INPUT): share scale 10^-6 (ADR 0002); parser returns good rows plus rejections together.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.core
 * enum class AccountType { NON_REGISTERED, TFSA, RRSP }
 * enum class Currency { CAD, USD }
 * enum class ActivityType { BUY, SELL, DIVIDEND, RETURN_OF_CAPITAL, FEE, DEPOSIT, WITHDRAWAL, TRANSFER_IN, FX_CONVERSION }
 * data class Activity(
 *     val idempotencyKey: String, val taxpayerId: String, val accountId: String, val accountType: AccountType,
 *     val activityType: ActivityType, val effectiveDate: java.time.LocalDate, val settlementDate: java.time.LocalDate?,
 *     val securityId: String?, val quantity: Long?,          // shares in units of 10^-6 share; null if absent
 *     val currency: Currency, val amountMinor: Long,         // cents
 *     val commissionMinor: Long,                             // cents; 0 when column empty
 *     val counterCurrency: Currency?, val counterAmountMinor: Long?,
 *     val description: String?,                              // null when the field is empty
 * )
 *
 * package dev.ledgerline.ingest
 * data class Rejection(val line: Int, val code: String, val field: String, val message: String) // line: 1-based physical line
 * data class FileError(val code: String, val message: String, val column: String?)              // column: offending header column, if any
 * data class ParseResult(val activities: List<Activity>, val rejections: List<Rejection>, val fileErrors: List<FileError>)
 * object CanonicalCsv {
 *     fun parse(text: String): ParseResult        // text may start with a BOM, use LF or CRLF; never throws on bad content
 *     fun serialize(activities: List<Activity>): String
 *     // serialize: canonical header (the 15 columns of AC1, in order), LF after every line including the last, no BOM;
 *     // quantity = minimal decimal (100000000 -> "100", 500000 -> "0.5"); amount, commission, counter_amount = exactly
 *     // 2 decimals; empty/absent optional values = empty field; commission always written ("0.00"); fields containing
 *     // comma, quote or newline are RFC 4180 quoted.
 * }
 * object SyntheticGenerator {
 *     fun generate(seed: Long, taxpayers: Int, year: Int): String   // canonical CSV text; pure function of its arguments
 * }
 * ```
 *
 * Rejection codes: AMOUNT_TOO_PRECISE, INVALID_DATE, UNKNOWN_ACTIVITY_TYPE, UNSUPPORTED_CURRENCY, MISSING_FIELD,
 * NON_POSITIVE_QUANTITY, SETTLEMENT_BEFORE_EFFECTIVE, QUANTITY_TOO_PRECISE. File error code: HEADER_INVALID.
 * Rejections are returned in line order. A missing/empty file yields HEADER_INVALID with column = null.
 *
 * Not tested here: the Gradle command `:ingest:run --args="generate ..."` (AC7 proposal). Only the generator
 * function is tested.
 */
class L01ImportCsvSpec : FunSpec({

    fun fixture(name: String): String =
        L01ImportCsvSpec::class.java.getResource("/l01/$name")!!.readText(Charsets.UTF_8)

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    val ac1 = fixture("ac1_valid.csv")
    val bom = ""

    fun expectedAc1(description: String? = "Initial deposit") =
        listOf(
            Activity(
                "TP-0001-2025-0001", "TP-0001", "TP-0001-NR1", AccountType.NON_REGISTERED, ActivityType.DEPOSIT,
                LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 6), null, null, Currency.CAD, 1_000_000L, 0L,
                null, null, description,
            ),
            Activity(
                "TP-0001-2025-0002", "TP-0001", "TP-0001-NR1", AccountType.NON_REGISTERED, ActivityType.BUY,
                LocalDate.of(2025, 1, 7), LocalDate.of(2025, 1, 8), "SYN-AAA", 100_000_000L, Currency.CAD, 301_250L,
                495L, null, null, null,
            ),
            Activity(
                "TP-0001-2025-0003", "TP-0001", "TP-0001-NR1", AccountType.NON_REGISTERED, ActivityType.FX_CONVERSION,
                LocalDate.of(2025, 1, 9), LocalDate.of(2025, 1, 9), null, null, Currency.CAD, 138_000L, 0L,
                Currency.USD, 100_000L, null,
            ),
        )

    test("L-01 AC1: valid canonical file parses into canonical activities") {
        val r = CanonicalCsv.parse(ac1)
        r.rejections shouldHaveSize 0
        r.fileErrors shouldHaveSize 0
        r.activities shouldContainExactly expectedAc1()
    }

    test("L-01 AC2: every bad row is rejected with line number and reason code; good rows still returned") {
        val r = CanonicalCsv.parse(fixture("ac2_bad_rows.csv"))
        r.fileErrors shouldHaveSize 0
        r.activities.map { it.idempotencyKey } shouldContainExactly listOf("TP-0001-2025-0018")
        r.rejections.map { Triple(it.line, it.code, it.field) } shouldContainExactly
            listOf(
                Triple(2, "AMOUNT_TOO_PRECISE", "amount"),
                Triple(3, "INVALID_DATE", "effective_date"),
                Triple(4, "UNKNOWN_ACTIVITY_TYPE", "activity_type"),
                Triple(5, "UNSUPPORTED_CURRENCY", "currency"),
                Triple(6, "MISSING_FIELD", "security_id"),
                Triple(7, "NON_POSITIVE_QUANTITY", "quantity"),
                Triple(8, "SETTLEMENT_BEFORE_EFFECTIVE", "settlement_date"),
                Triple(9, "QUANTITY_TOO_PRECISE", "quantity"),
            )
        r.rejections.forEach { it.message.isNotBlank() shouldBe true }
    }

    test("L-01 AC3: wrong header rejects the whole file, naming the missing column") {
        val lines = ac1.lines().toMutableList()
        lines[0] = lines[0].replace(",commission", "")
        val r = CanonicalCsv.parse(lines.joinToString("\n"))
        r.activities shouldHaveSize 0
        r.rejections shouldHaveSize 0
        r.fileErrors shouldHaveSize 1
        r.fileErrors[0].code shouldBe "HEADER_INVALID"
        r.fileErrors[0].column shouldBe "commission"
    }

    test("L-01 AC4: LF, CRLF, BOM and quoted-comma variants parse identically") {
        val crlf = ac1.replace("\n", "\r\n")
        for (variant in listOf(ac1, crlf, bom + ac1)) {
            val r = CanonicalCsv.parse(variant)
            r.rejections shouldHaveSize 0
            r.fileErrors shouldHaveSize 0
            r.activities shouldContainExactly expectedAc1()
        }
        val quoted = ac1.replace(",,Initial deposit", ",,\"Initial deposit, first\"")
        quoted shouldNotBe ac1
        val r = CanonicalCsv.parse(quoted)
        r.rejections shouldHaveSize 0
        r.fileErrors shouldHaveSize 0
        r.activities shouldContainExactly expectedAc1("Initial deposit, first")
    }

    test("L-01 AC5: header-only file is empty success; zero-byte file is HEADER_INVALID") {
        val header = ac1.lines().first()
        for (text in listOf(header, header + "\n")) {
            val a = CanonicalCsv.parse(text)
            a.activities shouldHaveSize 0
            a.rejections shouldHaveSize 0
            a.fileErrors shouldHaveSize 0
        }
        val b = CanonicalCsv.parse("")
        b.activities shouldHaveSize 0
        b.fileErrors.map { it.code } shouldContainExactly listOf("HEADER_INVALID")
    }

    test("L-01 AC6: serialize(parse(file)) is byte-identical for the AC1 file and generated files") {
        val generated = SyntheticGenerator.generate(42L, 5, 2025)
        for (file in listOf(ac1, generated)) {
            val r = CanonicalCsv.parse(file)
            r.rejections shouldHaveSize 0
            r.fileErrors shouldHaveSize 0
            CanonicalCsv.serialize(r.activities) shouldBe file
        }
    }

    test("L-01 AC7: same seed gives byte-identical file; different seed differs") {
        val a = SyntheticGenerator.generate(42L, 5, 2025)
        val b = SyntheticGenerator.generate(42L, 5, 2025)
        val c = SyntheticGenerator.generate(43L, 5, 2025)
        sha256(a) shouldBe sha256(b)
        sha256(c) shouldNotBe sha256(a)
    }

    test("L-01 AC8: generator output does not depend on default locale or time zone") {
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("en-CA"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val first = SyntheticGenerator.generate(42L, 5, 2025)
            Locale.setDefault(Locale.forLanguageTag("fr-CA"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
            val second = SyntheticGenerator.generate(42L, 5, 2025)
            sha256(second) shouldBe sha256(first)
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    test("L-01 AC9: generator output is valid, covers all activity types, unique keys, no oversell") {
        val r = CanonicalCsv.parse(SyntheticGenerator.generate(42L, 5, 2025))
        r.fileErrors shouldHaveSize 0
        r.rejections shouldHaveSize 0
        val acts = r.activities
        acts.map { it.activityType }.toSet() shouldBe ActivityType.entries.toSet()
        acts.all { it.effectiveDate.year == 2025 } shouldBe true
        acts.map { it.idempotencyKey }.toSet() shouldHaveSize acts.size

        val held = mutableMapOf<Pair<String, String?>, Long>()
        acts.withIndex()
            .sortedWith(compareBy({ it.value.effectiveDate }, { it.index }))
            .map { it.value }
            .forEach { a ->
                val k = a.accountId to a.securityId
                when (a.activityType) {
                    ActivityType.BUY, ActivityType.TRANSFER_IN -> held.merge(k, a.quantity!!, Long::plus)
                    ActivityType.SELL -> {
                        val after = (held[k] ?: 0L) - a.quantity!!
                        (after >= 0L) shouldBe true
                        held[k] = after
                    }
                    else -> Unit
                }
            }
    }

    test("L-01 AC10: generator uses only synthetic identifiers") {
        val acts = CanonicalCsv.parse(SyntheticGenerator.generate(42L, 5, 2025)).activities
        (acts.isNotEmpty()) shouldBe true
        acts.forEach { a ->
            a.securityId?.shouldStartWith("SYN-")
            a.taxpayerId shouldMatch Regex("TP-\\d{4}")
            a.accountId shouldStartWith a.taxpayerId
        }
        acts.mapNotNull { it.securityId }.isNotEmpty() shouldBe true
    }
})
