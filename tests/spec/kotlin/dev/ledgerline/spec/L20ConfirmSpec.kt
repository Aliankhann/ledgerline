package dev.ledgerline.spec

import dev.ledgerline.app.SubmitResult
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Env
import dev.ledgerline.testsupport.L20
import dev.ledgerline.testsupport.L20.allowInserts
import dev.ledgerline.testsupport.L20.failEveryInsert
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.amountOf
import dev.ledgerline.testsupport.confirm
import dev.ledgerline.testsupport.confirmAfter
import dev.ledgerline.testsupport.entryKeys
import dev.ledgerline.testsupport.l02Env
import dev.ledgerline.testsupport.postCsv
import dev.ledgerline.testsupport.preview
import dev.ledgerline.testsupport.shouldBeConsistent
import dev.ledgerline.testsupport.withL20Api
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * L-20 upload preview, AC5 (read-only), AC6 (confirm posts exactly what was previewed, once) and AC7 (a changed ledger
 * never double-posts). Needs Docker: every test is tagged Integration. Owner decisions are not asserted (see [L20]):
 * a confirm of a file with a conflict or a rejected row is checked only for what a partial and an all-or-nothing
 * confirm share; exact partial results are asserted on clean files.
 */
class L20ConfirmSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val tag = setOf(Integration)

    fun L02Env.postAc1() {
        service.importCsv(L20.ac1).let { (it.posted to it.duplicates) shouldBe (3 to 0) }
    }

    val ac1Acts = CanonicalCsv.parse(L20.ac1).activities
    val key20 = "TP-0001-2025-0020"

    /** AC6's file: line 2 unchanged, line 3 amount 3021.50, line 4 a new valid row, line 5 currency EUR. */
    val ac6File =
        L20.file(
            L20.ac1Rows[0],
            L20.ac1Rows[1].replace("3012.50", "3021.50"),
            L20.row(key20),
            L20.row("TP-0001-2025-0021", "currency" to "EUR"),
        )

    /** A file with only a duplicate and a new row, so partial and all-or-nothing confirm give the same answer. */
    val cleanFile = L20.file(L20.ac1Rows[0], L20.row(key20))

    // ---------- AC5 ----------

    test("L-20 AC5: previews of the AC1, AC3 and AC4 files change nothing, with every INSERT failing, 20 at once")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val ac4 =
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
                val files = listOf(L20.ac1, L20.ac2Bad, ac4, L20.ac1.replace("3012.50", "3021.50"))
                val snapshot = e.db.snapshot()
                val counts = e.db.rowCounts()
                val baseline = files.map { http.preview(it) }
                baseline.forEach { it.status shouldBe HttpStatusCode.OK }

                e.db.failEveryInsert()
                files.forEachIndexed { i, f ->
                    val again = http.preview(f)
                    again.status shouldBe HttpStatusCode.OK
                    again.text shouldBe baseline[i].text // byte-identical at the same ledger state (invariant 5)
                }
                val concurrent =
                    coroutineScope {
                        (0 until 20).map {
                                i ->
                            async(Dispatchers.IO) { i % 4 to http.preview(files[i % 4]) }
                        }.awaitAll()
                    }
                concurrent.forEach { (i, r) ->
                    r.status shouldBe HttpStatusCode.OK
                    r.text shouldBe baseline[i].text
                }
                e.db.allowInserts()

                e.db.snapshot() shouldBe snapshot
                e.db.rowCounts() shouldBe counts
                e.count("outbox") shouldBe 3L
            }
        }

    test("L-20 AC5: a key seen only in a preview is later accepted as NEW, with the id it would have had anyway")
        .config(tags = tag) {
            val file = L20.file(L20.row("ONLY-IN-PREVIEW"))
            val control = pg.l02Env()
            control.postAc1()
            control.service.importCsv(file).posted shouldBe 1
            val expectedEntryId = control.service.find("ONLY-IN-PREVIEW")!!.entryId

            pg.withL20Api { e, http ->
                e.postAc1()
                repeat(3) { http.preview(file).int("new") shouldBe 1 }
                http.preview(L20.ac1).int("duplicates") shouldBe 3
                e.service.importCsv(file).let { (it.posted to it.duplicates) shouldBe (1 to 0) }
                // No previous preview burned an id or consumed the key.
                e.service.find("ONLY-IN-PREVIEW")!!.entryId shouldBe expectedEntryId
                http.preview(file).int("duplicates") shouldBe 1
            }
        }

    // ---------- AC6 ----------

    test("L-20 AC6: the mixed file previews as NEW line 4, DUPLICATE line 2, CONFLICT line 3, REJECTED line 5")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val res = http.preview(ac6File)
                res.status shouldBe HttpStatusCode.OK
                res.counts() shouldBe listOf(4, 1, 1, 1, 1)
                res.statuses() shouldContainExactly
                    listOf(2 to "DUPLICATE", 3 to "CONFLICT", 4 to "NEW", 5 to "REJECTED")
                res.rows()[1].code shouldBe L20.CONFLICT_CODE
                res.rows()[3].code shouldBe "UNSUPPORTED_CURRENCY"
                e.count("entry") shouldBe 3L
            }
        }

    test("L-20 AC6: confirm of a clean file posts exactly the previewed NEW row, once, and matches the preview")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val prev = http.preview(cleanFile)
                prev.counts() shouldBe listOf(2, 1, 1, 0, 0)
                val res = http.confirmAfter(cleanFile, prev)
                res.status shouldBe HttpStatusCode.OK
                res.int("posted") shouldBe 1
                res.int("duplicates") shouldBe 1
                res.statuses() shouldContainExactly prev.statuses()
                res.str("changedSincePreview") shouldBe "false"
                res.changedRows().shouldBeEmpty()
                e.count("entry") shouldBe 4L
                e.count("outbox") shouldBe 4L
                e.service.find(key20)!!.activity.idempotencyKey shouldBe key20
                // Confirming again is a no-op: nothing is NEW any more.
                val again = http.confirmAfter(cleanFile, prev)
                again.status shouldBe HttpStatusCode.OK
                again.int("posted") shouldBe 0
                again.int("duplicates") shouldBe 2
                e.count("entry") shouldBe 4L
                http.preview(cleanFile).counts() shouldBe listOf(2, 0, 2, 0, 0)
            }
        }

    test("L-20 AC6: confirm of the mixed file never posts the conflict or the rejected row and never overwrites")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val before = e.db.snapshot()
                val prev = http.preview(ac6File)
                val res = http.confirmAfter(ac6File, prev)
                val gained = e.count("entry") - 3
                withClue("confirm answered ${res.status}: ${res.text}") {
                    // Both a partial and an all-or-nothing confirm satisfy these (owner decision, not asserted).
                    e.count("outbox") shouldBe e.count("entry")
                    e.service.amountOf("TP-0001-2025-0002") shouldBe 301_250L
                    e.service.find("TP-0001-2025-0021") shouldBe null
                    e.entryKeys().toSet().size shouldBe e.entryKeys().size
                    if (res.status == HttpStatusCode.OK) {
                        res.int("posted").toLong() shouldBe gained
                        res.rows().filter { it.status == "CONFLICT" || it.status == "REJECTED" }
                            .map { it.line to it.status } shouldContainExactly listOf(3 to "CONFLICT", 5 to "REJECTED")
                    } else {
                        gained shouldBe 0L
                        e.db.snapshot() shouldBe before
                    }
                }
            }
        }

    test(
        "L-20 AC6: confirm with a different body than the previewed fileSha256 is 409 PREVIEW_MISMATCH, nothing posted",
    )
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                e.postAc1()
                val prev = http.preview(cleanFile)
                val before = e.db.snapshot()
                val sha = prev.str("fileSha256")
                val edited = cleanFile.replace("301.25", "301.26")
                val changedFile = http.confirm(edited, sha, listOf(3))
                changedFile.status shouldBe HttpStatusCode.Conflict
                changedFile.code() shouldBe "PREVIEW_MISMATCH"
                val extraNewline = http.confirm(cleanFile + "\n", sha, listOf(3))
                extraNewline.status shouldBe HttpStatusCode.Conflict
                extraNewline.code() shouldBe "PREVIEW_MISMATCH"
                e.db.snapshot() shouldBe before
            }
        }

    test("L-20 AC6: confirm without a fileSha256 is 400 and posts nothing").config(tags = tag) {
        pg.withL20Api { e, http ->
            e.postAc1()
            val before = e.db.snapshot()
            val res = http.confirm(cleanFile, null)
            res.status shouldBe HttpStatusCode.BadRequest
            e.db.snapshot() shouldBe before
        }
    }

    test("L-20 AC6: POST /v1/imports keeps its L-02 answers with the preview routes installed").config(tags = tag) {
        pg.withL20Api { e, http ->
            val (s1, first) = http.postCsv(L20.ac1)
            s1 shouldBe HttpStatusCode.OK
            first.getValue("posted").jsonPrimitive.int shouldBe 3
            first.getValue("duplicates").jsonPrimitive.int shouldBe 0
            val (_, second) = http.postCsv(L20.ac1)
            second.getValue("posted").jsonPrimitive.int shouldBe 0
            second.getValue("duplicates").jsonPrimitive.int shouldBe 3
            val (_, mismatch) = http.postCsv(L20.ac1.replace("3012.50", "3021.50"))
            mismatch.getValue("duplicates").jsonPrimitive.int shouldBe 2
            e.count("entry") shouldBe 3L
        }
    }

    // ---------- AC7 ----------

    test("L-20 AC7 (a): another client posts row 2 first; confirm posts 2, reports 1 duplicate, one entry per key")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                val prev = http.preview(L20.ac1)
                prev.int("new") shouldBe 3
                (e.service.submit(ac1Acts[1]) as SubmitResult.Accepted).alreadyExisted shouldBe false
                val res = http.confirmAfter(L20.ac1, prev)
                res.status shouldBe HttpStatusCode.OK
                res.int("posted") shouldBe 2
                res.int("duplicates") shouldBe 1
                res.str("changedSincePreview") shouldBe "true"
                res.changedRows().map { it.getValue("line").jsonPrimitive.int } shouldContainExactly listOf(3)
                res.changedRows().single().getValue("previewStatus").jsonPrimitive.content shouldBe "NEW"
                res.changedRows().single().getValue("status").jsonPrimitive.content shouldBe "DUPLICATE"
                e.entryKeys() shouldContainExactlyInAnyOrder L20.ac1Keys
                e.count("outbox") shouldBe 3L
            }
        }

    test("L-20 AC7 (b): another client posts key 3 with amount 1400.00; confirm never overwrites it or double-posts")
        .config(tags = tag) {
            pg.withL20Api { e, http ->
                val prev = http.preview(L20.ac1)
                val other = ac1Acts[2].copy(amountMinor = 140_000L)
                (e.service.submit(other) as SubmitResult.Accepted).alreadyExisted shouldBe false
                val res = http.confirmAfter(L20.ac1, prev)
                withClue("confirm answered ${res.status}: ${res.text}") {
                    // Shared by a partial and an all-or-nothing confirm (owner decision, not asserted).
                    e.service.amountOf("TP-0001-2025-0003") shouldBe 140_000L
                    e.entryKeys().toSet().size shouldBe e.entryKeys().size
                    e.count("outbox") shouldBe e.count("entry")
                    if (res.status == HttpStatusCode.OK) {
                        res.int("posted").toLong() shouldBe e.count("entry") - 1
                        res.rows().single { it.line == 4 }.let {
                            it.status shouldBe "CONFLICT"
                            it.code shouldBe L20.CONFLICT_CODE
                        }
                        res.str("changedSincePreview") shouldBe "true"
                        res.changedRows().map { it.getValue("line").jsonPrimitive.int } shouldContainExactly listOf(4)
                    } else {
                        e.count("entry") shouldBe 1L
                    }
                }
            }
        }

    test("L-20 AC7 (c): two concurrent confirms of the same file post 3 once and report 3 duplicates once")
        .config(tags = tag) {
            repeat(3) {
                pg.withL20Api { e, http ->
                    val prev = http.preview(L20.ac1)
                    val results =
                        coroutineScope {
                            List(2) { async(Dispatchers.IO) { http.confirmAfter(L20.ac1, prev) } }.awaitAll()
                        }
                    results.forEach { it.status shouldBe HttpStatusCode.OK }
                    results.map { it.int("posted") }.sorted() shouldContainExactly listOf(0, 3)
                    results.single { it.int("posted") == 0 }.int("duplicates") shouldBe 3
                    results.single { it.int("posted") == 0 }.str("changedSincePreview") shouldBe "true"
                    e.entryKeys() shouldContainExactlyInAnyOrder L20.ac1Keys
                    e.count("outbox") shouldBe 3L
                    e.db.shouldBeConsistent()
                }
            }
        }
})
