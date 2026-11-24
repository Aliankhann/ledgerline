package dev.ledgerline.spec

import dev.ledgerline.app.PreviewStatus
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.L20
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlin.random.Random

/**
 * L-20 upload preview, the classification logic with no database and no HTTP (runs in `./gradlew build`). The story is
 * a Draft; the contract is on [L20] in `tests/support/.../L20.kt`; the HTTP and database criteria are in
 * [L20PreviewHttpSpec] and [L20ConfirmSpec] (tagged Integration). No owner decision is asserted: in-file repeats are
 * not classified here, and the "warnings already in the ledger" case is not tested.
 *
 * AC map: AC1, AC2, AC3 (a, b, c, zero-byte, header-only) and AC4 are here as pure classification; AC3 (d) 413, AC5,
 * AC6 and AC7 need the ledger and are in the integration specs; AC8 is `L20UiGuardSpec`.
 */
class L20UploadPreviewSpec : FunSpec({

    val ac1 = L20.ac1
    val held = L20.stored(ac1) // a ledger that holds exactly the L-01 AC1 file

    fun L20Report.lines() = rows.map { it.line to it.status }

    val dup = PreviewStatus.DUPLICATE
    val new = PreviewStatus.NEW

    test("L-20 AC1: a fresh file is 3 NEW rows on lines 2, 3, 4 with the AC1 keys and nothing else") {
        val r = L20.classify(ac1)
        r.counts() shouldBe listOf(3, 3, 0, 0, 0)
        r.warnings.shouldBeEmpty()
        r.fileErrors.shouldBeEmpty()
        r.rows.map { it.line } shouldContainExactly listOf(2, 3, 4)
        r.rows.map { it.key } shouldContainExactly L20.ac1Keys
        r.rows.map { it.status } shouldContainExactly List(3) { PreviewStatus.NEW }
        r.rows.forEach {
            it.code shouldBe null
            (it.type != null && it.date != null) shouldBe true
        }
        r.rows.map { it.type } shouldContainExactly listOf("DEPOSIT", "BUY", "FX_CONVERSION")
        r.rows.map { it.date } shouldContainExactly listOf("2025-01-06", "2025-01-07", "2025-01-09")
    }

    test("L-20 AC1: fileSha256 is the SHA-256 of the file bytes, 64 lowercase hex, and changes with one byte") {
        val r = L20.classify(ac1)
        r.fileSha256 shouldMatch Regex("[0-9a-f]{64}")
        r.fileSha256 shouldBe L20.sha256(ac1)
        val edited = ac1.replace("3012.50", "3012.51")
        L20.classify(edited).fileSha256 shouldBe L20.sha256(edited)
        (L20.classify(edited).fileSha256 == r.fileSha256) shouldBe false
    }

    test("L-20 AC2: the same file against a ledger that holds it is 3 DUPLICATE, never NEW") {
        val r = L20.classify(ac1, held)
        r.counts() shouldBe listOf(3, 0, 3, 0, 0)
        r.lines() shouldContainExactly listOf(2 to dup, 3 to dup, 4 to dup)
    }

    test("L-20 AC2: an overlapping next-year file has 2 DUPLICATE rows and the 2026 row NEW on line 4") {
        val overlap = L20.file(L20.ac1Rows[0], L20.ac1Rows[1], L20.row2026)
        val r = L20.classify(overlap, held)
        r.counts() shouldBe listOf(3, 1, 2, 0, 0)
        r.lines() shouldContainExactly listOf(2 to dup, 3 to dup, 4 to new)
        r.rows.last().key shouldBe "TP-0001-2026-0001"
    }

    test("L-20 AC2 (L-02 C20): a row that differs from its stored twin only in description is a DUPLICATE") {
        val r = L20.classify(ac1.replace("Initial deposit", "Opening cash, retyped"), held)
        r.duplicates shouldBe 3
        r.new shouldBe 0
        r.conflicts shouldBe 0
    }

    test("L-20 AC3 (a): line 3 with amount 3021.50 is a CONFLICT with the mismatch code; the other 2 are DUPLICATE") {
        val r = L20.classify(ac1.replace("3012.50", "3021.50"), held)
        r.counts() shouldBe listOf(3, 0, 2, 1, 0)
        val conflict = r.rows.single { it.status == PreviewStatus.CONFLICT }
        conflict.line shouldBe 3
        conflict.key shouldBe "TP-0001-2025-0002"
        conflict.code shouldBe L20.CONFLICT_CODE
        (conflict.message?.isNotBlank() == true) shouldBe true
    }

    test("L-20 AC3 (b): the L-01 AC2 file gives 8 REJECTED rows with the L-01 table and 1 NEW row on line 10") {
        val r = L20.classify(L20.ac2Bad, held)
        r.counts() shouldBe listOf(9, 1, 0, 0, 8)
        r.fileErrors.shouldBeEmpty()
        val rejected = r.rows.filter { it.status == PreviewStatus.REJECTED }
        rejected.map { Triple(it.line, it.code, it.field) } shouldContainExactly
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
        rejected.forEach { (it.message?.isNotBlank() == true) shouldBe true }
        r.rows.single { it.status == PreviewStatus.NEW }.let {
            it.line shouldBe 10
            it.key shouldBe "TP-0001-2025-0018"
        }
        r.rows.map { it.line } shouldContainExactly (2..10).toList()
    }

    test(
        "L-20 AC3 (c): a file with the commission header removed has one HEADER_INVALID naming commission and no rows",
    ) {
        val lines = ac1.lines().toMutableList()
        lines[0] = lines[0].replace(",commission", "")
        val r = L20.classify(lines.joinToString("\n"), held)
        r.counts() shouldBe listOf(0, 0, 0, 0, 0)
        r.rows.shouldBeEmpty()
        r.fileErrors.map { it.code } shouldContainExactly listOf("HEADER_INVALID")
        r.fileErrors.single().column shouldBe "commission"
    }

    test("L-20 AC3: a zero-byte file is HEADER_INVALID and a header-only file has all counts 0 and no file error") {
        val empty = L20.classify("")
        empty.fileErrors.map { it.code } shouldContainExactly listOf("HEADER_INVALID")
        empty.counts() shouldBe listOf(0, 0, 0, 0, 0)
        val headerOnly = L20.classify(CsvRows.header + "\n")
        headerOnly.fileErrors.shouldBeEmpty()
        headerOnly.counts() shouldBe
            listOf(0, 0, 0, 0, 0)
        headerOnly.rows.shouldBeEmpty()
        headerOnly.warnings.shouldBeEmpty()
    }

    test("L-20 AC4: SELL with no earlier buy and TRANSFER_IN with no cost are warned by rule, security and file line") {
        val file =
            L20.file(
                L20.row("W-1", "activity_type" to "SELL", "quantity" to "5", "amount" to "60.00"),
                L20.row(
                    "W-2",
                    "activity_type" to "TRANSFER_IN",
                    "security_id" to "SYN-BBB",
                    "quantity" to "100",
                    "amount" to "0.00",
                ),
            )
        val r = L20.classify(file)
        r.counts() shouldBe listOf(2, 2, 0, 0, 0)
        r.warnings.map { Triple(it.ruleId, it.securityId, it.lines) }.toSet() shouldBe
            setOf(
                Triple(L20.OVERSELL, "SYN-AAA", listOf(2)),
                Triple(L20.NO_COST, "SYN-BBB", listOf(3)),
            )
        r.warnings.size shouldBe 2
        r.warnings.forEach {
            it.amountMinor shouldBe 0L
            it.text.isNotBlank() shouldBe true
        }
    }

    test("L-20 AC4: a clean file (BUY 10 then SELL 4, the L-16 AC4 data) has an empty warning list") {
        val file =
            L20.file(
                L20.row("C-1", "quantity" to "10", "amount" to "100.00"),
                L20.row(
                    "C-2",
                    "activity_type" to "SELL",
                    "quantity" to "4",
                    "effective_date" to "2025-02-06",
                    "amount" to "50.00",
                ),
            )
        val r = L20.classify(file)
        r.new shouldBe 2
        r.warnings.shouldBeEmpty()
    }

    test("L-20 AC4: the sell that comes first by effective date, not first in the file, decides the warning") {
        // The BUY is on line 2 but dated after the SELL on line 3: the sale exceeds the shares held at its date.
        val file =
            L20.file(
                L20.row("O-1", "quantity" to "10", "effective_date" to "2025-03-10", "amount" to "100.00"),
                L20.row(
                    "O-2",
                    "activity_type" to "SELL",
                    "quantity" to "4",
                    "effective_date" to "2025-03-01",
                    "amount" to "50.00",
                ),
            )
        val r = L20.classify(file)
        r.warnings.map { it.ruleId to it.lines } shouldContainExactly listOf(L20.OVERSELL to listOf(3))
    }

    test("L-20 AC1 and AC3: physical line numbers survive a multi-line quoted field, CRLF and a BOM") {
        val multi = "\"first\nsecond\""
        val text =
            L20.file(
                L20.row("L-1", "description" to multi),
                L20.row("L-2"),
                L20.row("L-3", "currency" to "EUR"),
            )
        val r = L20.classify(text)
        // header = line 1, row 1 spans lines 2-3, so rows start at lines 2, 4, 5.
        r.lines() shouldContainExactly listOf(2 to new, 4 to new, 5 to PreviewStatus.REJECTED)
        val plain = L20.file(L20.row("L-1"), L20.row("L-2"), L20.row("L-3", "currency" to "EUR"))
        val expected = L20.classify(plain).lines()
        L20.classify(plain.replace("\n", "\r\n")).lines() shouldContainExactly expected
        L20.classify(CsvRows.BOM + plain).lines() shouldContainExactly expected
    }

    test("L-20 AC5 (pure part): the same input gives an equal report, and the stored lookup is only read") {
        val calls = mutableListOf<String>()
        val first =
            dev.ledgerline.app.ImportPreview.classify(ac1, {
                calls += it
                held[it]
            })
        val second = dev.ledgerline.app.ImportPreview.classify(ac1, { held[it] })
        first shouldBe second
        L20.classify(ac1, held) shouldBe L20.classify(ac1, held)
        // One lookup per good row at most; nothing else is asked for.
        (calls.toSet() - L20.ac1Keys.toSet()).shouldBeEmpty()
    }

    test("L-20 AC2 and AC3: counts add up and each row has its kind, for 150 seeded random files") {
        val rnd = Random(20)
        val storedRows = (1..5).associate { "S$it" to L20.row("S$it") }
        val stored = L20.stored(L20.file(*storedRows.values.toTypedArray()))
        repeat(150) { round ->
            // kinds: N new, D duplicate of a stored row, C conflict with a stored row, R rejected (bad currency)
            val kinds = List(rnd.nextInt(0, 13)) { "NDCR"[rnd.nextInt(4)] }
            var fresh = 0
            val all =
                kinds.map { k ->
                    when (k) {
                        'N' -> "N$round-${fresh++}".let { Triple(k, it, L20.row(it)) }
                        'D' -> "S${rnd.nextInt(1, 6)}".let { Triple(k, it, storedRows.getValue(it)) }
                        'C' -> "S${rnd.nextInt(1, 6)}".let { Triple(k, it, L20.row(it, "amount" to "9.99")) }
                        else -> "R$round-${fresh++}".let { Triple(k, it, L20.row(it, "currency" to "EUR")) }
                    }
                }
            // A key is not repeated inside one file here, so no in-file repeat rule is involved.
            val usable = all.distinctBy { it.second }
            val file = L20.file(*usable.map { it.third }.toTypedArray())
            val r = L20.classify(file, stored)
            r.rowsRead shouldBe usable.size
            (r.new + r.duplicates + r.conflicts + r.rejected) shouldBe r.rowsRead
            r.rows.map { it.line } shouldContainExactly (2 until 2 + usable.size).toList()
            r.rows.zip(usable).forEach { (row, ku) ->
                val want =
                    when (ku.first) {
                        'N' -> PreviewStatus.NEW
                        'D' -> PreviewStatus.DUPLICATE
                        'C' -> PreviewStatus.CONFLICT
                        else -> PreviewStatus.REJECTED
                    }
                row.status shouldBe want
                if (want == PreviewStatus.CONFLICT) row.code shouldBe L20.CONFLICT_CODE
                if (want == PreviewStatus.REJECTED) row.code shouldBe "UNSUPPORTED_CURRENCY"
            }
            // Row order does not change what a key is: invariant 6 for the classification.
            val shuffled = L20.classify(L20.file(*usable.map { it.third }.shuffled(rnd).toTypedArray()), stored)

            fun byKey(rep: L20Report) = rep.rows.filter { it.key != null }.associate { it.key to it.status }
            byKey(shuffled) shouldBe byKey(r)
            shuffled.rows.groupingBy { it.status }.eachCount() shouldBe r.rows.groupingBy { it.status }.eachCount()
        }
    }
})

private typealias L20Report = dev.ledgerline.app.PreviewReport

private fun L20Report.counts() = listOf(rowsRead, new, duplicates, conflicts, rejected)
