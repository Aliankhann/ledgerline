package dev.ledgerline.spec

import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.testsupport.CsvRows.BOM
import dev.ledgerline.testsupport.CsvRows.file
import dev.ledgerline.testsupport.CsvRows.header
import dev.ledgerline.testsupport.CsvRows.row
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** L-01 phase B attack tests: parser edge cases beyond the story's literal examples. Source: docs/stories/L-01. */
class L01ParserEdgeSpec : FunSpec({
    fun one(vararg o: Pair<String, String>) = CanonicalCsv.parse(file(row(*o)))

    fun amountOf(text: String): Activity? = one("amount" to text).activities.singleOrNull()

    fun rejectedAs(
        code: String,
        vararg o: Pair<String, String>,
    ) {
        val r = one(*o)
        r.activities shouldHaveSize 0
        r.rejections.map { it.code } shouldBe listOf(code)
        r.rejections.single().line shouldBe 2
    }

    test("L-01 edge: decimal amounts 0.1 and leading zeros parse to exact minor units") {
        amountOf("0.1")!!.amountMinor shouldBe 10L
        amountOf("0.10")!!.amountMinor shouldBe 10L
        amountOf("007.50")!!.amountMinor shouldBe 750L
        amountOf("0")!!.amountMinor shouldBe 0L
        amountOf("0.00")!!.amountMinor shouldBe 0L
    }

    test("L-01 edge: malformed numbers are rejected INVALID_NUMBER, never accepted") {
        for (bad in listOf("1e3", "+5", "abc", "1 000", "1_000", "--5", "0x10", "NaN", "Infinity")) {
            rejectedAs("INVALID_NUMBER", "amount" to bad)
        }
        rejectedAs("INVALID_NUMBER", "quantity" to "1e3")
        rejectedAs("INVALID_NUMBER", "quantity" to "+5")
    }

    test("L-01 edge: .5 and 1. never crash and are either exact or rejected INVALID_NUMBER") {
        for ((text, minor) in listOf(".5" to 50L, "1." to 100L)) {
            val r = one("amount" to text)
            if (r.activities.isNotEmpty()) {
                r.activities.single().amountMinor shouldBe minor
            } else {
                r.rejections.single().code shouldBe "INVALID_NUMBER"
            }
        }
    }

    test("L-01 edge: negative amount is rejected; -0 is accepted as zero or rejected, never negative") {
        rejectedAs("NEGATIVE_AMOUNT", "amount" to "-1.00")
        val r = one("amount" to "-0")
        r.activities.forEach { it.amountMinor shouldBe 0L }
        r.activities.size + r.rejections.size shouldBe 1
    }

    test("L-01 edge: huge values never overflow or wrap") {
        // 18 digits fits comfortably
        amountOf("9999999999999999.99")!!.amountMinor shouldBe 999_999_999_999_999_999L
        // Long.MAX_VALUE exactly, and beyond: either exact or rejected, never wrapped negative
        for (text in listOf("92233720368547758.07", "92233720368547758.08", "999999999999999999999.99")) {
            val r = one("amount" to text)
            r.activities.forEach { (it.amountMinor > 0L) shouldBe true }
            r.activities.size + r.rejections.size shouldBe 1
        }
        rejectedAs("INVALID_NUMBER", "amount" to "999999999999999999999.99")
        rejectedAs("INVALID_NUMBER", "quantity" to "99999999999999999999")
        rejectedAs("INVALID_NUMBER", "commission" to "99999999999999999999")
    }

    test("L-01 edge: quantity boundaries at scale 10^-6") {
        one("quantity" to "0.000001").activities.single().quantity shouldBe 1L
        one("quantity" to "000100.5").activities.single().quantity shouldBe 100_500_000L
        rejectedAs("QUANTITY_TOO_PRECISE", "quantity" to "0.0000001")
        rejectedAs("NON_POSITIVE_QUANTITY", "quantity" to "0")
        rejectedAs("NON_POSITIVE_QUANTITY", "quantity" to "0.000000")
        rejectedAs("NON_POSITIVE_QUANTITY", "quantity" to "-0")
    }

    test("L-01 edge: tiny amount 0.01 and commission precision") {
        amountOf("0.01")!!.amountMinor shouldBe 1L
        rejectedAs("COMMISSION_TOO_PRECISE", "commission" to "0.001")
    }

    test("L-01 edge: quoted field with embedded quotes, commas and newline round trips and keeps later line numbers") {
        val desc = "say \"hi\",\nline two"
        val quoted = "\"say \"\"hi\"\",\nline two\""
        val text = file(row("description" to quoted), row("idempotency_key" to "K-2", "amount" to "bad"))
        val r = CanonicalCsv.parse(text)
        r.activities.single().description shouldBe desc
        // header=1, row1 spans lines 2-3, row2 starts at line 4
        r.rejections.single().line shouldBe 4
    }

    test("L-01 edge: CRLF inside a quoted field is preserved") {
        val r = one("description" to "\"a\r\nb\"")
        r.activities.single().description shouldBe "a\r\nb"
    }

    test("L-01 edge: description with leading/trailing spaces is not altered") {
        val r = one("description" to "\"  padded  \"")
        r.activities.single().description shouldBe "  padded  "
    }

    test("L-01 edge: trailing extra empty column is rejected WRONG_COLUMN_COUNT on that line") {
        val text = file(row(), row("idempotency_key" to "K-2") + ",", row("idempotency_key" to "K-3"))
        val r = CanonicalCsv.parse(text)
        r.activities shouldHaveSize 2
        r.rejections.map { it.line to it.code } shouldBe listOf(3 to "WRONG_COLUMN_COUNT")
    }

    test("L-01 edge: too few columns is rejected WRONG_COLUMN_COUNT") {
        val r = CanonicalCsv.parse(header + "\nK-1,TP-0001\n")
        r.rejections.map { it.line to it.code } shouldBe listOf(2 to "WRONG_COLUMN_COUNT")
    }

    test("L-01 edge: missing final newline parses the last row, with and without CRLF") {
        val text = file(row(), row("idempotency_key" to "K-2"))
        CanonicalCsv.parse(text.trimEnd('\n')).activities shouldHaveSize 2
        CanonicalCsv.parse(text.replace("\n", "\r\n").trimEnd('\r', '\n')).activities shouldHaveSize 2
        val r = CanonicalCsv.parse(text.trimEnd('\n') + "\n\n\n")
        r.activities shouldHaveSize 2
        r.rejections shouldHaveSize 0
    }

    test("L-01 edge: last row ending in empty quoted field and no newline") {
        val r = CanonicalCsv.parse(file(row()).trimEnd('\n').dropLast(0) + "")
        r.activities shouldHaveSize 1
        CanonicalCsv.parse(header + "\n" + row("description" to "\"\"")).activities shouldHaveSize 1
    }

    test("L-01 edge: blank line between rows is skipped and later line numbers stay physical") {
        val text = header + "\n" + row() + "\n\n" + row("idempotency_key" to "K-2", "amount" to "x") + "\n"
        val r = CanonicalCsv.parse(text)
        r.rejections.single().line shouldBe 4
    }

    test("L-01 edge: duplicate header name replacing a required column is HEADER_INVALID") {
        val dup = header.replace("commission", "amount")
        val r = CanonicalCsv.parse(dup + "\n" + row() + "\n")
        r.activities shouldHaveSize 0
        r.fileErrors.map { it.code } shouldBe listOf("HEADER_INVALID")
        r.fileErrors.single().column shouldBe "commission"
    }

    test("L-01 edge: duplicate header name appended is HEADER_INVALID and no rows are parsed") {
        val r = CanonicalCsv.parse("$header,amount\n" + row() + ",1\n")
        r.activities shouldHaveSize 0
        r.rejections shouldHaveSize 0
        r.fileErrors.map { it.code } shouldBe listOf("HEADER_INVALID")
    }

    test("L-01 edge: BOM is ignored for every variant and a BOM-only file is HEADER_INVALID") {
        val bom = BOM
        val text = file(row())
        CanonicalCsv.parse(bom + text).activities shouldHaveSize 1
        CanonicalCsv.parse(bom + text).fileErrors shouldHaveSize 0
        CanonicalCsv.parse(bom + text.replace("\n", "\r\n")).activities shouldHaveSize 1
        CanonicalCsv.parse(bom + header + "\n").let {
            it.activities shouldHaveSize 0
            it.fileErrors shouldHaveSize 0
        }
        CanonicalCsv.parse(bom).fileErrors.map { it.code } shouldBe listOf("HEADER_INVALID")
    }
})
