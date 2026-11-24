package dev.ledgerline.spec

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.PreviewReport
import dev.ledgerline.app.PreviewStatus
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L20
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.random.Random

/**
 * L-20 phase B (QE attack). Pure classification attacks run in `./gradlew build`; the two tests that need the ledger
 * are tagged Integration. Nothing here asserts an open owner decision: in-file repeats follow the story's AC2 text.
 */
class L20AttackSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val ac1 = L20.ac1
    val held = L20.stored(ac1)

    fun PreviewReport.statuses() = rows.map { it.line to it.status }

    fun PreviewReport.counts() = listOf(rowsRead, new, duplicates, conflicts, rejected)

    val deposit = L20.ac1Rows[0]

    fun cashRow(
        key: String,
        amount: String,
    ) = deposit.split(",").toMutableList().also {
        it[0] = key
        it[10] = amount
    }.joinToString(",")

    test(
        "L-20 attack 1: a key repeated in one file is NEW, DUPLICATE, CONFLICT, DUPLICATE; a refused row frees its key",
    ) {
        val a = L20.row("R-1")
        val other = L20.row("R-1", "amount" to "999.00")
        // Lines 3 and 5 repeat line 2; line 4 reuses the key with another payload.
        // Line 6 is refused at posting (ZERO_AMOUNT) and must not consume key R-2, so line 7 is its first accepted use.
        val file = L20.file(a, a, other, a, cashRow("R-2", "0.00"), cashRow("R-2", "5.00"))
        val r = L20.classify(file)
        r.statuses() shouldContainExactly
            listOf(
                2 to PreviewStatus.NEW,
                3 to PreviewStatus.DUPLICATE,
                4 to PreviewStatus.CONFLICT,
                5 to PreviewStatus.DUPLICATE,
                6 to PreviewStatus.REJECTED,
                7 to PreviewStatus.NEW,
            )
        r.counts() shouldBe listOf(6, 2, 2, 1, 1)
        r.rows[4].code shouldBe "ZERO_AMOUNT"
    }

    test("L-20 attack 2: CRLF, a BOM, trailing blank lines and re-ordered header columns") {
        val crlf = ac1.replace("\n", "\r\n")
        val r = L20.classify(crlf, held)
        r.counts() shouldBe listOf(3, 0, 3, 0, 0)
        r.rows.map { it.line } shouldContainExactly listOf(2, 3, 4)
        L20.classify(CsvRows.BOM + ac1, held).counts() shouldBe listOf(3, 0, 3, 0, 0)
        val padded = L20.classify(ac1 + "\n\n\r\n\n", held)
        padded.counts() shouldBe listOf(3, 0, 3, 0, 0) // blank lines are not rows
        padded.rejected shouldBe 0
        // The file bytes differ, so the hash must too: the hash identifies the bytes, not the rows.
        (r.fileSha256 == L20.classify(ac1, held).fileSha256) shouldBe false

        // Same set of column names, wrong order: one HEADER_INVALID, no rows, no warnings, no ledger lookup.
        val lines = ac1.lines().toMutableList()
        val cols = lines[0].split(",").toMutableList()
        cols[0] = cols[1].also { cols[1] = cols[0] }
        lines[0] = cols.joinToString(",")
        var lookups = 0
        val swapped =
            dev.ledgerline.app.ImportPreview.classify(lines.joinToString("\n"), {
                lookups++
                null
            })
        swapped.fileErrors.map { it.code } shouldContainExactly listOf("HEADER_INVALID")
        swapped.counts() shouldBe listOf(0, 0, 0, 0, 0)
        swapped.warnings.shouldBeEmpty()
        lookups shouldBe 0
    }

    test("L-20 attack 3: keys that differ by case are distinct (NEW); keys that differ by padding are the same key") {
        val lower = ac1.replace("TP-0001-2025-0002", "tp-0001-2025-0002")
        L20.classify(lower, held).statuses() shouldContainExactly
            listOf(2 to PreviewStatus.DUPLICATE, 3 to PreviewStatus.NEW, 4 to PreviewStatus.DUPLICATE)
        val padded = ac1.replace("TP-0001-2025-0002", "  TP-0001-2025-0002  ")
        val asked = mutableListOf<String>()
        val r =
            dev.ledgerline.app.ImportPreview.classify(padded, { k ->
                asked += k
                held[k]
            })
        // The key the parser stores is what the ledger is asked about, and that is what importCsv would store.
        asked.toSet() shouldBe L20.ac1Keys.toSet()
        r.counts() shouldBe listOf(3, 0, 3, 0, 0)
    }

    test("L-20 attack 4: fields the hash ignores or normalises never make a CONFLICT, and every hashed field does") {
        fun variant(vararg changes: Pair<String, String>) =
            L20.row(
                "N-1",
                "settlement_date" to "2025-02-07",
                "commission" to "4.95",
                "amount" to "301.20",
                *changes,
            )

        val twin = variant()
        val stored = L20.stored(L20.file(twin))

        fun verdict(row: String) = L20.classify(L20.file(row), stored).rows.single().status
        verdict(twin) shouldBe PreviewStatus.DUPLICATE
        verdict(variant("description" to "retyped")) shouldBe PreviewStatus.DUPLICATE
        verdict(variant("amount" to "301.2")) shouldBe PreviewStatus.DUPLICATE
        verdict(variant("quantity" to "10.0")) shouldBe PreviewStatus.DUPLICATE
        verdict(variant("quantity" to "10.000000")) shouldBe PreviewStatus.DUPLICATE
        // The same row on another physical line (another sourceSeq) is still the same activity.
        L20.classify(L20.file(L20.row("N-0"), twin), stored).rows.last().status shouldBe PreviewStatus.DUPLICATE
        listOf(
            variant("settlement_date" to "2025-02-08"),
            variant("settlement_date" to ""),
            variant("commission" to "4.96"),
            variant("quantity" to "11"),
            variant("account_id" to "TP-0001-NR2"),
            variant("taxpayer_id" to "TP-0002"),
            variant("currency" to "USD"),
            variant("security_id" to "SYN-BBB"),
            variant("effective_date" to "2025-02-06"),
            variant("activity_type" to "SELL"),
        ).forEach { withClue(it) { verdict(it) shouldBe PreviewStatus.CONFLICT } }
    }

    test("L-20 attack 5: the same arguments give an equal report, whatever the order of the existing ledger") {
        val file =
            L20.file(
                L20.row("D-1", "activity_type" to "SELL", "quantity" to "5", "amount" to "60.00"),
                L20.row("D-2", "quantity" to "3", "effective_date" to "2025-02-01"),
                L20.row(
                    "D-3",
                    "effective_date" to "2025-02-02",
                    "security_id" to "SYN-BBB",
                    "activity_type" to "TRANSFER_IN",
                    "amount" to "0.00",
                ),
            )
        val existing: List<PostedActivity> =
            CanonicalCsv.parse(ac1 + L20.row("E-1", "security_id" to "SYN-BBB") + "\n").activities
                .mapIndexed { i, a -> PostedActivity("a$i", i + 1L, a) }
        val base = L20.classify(file, L20.stored(ac1), existing)
        L20.classify(file, L20.stored(ac1), existing) shouldBe base
        repeat(20) { seed ->
            L20.classify(file, L20.stored(ac1), existing.shuffled(Random(seed))) shouldBe base
        }
        // Order independence of the file (invariant 6): an earlier-dated BUY on a later line covers the SELL, so no gap.
        val ordered =
            L20.file(
                L20.row(
                    "O-1",
                    "activity_type" to "SELL",
                    "quantity" to "5",
                    "amount" to "60.00",
                    "effective_date" to "2025-03-01",
                ),
                L20.row("O-2", "quantity" to "10", "effective_date" to "2025-02-01"),
            )
        L20.classify(ordered).warnings.shouldBeEmpty()
        // A SELL that is only a DUPLICATE or CONFLICT is not "caused by the file".
        val sell = L20.row("O-1", "activity_type" to "SELL", "quantity" to "5", "amount" to "60.00")
        val heldSell = L20.stored(L20.file(sell))
        L20.classify(L20.file(sell), heldSell).warnings.shouldBeEmpty()
        L20.classify(L20.file(sell.replace("60.00", "61.00")), heldSell).warnings.shouldBeEmpty()
    }

    test("L-20 attack 6a: an account id the ledger reserves (SYS:) is a REJECTED row, never an exception") {
        val file = L20.file(L20.row("S-0"), L20.row("S-1", "account_id" to "SYS:MARKET"))
        val r = runCatching { L20.classify(file) }
        withClue("classify threw ${r.exceptionOrNull()}") { r.isSuccess shouldBe true }
        r.getOrThrow().statuses() shouldContainExactly listOf(2 to PreviewStatus.NEW, 3 to PreviewStatus.REJECTED)
    }

    test("L-20 attack 6b: the largest 18-digit amount and commission classify as NEW and one more digit as REJECTED") {
        val max = "9999999999999999.99"
        val file =
            L20.file(
                L20.row("S-2", "amount" to max, "commission" to max),
                L20.row("S-3", "amount" to "10000000000000000.00"),
            )
        val r = runCatching { L20.classify(file) }
        withClue("classify threw ${r.exceptionOrNull()}") { r.isSuccess shouldBe true }
        r.getOrThrow().statuses() shouldContainExactly listOf(2 to PreviewStatus.NEW, 3 to PreviewStatus.REJECTED)
    }

    test("L-20 attack 7: a 100,000-row file classifies correctly (counts, ascending lines) in under 60 seconds") {
        val n = 100_000
        val sb = StringBuilder(CsvRows.header).append('\n')
        repeat(n) { i ->
            sb.append(
                L20.row("BIG-$i", "effective_date" to "2025-%02d-%02d".format(1 + i % 12, 1 + i % 28)),
            ).append('\n')
        }
        val file = sb.toString()
        val started = System.nanoTime()
        val r = L20.classify(file, L20.stored(ac1))
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        r.counts() shouldBe listOf(n, n, 0, 0, 0)
        r.rows.map { it.line } shouldBe (2..n + 1).toList()
        withClue("took $seconds s") { (seconds < 60) shouldBe true }
    }

    test("L-20 attack 8: concurrent confirms never need a second pool connection while holding the first")
        .config(tags = setOf(Integration)) {
            // A pool of 3 (production is 10, so 10 simultaneous confirms behave the same way): each confirm holds one
            // connection for the import lock and needs another to submit a row. Waiters must not starve the holder.
            val db = pg.freshDatabase()
            val pool = BoundedPool(db.app, size = 3, waitSeconds = 8)
            val service = IngestionService(LedgerRepository(pool))
            val file = L20.ac1
            val reports =
                coroutineScope {
                    List(6) {
                        async(Dispatchers.IO) { runCatching { service.confirm(file) } }
                    }.awaitAll()
                }
            withClue("failures: ${reports.mapNotNull { it.exceptionOrNull()?.toString() }}") {
                reports.all { it.isSuccess } shouldBe true
            }
            reports.sumOf { it.getOrThrow().new } shouldBe 3
            reports.sumOf { it.getOrThrow().duplicates } shouldBe 15
        }

    test("L-20 attack 9: preview agrees with importCsv row for row on a file of repeats, refusals and rejects")
        .config(tags = setOf(Integration)) {
            val file =
                L20.file(
                    L20.row("X-1"),
                    L20.row("X-1"),
                    L20.row("X-1", "amount" to "1.00"),
                    cashRow("X-2", "0.00"),
                    cashRow("X-2", "7.00"),
                    L20.row("X-3", "currency" to "EUR"),
                )
            val db = pg.freshDatabase()
            val service = IngestionService(LedgerRepository(db.app))
            val previewed = service.preview(file)
            val imported = runCatching { service.confirm(file) }
            withClue("confirm threw ${imported.exceptionOrNull()}") { imported.isSuccess shouldBe true }
            imported.getOrThrow().rows.map { it.line to it.status } shouldBe previewed.rows.map { it.line to it.status }
        }
})

/** A data source that hands out at most [size] connections at once and fails a waiter after [waitSeconds]. */
private class BoundedPool(
    private val inner: DataSource,
    size: Int,
    private val waitSeconds: Long,
) : DataSource by inner {
    private val slots = Semaphore(size)

    override fun getConnection(): Connection {
        if (!slots.tryAcquire(waitSeconds, TimeUnit.SECONDS)) throw SQLException("pool exhausted after $waitSeconds s")
        val c =
            try {
                inner.connection
            } catch (e: Throwable) {
                slots.release()
                throw e
            }
        return java.lang.reflect.Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            try {
                val out = method.invoke(c, *(args ?: emptyArray()))
                if (method.name == "close") slots.release()
                out
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }
}
