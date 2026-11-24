package dev.ledgerline.spec

import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Env
import dev.ledgerline.testsupport.L20
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.l02Env
import dev.ledgerline.testsupport.preview
import dev.ledgerline.testsupport.previewBytes
import dev.ledgerline.testsupport.withL20Api
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode

/**
 * L-20 upload preview over HTTP on a real ledger (AC1 to AC4 and the differential check). Every test needs Docker, so
 * every test is tagged Integration and runs only in CI or with `-PincludeIntegration`. The contract is on [L20]; the
 * pure classification is [L20UploadPreviewSpec]. Owner decisions are not asserted (see [L20]).
 */
class L20PreviewHttpSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val tag = setOf(Integration)

    fun L20.Resp.countList() = counts()

    fun L02Env.postAc1() {
        service.importCsv(L20.ac1).let { (it.posted to it.duplicates) shouldBe (3 to 0) }
    }

    test("L-20 AC1: previewing the AC1 file on an empty ledger answers NEW x3 and posts nothing").config(tags = tag) {
        pg.withL20Api { e, http ->
            val before = e.db.snapshot()
            val res = http.preview(L20.ac1)
            res.status shouldBe HttpStatusCode.OK
            res.countList() shouldBe listOf(3, 3, 0, 0, 0)
            res.warnings().shouldBeEmpty()
            res.fileErrors().shouldBeEmpty()
            res.statuses() shouldContainExactly listOf(2 to "NEW", 3 to "NEW", 4 to "NEW")
            res.rows().map { it.key } shouldContainExactly L20.ac1Keys
            res.str("fileSha256") shouldBe L20.sha256(L20.ac1)
            listOf("entry", "line", "activity", "outbox").forEach { e.count(it) shouldBe 0L }
            e.db.snapshot() shouldBe before
        }
    }

    test("L-20 AC2: after the AC1 import, the same file previews as 3 DUPLICATE and the 2026 overlap as 1 NEW")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val same = http.preview(L20.ac1)
                same.status shouldBe HttpStatusCode.OK
                same.countList() shouldBe listOf(3, 0, 3, 0, 0)
                val overlap = http.preview(L20.file(L20.ac1Rows[0], L20.ac1Rows[1], L20.row2026))
                overlap.countList() shouldBe listOf(3, 1, 2, 0, 0)
                overlap.statuses() shouldContainExactly listOf(2 to "DUPLICATE", 3 to "DUPLICATE", 4 to "NEW")
                val described = http.preview(L20.ac1.replace("Initial deposit", "Opening cash retyped"))
                described.countList() shouldBe listOf(3, 0, 3, 0, 0)
                e.count("entry") shouldBe 3L
            }
        }

    test("L-20 AC3 (a): line 3 with amount 3021.50 previews as a CONFLICT and the stored value is unchanged")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val before = e.db.snapshot()
                val res = http.preview(L20.ac1.replace("3012.50", "3021.50"))
                res.status shouldBe HttpStatusCode.OK
                res.countList() shouldBe listOf(3, 0, 2, 1, 0)
                res.rows().single { it.status == "CONFLICT" }.let {
                    it.line shouldBe 3
                    it.code shouldBe L20.CONFLICT_CODE
                }
                e.service.find("TP-0001-2025-0002")!!.activity.amountMinor shouldBe 301_250L
                e.db.snapshot() shouldBe before
            }
        }

    test("L-20 AC3 (b): the L-01 AC2 file previews as 8 REJECTED rows with line, code, field and message, and 1 NEW")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val res = http.preview(L20.ac2Bad)
                res.status shouldBe HttpStatusCode.OK
                res.countList() shouldBe listOf(9, 1, 0, 0, 8)
                val rejected = res.rows().filter { it.status == "REJECTED" }
                rejected.map { it.line to it.code } shouldContainExactly
                    listOf(
                        2 to "AMOUNT_TOO_PRECISE", 3 to "INVALID_DATE", 4 to "UNKNOWN_ACTIVITY_TYPE",
                        5 to "UNSUPPORTED_CURRENCY", 6 to "MISSING_FIELD", 7 to "NON_POSITIVE_QUANTITY",
                        8 to "SETTLEMENT_BEFORE_EFFECTIVE", 9 to "QUANTITY_TOO_PRECISE",
                    )
                rejected.forEach {
                    (it.field?.isNotBlank() == true) shouldBe true
                    (it.message?.isNotBlank() == true) shouldBe true
                }
                res.rows().single { it.status == "NEW" }.line shouldBe 10
                e.count("entry") shouldBe 3L
            }
        }

    test(
        "L-20 AC3 (c): a bad header gives rowsRead 0 and HEADER_INVALID naming commission; zero-byte; header-only",
    )
        .config(tags = tag) {
            pg.withL20Api { _, http ->
                val lines = L20.ac1.lines().toMutableList()
                lines[0] = lines[0].replace(",commission", "")
                val bad = http.preview(lines.joinToString("\n"))
                bad.status shouldBe HttpStatusCode.OK
                bad.countList() shouldBe listOf(0, 0, 0, 0, 0)
                bad.fileErrorCodes() shouldContainExactly listOf("HEADER_INVALID")
                bad.fileErrorColumns() shouldContainExactly listOf("commission")
                val empty = http.preview("")
                empty.status shouldBe HttpStatusCode.OK
                empty.fileErrorCodes() shouldContainExactly listOf("HEADER_INVALID")
                val headerOnly = http.preview(dev.ledgerline.testsupport.CsvRows.header + "\n")
                headerOnly.countList() shouldBe listOf(0, 0, 0, 0, 0)
                headerOnly.fileErrors().shouldBeEmpty()
            }
        }

    test("L-20 AC3 (d): a body above 32 MB is 413 PAYLOAD_TOO_LARGE and nothing is posted").config(tags = tag) {
        pg.withL20Api { e, http ->
            val before = e.db.snapshot()
            val filler = ByteArray(33 * 1024 * 1024) { 'a'.code.toByte() }
            val head = (dev.ledgerline.testsupport.CsvRows.header + "\n").toByteArray()
            val res = http.previewBytes(head + filler)
            res.status shouldBe HttpStatusCode.PayloadTooLarge
            res.code() shouldBe "PAYLOAD_TOO_LARGE"
            e.db.snapshot() shouldBe before
        }
    }

    test(
        "L-20 AC4: SELL with no buy and TRANSFER_IN with no cost are warned with their file lines; clean file is empty",
    )
        .config(tags = tag) {
            pg.withL20Api { e, http ->
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
                val before = e.db.snapshot()
                val res = http.preview(file)
                res.status shouldBe HttpStatusCode.OK
                res.countList() shouldBe listOf(2, 2, 0, 0, 0)
                res.warningViews().toSet() shouldBe
                    setOf(
                        L20.WarningView(L20.OVERSELL, "SYN-AAA", listOf(2), 0L),
                        L20.WarningView(L20.NO_COST, "SYN-BBB", listOf(3), 0L),
                    )
                res.warnings().size shouldBe 2
                e.db.snapshot() shouldBe before
                val clean =
                    http.preview(
                        L20.file(
                            L20.row("C-1", "quantity" to "10", "amount" to "100.00"),
                            L20.row(
                                "C-2",
                                "activity_type" to "SELL",
                                "quantity" to "4",
                                "effective_date" to "2025-02-06",
                                "amount" to "50.00",
                            ),
                        ),
                    )
                clean.warnings().shouldBeEmpty()
            }
        }

    // Differential: whatever the owner decides for in-file repeats, the preview must say what an import then does.
    test("L-20 AC2 and AC6: the preview counts equal the counts of importCsv on an identical ledger, 6 seeded files")
        .config(tags = tag) {
            repeat(6) { seed ->
                val rnd = kotlin.random.Random(seed)
                val pool = (1..4).map { "P$it" }

                fun line(): String =
                    when (rnd.nextInt(7)) {
                        0 -> L20.row(pool[rnd.nextInt(4)]) // may repeat inside the file, or hit a stored key
                        1 -> L20.row(pool[rnd.nextInt(4)], "amount" to "9.99") // a different payload
                        2 -> L20.row("Z${rnd.nextInt(1000)}", "currency" to "EUR")
                        3 ->
                            L20.row(
                                "D${rnd.nextInt(1000)}",
                                "activity_type" to "DEPOSIT",
                                "security_id" to "",
                                "quantity" to "",
                                "amount" to "0.00",
                            )
                        4 -> L20.row("S${rnd.nextInt(1000)}", "description" to "\"two\nlines\"")
                        else -> L20.row("F${rnd.nextInt(10000)}")
                    }
                val seedRows = listOf(L20.row("P1"), L20.row("P3", "amount" to "5.00"))
                val file = L20.file(*List(rnd.nextInt(3, 14)) { line() }.toTypedArray())
                pg.withL20Api { a, http ->
                    a.service.importCsv(L20.file(*seedRows.toTypedArray()))
                    val res = http.preview(file)
                    res.status shouldBe HttpStatusCode.OK
                    val twin = pg.l02Env().also { it.service.importCsv(L20.file(*seedRows.toTypedArray())) }
                    val report = twin.service.importCsv(file)
                    withClueFile(file) {
                        res.int("new") shouldBe report.posted
                        res.int("duplicates") shouldBe report.duplicates
                        (res.int("conflicts") + res.int("rejected")) shouldBe report.rejected.size
                        res.rows().filter { it.status == "CONFLICT" || it.status == "REJECTED" }
                            .map { it.line to it.code }.sortedBy { it.first } shouldBe
                            report.rejected.map { it.line to it.code }.sortedBy { it.first }
                        res.int("rowsRead") shouldBe res.rows().size
                    }
                    a.count("entry") shouldBe 2L
                }
            }
        }
})

private inline fun withClueFile(
    file: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue("file was:\n$file", block)
