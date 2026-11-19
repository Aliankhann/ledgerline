package dev.ledgerline.spec

import dev.ledgerline.app.ImportReport
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ledger.PayloadHash
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.Fixtures
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Activities
import dev.ledgerline.testsupport.L02Env
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.l02Env
import dev.ledgerline.testsupport.postCsv
import dev.ledgerline.testsupport.rejections
import dev.ledgerline.testsupport.withL02Api
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Spec for story L-02, second file (the first, [L02IngestionSpec], holds the API contract and AC1 to AC8): CSV import
 * (AC9, AC10, placeholders C23 and C24) and the payload hash (placeholder C20). Kept separate only for the 400-line
 * file-size gate.
 */
class L02ImportSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val a1 = L02Activities.a1
    val csv = Fixtures.text("l01/ac1_valid.csv")

    fun env() = pg.l02Env()

    suspend fun withApi(
        block:
            suspend io.ktor.server.testing.ApplicationTestBuilder.(
                dev.ledgerline.testsupport.L02Env,
                io.ktor.client.HttpClient,
            ) -> Unit,
    ) = pg.withL02Api(block)

    test("L-02 AC9: the L-01 AC1 file twice: posted 3 then duplicates 3, exactly 3 entries (HTTP)")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val (s1, first) = http.postCsv(csv)
                s1 shouldBe HttpStatusCode.OK
                first.getValue("posted").jsonPrimitive.int shouldBe 3
                first.getValue("duplicates").jsonPrimitive.int shouldBe 0
                first.rejections() shouldBe emptyList()
                val before = e.db.snapshot()
                val (s2, second) = http.postCsv(csv)
                s2 shouldBe HttpStatusCode.OK
                second.getValue("posted").jsonPrimitive.int shouldBe 0
                second.getValue("duplicates").jsonPrimitive.int shouldBe 3
                second.rejections() shouldBe emptyList()
                e.count("entry") shouldBe 3
                e.db.snapshot() shouldBe before
            }
        }

    test("L-02 AC10: after AC9, line 3 with amount 3021.50 gives duplicates 2 and a rejection on line 3 (HTTP)")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                http.postCsv(csv)
                val before = e.db.snapshot()
                val changed = csv.replace("3012.50", "3021.50")
                val (status, body) = http.postCsv(changed)
                status shouldBe HttpStatusCode.OK
                body.getValue("posted").jsonPrimitive.int shouldBe 0
                body.getValue("duplicates").jsonPrimitive.int shouldBe 2
                body.rejections() shouldBe listOf(3 to "IDEMPOTENCY_KEY_MISMATCH")
                e.count("entry") shouldBe 3
                e.db.snapshot() shouldBe before
            }
        }

    test("L-02 AC9 and AC10: importCsv reports the same numbers through the service")
        .config(tags = setOf(Integration)) {
            val e = env()
            e.service.importCsv(csv).let { (it.posted to it.duplicates) shouldBe (3 to 0) }
            val again: ImportReport = e.service.importCsv(csv)
            (again.posted to again.duplicates) shouldBe (0 to 3)
            again.rejected shouldBe emptyList()
            val mismatch = e.service.importCsv(csv.replace("3012.50", "3021.50"))
            (mismatch.posted to mismatch.duplicates) shouldBe (0 to 2)
            mismatch.rejected.map { it.line to it.code } shouldBe listOf(3 to "IDEMPOTENCY_KEY_MISMATCH")
            e.count("entry") shouldBe 3
        }

    test(
        "L-02 AC9 (C23): bad rows are reported by line and good rows still post; the fixed file then posts the bad row",
    )
        .config(tags = setOf(Integration)) {
            val e = env()
            val good1 = CsvRows.row("idempotency_key" to "C23-1")
            val bad = CsvRows.row("idempotency_key" to "C23-2", "currency" to "EUR")
            val good3 = CsvRows.row("idempotency_key" to "C23-3", "amount" to "10.00")
            val report = e.service.importCsv(CsvRows.file(good1, bad, good3))
            (report.posted to report.duplicates) shouldBe (2 to 0)
            report.rejected.map { it.line to it.code } shouldBe listOf(3 to "UNSUPPORTED_CURRENCY")
            e.count("entry") shouldBe 2
            val fixed = e.service.importCsv(CsvRows.file(good1, CsvRows.row("idempotency_key" to "C23-2"), good3))
            (fixed.posted to fixed.duplicates) shouldBe (1 to 2)
            fixed.rejected shouldBe emptyList()
        }

    test("L-02 AC9 (C24): a key repeated in one file is a duplicate if the rows are identical and a mismatch if not")
        .config(tags = setOf(Integration)) {
            val e = env()
            val row = CsvRows.row("idempotency_key" to "C24-1")
            val same = e.service.importCsv(CsvRows.file(row, row))
            (same.posted to same.duplicates) shouldBe (1 to 1)
            same.rejected shouldBe emptyList()
            val differ =
                e.service.importCsv(
                    CsvRows.file(
                        CsvRows.row("idempotency_key" to "C24-2"),
                        CsvRows.row("idempotency_key" to "C24-2", "amount" to "9.99"),
                    ),
                )
            (differ.posted to differ.duplicates) shouldBe (1 to 0)
            differ.rejected.map { it.line to it.code } shouldBe listOf(3 to "IDEMPOTENCY_KEY_MISMATCH")
            e.count("entry") shouldBe 2
        }

    // ---------- C20: the payload hash (no database) ----------

    test("L-02 AC4 (C20): PayloadHash is 64 lowercase hex, ignores description, and changes with every other field") {
        val h = PayloadHash.of(a1)
        h shouldMatch Regex("[0-9a-f]{64}")
        PayloadHash.of(a1.copy()) shouldBe h
        PayloadHash.of(a1.copy(description = "anything")) shouldBe h
        val fx = a1.copy(counterCurrency = Currency.USD, counterAmountMinor = 5L)
        val changed =
            listOf(
                a1.copy(idempotencyKey = "OTHER"), a1.copy(taxpayerId = "TP-0002"), a1.copy(accountId = "X"),
                a1.copy(accountType = AccountType.RRSP), a1.copy(activityType = ActivityType.SELL),
                a1.copy(effectiveDate = a1.effectiveDate.plusDays(1)), a1.copy(settlementDate = null),
                a1.copy(securityId = "SYN-BBB"), a1.copy(quantity = null), a1.copy(currency = Currency.USD),
                a1.copy(amountMinor = a1.amountMinor + 1), a1.copy(commissionMinor = 0L), a1.copy(sourceSeq = 7L),
                fx, fx.copy(counterAmountMinor = 6L), fx.copy(counterCurrency = Currency.CAD),
            )
        changed.map { PayloadHash.of(it) }.toSet().let { it + h } shouldHaveSize changed.size + 1
    }
})
