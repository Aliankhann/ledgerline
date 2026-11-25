package dev.ledgerline.spec

import dev.ledgerline.app.ImportPreview
import dev.ledgerline.app.PreviewReport
import dev.ledgerline.app.PreviewStatus
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ingest.BrokerFormat
import dev.ledgerline.ingest.BrokerFormats
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.ImportDefaults
import dev.ledgerline.ledger.PayloadHash
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.Fixtures
import dev.ledgerline.testsupport.L19
import dev.ledgerline.testsupport.L19.normalized
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import java.time.LocalDate

/**
 * L-19 broker adapters, AC1, AC2, AC3 and AC5 as pure classification and parsing (no database, no HTTP; runs in
 * `./gradlew build`). AC4 is `L19CrossFormatPropertyTest`; AC6 is `L19UiGuardSpec`. The API contract for
 * Dev is on `L19` in `tests/support/.../L19.kt`. Not covered here: the HTTP query parameters and the `format` field of
 * the JSON body (an integration spec can follow once the route exists).
 */
class L19BrokerAdapterSpec : FunSpec({

    val d = L19.defaults
    val ac1 = CanonicalCsv.parse(Fixtures19.ac1).activities

    fun PreviewReport.counts() = listOf(rowsRead, new, duplicates, conflicts, rejected)

    fun classifyB(
        csv: String,
        stored: Map<String, String> = emptyMap(),
    ) = ImportPreview.classify(csv, { stored[it] }, emptyList(), d)

    fun storedOf(csv: String): Map<String, String> =
        BrokerFormats.parse(csv, d).activities.associate { it.idempotencyKey to PayloadHash.of(it) }

    // ---------------- AC1 detection ----------------

    test("L-19 AC1: the canonical header is detected as CANONICAL and the Broker B header as BROKER_B") {
        BrokerFormats.detect(L19.CANONICAL_HEADER) shouldBe BrokerFormat.CANONICAL
        BrokerFormats.detect(L19.BROKER_B_HEADER) shouldBe BrokerFormat.BROKER_B
        BrokerFormats.BROKER_B_COLUMNS.joinToString(",") shouldBe L19.BROKER_B_HEADER
    }

    test("L-19 AC1: a BOM, a trailing CR and a trailing space do not change the detection") {
        BrokerFormats.detect(CsvRows.BOM + L19.BROKER_B_HEADER) shouldBe BrokerFormat.BROKER_B
        BrokerFormats.detect(L19.BROKER_B_HEADER + "\r") shouldBe BrokerFormat.BROKER_B
        BrokerFormats.detect(CsvRows.BOM + L19.CANONICAL_HEADER + "\r") shouldBe BrokerFormat.CANONICAL
    }

    test("L-19 AC1: an unknown, reordered, truncated, empty or data-row first line detects as null") {
        val cols = L19.BROKER_B_HEADER.split(",")
        listOf(
            "",
            "a,b,c",
            cols.reversed().joinToString(","),
            cols.dropLast(1).joinToString(","),
            cols.joinToString(",") + ",Extra",
            L19.BROKER_B_HEADER.lowercase(),
            L19.row(),
        ).forEach { BrokerFormats.detect(it) shouldBe null }
    }

    test("L-19 AC1: the preview reports CANONICAL for the L-01 AC1 file and BROKER_B for a Broker B file") {
        ImportPreview.classify(Fixtures19.ac1, { null }).format shouldBe BrokerFormat.CANONICAL
        val r = classifyB(L19.ac1LikeFile)
        r.format shouldBe BrokerFormat.BROKER_B
        r.fileErrors.shouldBeEmpty()
        r.counts() shouldBe listOf(2, 2, 0, 0, 0)
    }

    test("L-19 AC1: an unknown header is a file error that names the expected header of each format; no rows") {
        val text = "date,thing,amount\n2025-01-01,x,1.00\n"
        val r = ImportPreview.classify(text, { null }, emptyList(), d)
        r.format shouldBe null
        r.rows.shouldBeEmpty()
        r.counts() shouldBe listOf(0, 0, 0, 0, 0)
        r.fileErrors shouldHaveSize 1
        r.fileErrors.single().code shouldBe "HEADER_INVALID"
        r.fileErrors.single().message shouldContain L19.CANONICAL_HEADER
        r.fileErrors.single().message shouldContain L19.BROKER_B_HEADER
        val parsed = BrokerFormats.parse(text, d)
        parsed.activities.shouldBeEmpty()
        parsed.rejections.shouldBeEmpty()
        parsed.fileErrors.single().message shouldContain L19.BROKER_B_HEADER
    }

    test("L-19 AC1: a zero-byte file is an unknown header, not a crash") {
        val r = ImportPreview.classify("", { null }, emptyList(), d)
        r.format shouldBe null
        r.fileErrors.map { it.code } shouldContainExactly listOf("HEADER_INVALID")
    }

    test("L-19 AC1: a Broker B file without taxpayer and account type is a file error, nothing is read") {
        val r = ImportPreview.classify(L19.ac1LikeFile, { null })
        r.format shouldBe BrokerFormat.BROKER_B
        r.fileErrors.isNotEmpty() shouldBe true
        r.new shouldBe 0
        BrokerFormats.parse(L19.ac1LikeFile, null).activities.shouldBeEmpty()
    }

    test("L-19 AC1: the canonical path is unchanged; defaults are ignored for a canonical file") {
        val other = ImportDefaults("TP-9999", AccountType.TFSA)
        BrokerFormats.parse(Fixtures19.ac1, other).activities shouldBe ac1
        ImportPreview.classify(Fixtures19.ac1, { null }, emptyList(), other).counts() shouldBe listOf(3, 3, 0, 0, 0)
    }

    // ---------------- AC2 mapping ----------------

    test("L-19 AC2: the deposit and the buy map to the AC1 activities, key aside") {
        val r = BrokerFormats.parse(L19.ac1LikeFile, d)
        r.rejections.shouldBeEmpty()
        r.fileErrors.shouldBeEmpty()
        r.activities shouldHaveSize 2
        r.activities.map { it.normalized() } shouldContainExactly ac1.take(2).map { it.normalized() }
        val buy = r.activities[1]
        buy.effectiveDate shouldBe LocalDate.of(2025, 1, 7)
        buy.settlementDate shouldBe LocalDate.of(2025, 1, 8)
        buy.quantity shouldBe 100_000_000L
        buy.amountMinor shouldBe 301_250L
        buy.commissionMinor shouldBe 495L
        buy.currency shouldBe Currency.CAD
        buy.taxpayerId shouldBe "TP-0001"
        buy.accountType shouldBe AccountType.NON_REGISTERED
    }

    test("L-19 AC2: the key is BROKERB:<Account>:<Reference>") {
        BrokerFormats.parse(L19.ac1LikeFile, d).activities.map { it.idempotencyKey } shouldContainExactly
            listOf("BROKERB:TP-0001-NR1:R1", "BROKERB:TP-0001-NR1:R2")
        BrokerFormats.parse(L19.file(L19.row("Account" to "ACC-9", "Reference" to "T-77")), d)
            .activities.single().idempotencyKey shouldBe "BROKERB:ACC-9:T-77"
    }

    test("L-19 AC2: the taxpayer and the account type come from the upload, the account id from the Account column") {
        val a =
            BrokerFormats.parse(L19.file(L19.row("Account" to "ACC-9")), ImportDefaults("TP-0042", AccountType.TFSA))
                .activities.single()
        a.taxpayerId shouldBe "TP-0042"
        a.accountType shouldBe AccountType.TFSA
        a.accountId shouldBe "ACC-9"
    }

    test("L-19 AC2: each of the six actions maps to its activity type") {
        val cases =
            listOf(
                Triple("Buy", ActivityType.BUY, row("Buy")),
                Triple("Sell", ActivityType.SELL, row("Sell", "Quantity" to "-100")),
                Triple("Deposit", ActivityType.DEPOSIT, row("Deposit", "Symbol" to "", "Quantity" to "")),
                Triple("Withdrawal", ActivityType.WITHDRAWAL, row("Withdrawal", "Symbol" to "", "Quantity" to "")),
                Triple("Transfer In", ActivityType.TRANSFER_IN, row("Transfer In")),
                Triple("Dividend", ActivityType.DIVIDEND, row("Dividend", "Quantity" to "")),
            )
        cases.forEachIndexed { i, (name, type, line) ->
            val r = BrokerFormats.parse(L19.file(line.replace("R2", "X$i")), d)
            r.rejections.shouldBeEmpty()
            r.activities.single().activityType shouldBe type
            name.isNotBlank() shouldBe true
        }
    }

    test("L-19 AC2: a sell's negative quantity becomes its absolute value; the sell is a SELL of 40 shares") {
        val a = BrokerFormats.parse(L19.file(L19.row("Action" to "Sell", "Quantity" to "-40")), d).activities.single()
        a.activityType shouldBe ActivityType.SELL
        a.quantity shouldBe 40_000_000L
    }

    test("L-19 AC2: fractional quantity keeps six decimals: -0.000001 is one unit") {
        val a =
            BrokerFormats.parse(L19.file(L19.row("Action" to "Sell", "Quantity" to "-0.000001")), d).activities.single()
        a.quantity shouldBe 1L
    }

    test("L-19 AC2: the dollar sign and thousands commas are stripped from amounts and fees; no float rounding") {
        val cases =
            listOf(
                "\$1,234,567.89" to 123_456_789L,
                "\$0.01" to 1L,
                "12.50" to 1_250L,
                "1,000.00" to 100_000L,
                "\$90,071,992,547,409.93" to 9_007_199_254_740_993L,
            )
        cases.forEachIndexed { i, (cell, minor) ->
            val a =
                BrokerFormats.parse(
                    L19.file(L19.row("Gross Amount" to "\"$cell\"", "Fees" to "\"\$1,000.05\"", "Reference" to "A$i")),
                    d,
                ).activities.single()
            a.amountMinor shouldBe minor
            a.commissionMinor shouldBe 100_005L
        }
    }

    test("L-19 AC2: Fees is the commission; empty Fees is zero commission") {
        BrokerFormats.parse(L19.file(L19.row("Fees" to "")), d).activities.single().commissionMinor shouldBe 0L
        BrokerFormats.parse(L19.file(L19.row("Fees" to "4.95")), d).activities.single().commissionMinor shouldBe 495L
    }

    test("L-19 AC2: Currency is kept: a USD row stays USD") {
        val a = BrokerFormats.parse(L19.file(L19.row("Currency" to "USD")), d).activities.single()
        a.currency shouldBe Currency.USD
    }

    test("L-19 AC2: dates are MM/DD/YYYY: 01/02/2025 is 2 January, 12/31/2025 is 31 December, 02/29/2024 exists") {
        fun date(cell: String) =
            BrokerFormats.parse(L19.file(L19.row("Trade Date" to cell, "Settle Date" to "")), d)
                .activities.single().effectiveDate
        date("01/02/2025") shouldBe LocalDate.of(2025, 1, 2)
        date("12/31/2025") shouldBe LocalDate.of(2025, 12, 31)
        date("02/29/2024") shouldBe LocalDate.of(2024, 2, 29)
    }

    test("L-19 AC2: one row, one activity, in file order; a header-only Broker B file is an empty success") {
        val rows = (1..5).map { L19.row("Reference" to "N$it") }
        BrokerFormats.parse(L19.file(*rows.toTypedArray()), d).activities.map { it.idempotencyKey } shouldContainExactly
            (1..5).map { "BROKERB:TP-0001-NR1:N$it" }
        val empty = BrokerFormats.parse(L19.BROKER_B_HEADER + "\n", d)
        empty.activities.shouldBeEmpty()
        empty.rejections.shouldBeEmpty()
        empty.fileErrors.shouldBeEmpty()
    }

    test("L-19 AC2: LF, CRLF and BOM variants of a Broker B file parse identically") {
        val base = BrokerFormats.parse(L19.ac1LikeFile, d).activities
        listOf(L19.ac1LikeFile.replace("\n", "\r\n"), CsvRows.BOM + L19.ac1LikeFile).forEach {
            BrokerFormats.parse(it, d).activities shouldBe base
        }
    }

    // ---------------- AC3 row errors ----------------

    fun oneBad(
        column: String,
        value: String,
    ) = BrokerFormats.parse(L19.file(L19.depositRow, L19.row(column to value), L19.row("Reference" to "OK")), d)

    test("L-19 AC3: a bad trade date is rejected on its line, naming Trade Date; the other rows are read") {
        listOf("2025-01-07", "13/01/2025", "02/29/2025", "01/32/2025", "1/7/25", "garbage").forEach { bad ->
            val r = oneBad("Trade Date", bad)
            r.rejections.map { Triple(it.line, it.code, it.field) } shouldContainExactly
                listOf(Triple(3, "INVALID_DATE", "Trade Date"))
            r.activities.map { it.idempotencyKey } shouldContainExactly
                listOf("BROKERB:TP-0001-NR1:R1", "BROKERB:TP-0001-NR1:OK")
        }
    }

    test("L-19 AC3: a bad settle date names Settle Date") {
        val r = oneBad("Settle Date", "31/12/2025")
        r.rejections.map { Triple(it.line, it.code, it.field) } shouldContainExactly
            listOf(Triple(3, "INVALID_DATE", "Settle Date"))
        r.activities shouldHaveSize 2
    }

    test("L-19 AC3: an unknown action is rejected naming Action") {
        listOf("Short", "buy", "Transfer Out", "").forEach { bad ->
            val r = oneBad("Action", bad)
            r.rejections.map { it.line to it.field } shouldContainExactly listOf(3 to "Action")
            if (bad.isNotEmpty()) r.rejections.single().code shouldBe "UNKNOWN_ACTIVITY_TYPE"
            r.activities shouldHaveSize 2
        }
    }

    test("L-19 AC3: a sell with a positive quantity is rejected naming Quantity, with a reason") {
        val r = oneBad("Action", "Sell")
        r.rejections.map { it.line to it.field } shouldContainExactly listOf(3 to "Quantity")
        r.rejections.single().code.shouldNotBeBlank()
        r.rejections.single().message.shouldNotBeBlank()
        r.activities shouldHaveSize 2
    }

    test("L-19 AC3: a missing reference is rejected naming Reference; no key is made") {
        val r = oneBad("Reference", "")
        r.rejections.map { Triple(it.line, it.code, it.field) } shouldContainExactly
            listOf(Triple(3, "MISSING_FIELD", "Reference"))
        r.activities.map { it.idempotencyKey } shouldContainExactly
            listOf("BROKERB:TP-0001-NR1:R1", "BROKERB:TP-0001-NR1:OK")
    }

    test("L-19 AC3: four bad rows among good ones are all reported with their lines and the good rows are all kept") {
        val csv =
            L19.file(
                L19.row("Reference" to "G1"),
                L19.row("Reference" to "B1", "Trade Date" to "99/99/2025"),
                L19.row("Reference" to "G2"),
                L19.row("Reference" to "B2", "Action" to "Swap"),
                L19.row("Reference" to "B3", "Action" to "Sell", "Quantity" to "5"),
                L19.row("Reference" to ""),
                L19.row("Reference" to "G3"),
            )
        val r = BrokerFormats.parse(csv, d)
        r.rejections.map { it.line to it.field } shouldContainExactly
            listOf(3 to "Trade Date", 5 to "Action", 6 to "Quantity", 7 to "Reference")
        r.activities.map { it.idempotencyKey.substringAfterLast(':') } shouldContainExactly listOf("G1", "G2", "G3")
        val p = classifyB(csv)
        p.counts() shouldBe listOf(7, 3, 0, 0, 4)
        p.rows.map { it.line to it.status } shouldContainExactly
            listOf(
                2 to PreviewStatus.NEW, 3 to PreviewStatus.REJECTED, 4 to PreviewStatus.NEW,
                5 to PreviewStatus.REJECTED, 6 to PreviewStatus.REJECTED, 7 to PreviewStatus.REJECTED,
                8 to PreviewStatus.NEW,
            )
        p.rows.filter { it.status == PreviewStatus.REJECTED }.map { it.field } shouldContainExactly
            listOf("Trade Date", "Action", "Quantity", "Reference")
        p.fileErrors.shouldBeEmpty()
    }

    test("L-19 AC3: a quoted memo that spans two lines moves the next row's line number by the physical lines") {
        val csv =
            L19.file(
                L19.row("Reference" to "G1", "Memo" to "\"two\nlines\""),
                L19.row("Reference" to "B1", "Trade Date" to "nope"),
            )
        val r = BrokerFormats.parse(csv, d)
        r.activities shouldHaveSize 1
        r.rejections.map { it.line to it.field } shouldContainExactly listOf(4 to "Trade Date")
    }

    test("L-19 AC3: a row with the wrong number of columns is rejected on its line and does not stop the file") {
        val r =
            BrokerFormats.parse(
                L19.file(L19.row("Reference" to "G1"), "01/07/2025,only,three", L19.row("Reference" to "G2")),
                d,
            )
        r.rejections.map { it.line } shouldContainExactly listOf(3)
        r.activities shouldHaveSize 2
    }

    // ---------------- AC5 idempotency across formats ----------------

    val multi =
        L19.file(
            L19.depositRow,
            L19.row(),
            L19.row("Reference" to "R3", "Trade Date" to "01/10/2025", "Settle Date" to "01/13/2025"),
            L19.row(
                "Reference" to "R4",
                "Trade Date" to "01/14/2025",
                "Settle Date" to "",
                "Action" to "Sell",
                "Quantity" to "-30",
                "Gross Amount" to "\$1,200.00",
            ),
        )

    test("L-19 AC5: a Broker B file against a ledger that holds it is all DUPLICATE; against an empty one all NEW") {
        val held = storedOf(multi)
        held.keys shouldHaveSize 4
        val again = classifyB(multi, held)
        again.counts() shouldBe listOf(4, 0, 4, 0, 0)
        again.rows.map { it.status }.toSet() shouldBe setOf(PreviewStatus.DUPLICATE)
        classifyB(multi).counts() shouldBe listOf(4, 4, 0, 0, 0)
    }

    test("L-19 AC5: changing one row's amount is a CONFLICT on that row only, with the mismatch code") {
        val held = storedOf(multi)
        val edited = multi.replace("\$1,200.00", "\$1,201.00")
        val r = classifyB(edited, held)
        r.counts() shouldBe listOf(4, 0, 3, 1, 0)
        r.rows.map { it.line to it.status } shouldContainExactly
            listOf(
                2 to PreviewStatus.DUPLICATE, 3 to PreviewStatus.DUPLICATE, 4 to PreviewStatus.DUPLICATE,
                5 to PreviewStatus.CONFLICT,
            )
        r.rows.last().code shouldBe "IDEMPOTENCY_KEY_MISMATCH"
        r.rows.last().message.shouldNotBeBlank()
    }

    test("L-19 AC5: spelling the same amount differently (\$ and commas) is still a DUPLICATE") {
        val held = storedOf(multi)
        val respelled = multi.replace("\$1,200.00", "1200.00").replace("\$10,000.00", "10000.00")
        classifyB(respelled, held).counts() shouldBe listOf(4, 0, 4, 0, 0)
    }

    test("L-19 AC5: the same reference under a different account is a new key, NEW, not a conflict") {
        val held = storedOf(multi)
        val other = multi.replace("TP-0001-NR1", "TP-0001-NR2")
        classifyB(other, held).counts() shouldBe listOf(4, 4, 0, 0, 0)
    }

    test("L-19 AC5: a Broker B key never collides with a canonical key; the canonical AC1 file stays NEW beside it") {
        val held = storedOf(multi)
        ImportPreview.classify(Fixtures19.ac1, { held[it] }).counts() shouldBe listOf(3, 3, 0, 0, 0)
    }
})

private object Fixtures19 {
    val ac1: String = Fixtures.text("l01/ac1_valid.csv")
}

private fun row(
    action: String,
    vararg overrides: Pair<String, String>,
): String = L19.row("Action" to action, *overrides)
