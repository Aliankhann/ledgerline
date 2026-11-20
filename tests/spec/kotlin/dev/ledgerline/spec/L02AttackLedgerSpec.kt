package dev.ledgerline.spec

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.SubmitResult
import dev.ledgerline.app.ingestionModule
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.testsupport.ActivityStreams
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.FaultyDataSource
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Activities
import dev.ledgerline.testsupport.L02Env
import dev.ledgerline.testsupport.L02Json
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.injectedSqlError
import dev.ledgerline.testsupport.killBackend
import dev.ledgerline.testsupport.l02Env
import dev.ledgerline.testsupport.postCsv
import dev.ledgerline.testsupport.postJson
import dev.ledgerline.testsupport.postRaw
import dev.ledgerline.testsupport.rejections
import dev.ledgerline.testsupport.shouldBeConsistent
import dev.ledgerline.testsupport.withL02Api
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.random.Random

/**
 * L-02 phase B attacks on the ledger: a crash between the entry and the outbox insert, the app role's privileges,
 * concurrent imports, a 10 000-row file, and a key mismatch inside one file after a duplicate.
 */
class L02AttackLedgerSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val a1 = L02Activities.a1
    val key = L02Activities.KEY

    /** [IngestionService] over [ds] that fails once, when armed, at the first statement starting with [sql]. */
    fun failingService(
        e: L02Env,
        sql: String,
        armed: AtomicBoolean,
        fault: (DataSource) -> (Connection) -> Unit = { injectedSqlError },
    ): IngestionService {
        val real = fault(e.db.owner)
        val ds = FaultyDataSource(e.db.app, sql) { c -> if (armed.getAndSet(false)) real(c) }
        return IngestionService(LedgerRepository(ds), e.clock)
    }

    // ---------- crash between the entry and the outbox insert ----------

    test("L-02 attack: a failure before the outbox insert rolls back the entry, lines and activity; replay posts once")
        .config(tags = setOf(Integration)) {
            val points =
                mapOf(
                    "INSERT INTO ledger.activity" to "activity insert",
                    "INSERT INTO ledger.outbox" to "outbox insert",
                )
            points.forEach { (sql, name) ->
                withClue("failure at $name") {
                    val e = pg.l02Env()
                    val empty = e.db.snapshot()
                    val armed = AtomicBoolean(true)
                    val crashing = failingService(e, sql, armed)
                    runCatching { crashing.submit(a1) }.isFailure shouldBe true
                    e.db.snapshot() shouldBe empty
                    // the client retries the same request: it must post exactly once
                    val retry = crashing.submit(a1) as SubmitResult.Accepted
                    retry.alreadyExisted shouldBe false
                    e.count("entry") shouldBe 1
                    e.count("outbox") shouldBe 1
                    e.count("activity") shouldBe 1
                    (e.service.submit(a1) as SubmitResult.Accepted) shouldBe retry.copy(alreadyExisted = true)
                    e.db.shouldBeConsistent()
                }
            }
        }

    test("L-02 attack: the database backend killed at the outbox insert leaves no partial rows; replay posts once")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            val empty = e.db.snapshot()
            val armed = AtomicBoolean(true)
            val crashing = failingService(e, "INSERT INTO ledger.outbox", armed) { killBackend(it) }
            runCatching { crashing.submit(a1) }.isFailure shouldBe true
            e.db.snapshot() shouldBe empty
            (crashing.submit(a1) as SubmitResult.Accepted).alreadyExisted shouldBe false
            e.db.shouldBeConsistent()
            e.count("entry") shouldBe 1
        }

    test("L-02 attack: over HTTP a crash at the outbox insert is a 5xx with nothing written, and the retry is 201")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            val armed = AtomicBoolean(true)
            val service = failingService(e, "INSERT INTO ledger.outbox", armed)
            testApplication {
                application { ingestionModule(service) }
                // the test host rethrows an exception that escapes the route; a real server answers 500
                val first = runCatching { client.postRaw(L02Json.body()) }
                (first.isFailure || first.getOrThrow().first.value in 500..599) shouldBe true
                e.count("entry") + e.count("activity") + e.count("outbox") + e.count("line") shouldBe 0
                client.postJson(L02Json.body()).first shouldBe HttpStatusCode.Created
                client.postJson(L02Json.body()).first shouldBe HttpStatusCode.OK
                e.db.shouldBeConsistent()
            }
        }

    // ---------- the app role cannot rewrite history ----------

    test("L-02 attack: the app role cannot UPDATE, DELETE, TRUNCATE or unlock ledger.activity and ledger.outbox")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            e.service.submit(a1)
            val before = e.db.snapshot()
            listOf("activity", "outbox").forEach { t ->
                val statements =
                    listOf(
                        "UPDATE ledger.$t SET entry_id = entry_id + 1",
                        "UPDATE ledger.$t SET entry_id = entry_id WHERE false",
                        "DELETE FROM ledger.$t",
                        "TRUNCATE ledger.$t",
                        "ALTER TABLE ledger.$t DISABLE TRIGGER ALL",
                        "DROP TRIGGER ${t}_append_only ON ledger.$t",
                        "DROP TABLE ledger.$t",
                    )
                statements.forEach { sql ->
                    withClue("as ledgerline_app: $sql") { e.db.failureOf(e.db.app, sql)?.sqlState shouldBe "42501" }
                }
                withClue("as ledgerline_app: SET session_replication_role") {
                    e.db.failureOf(e.db.app, "SET session_replication_role = replica")?.sqlState shouldBe "42501"
                }
                // even the table owner (a superuser here) is stopped by the trigger
                listOf(
                    "UPDATE ledger.$t SET entry_id = entry_id",
                    "DELETE FROM ledger.$t",
                    "TRUNCATE ledger.$t",
                ).forEach {
                    withClue("as owner: $it") { e.db.ownerError(it) shouldContain "append-only" }
                }
            }
            e.db.snapshot() shouldBe before
        }

    test("L-02 attack: stored payload_hash equals the hash recomputed from the stored activity (no lossy storage)")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            val stream = ActivityStreams.generate(Random(20_251_119), "HC", 300)
            stream.forEach { (e.service.submit(it) as SubmitResult.Accepted).alreadyExisted shouldBe false }
            val stored =
                e.db.query(e.db.owner, "SELECT idempotency_key, payload_hash FROM ledger.activity").associate {
                    it[0] to it[1]
                }
            stored.size shouldBe stream.size
            stream.forEach { a ->
                val back = e.service.find(a.idempotencyKey)!!.activity
                withClue("round trip of ${a.idempotencyKey}") {
                    dev.ledgerline.ledger.PayloadHash.of(back) shouldBe stored[a.idempotencyKey]
                    dev.ledgerline.ledger.PayloadHash.of(a) shouldBe stored[a.idempotencyKey]
                    back shouldBe a.copy(description = back.description)
                }
                (e.service.submit(a) as SubmitResult.Accepted).alreadyExisted shouldBe true
            }
            e.db.shouldBeConsistent()
        }

    // ---------- concurrency across imports ----------

    test("L-02 attack: two concurrent imports with overlapping keys and identical rows post each key once")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()

            fun file(range: IntRange) =
                CsvRows.file(
                    *range.map { CsvRows.row("idempotency_key" to "OV-$it", "amount" to "$it.25") }.toTypedArray(),
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CyclicBarrier(2)
                val reports =
                    listOf(
                        file(1..300),
                        file(151..450).lines().let {
                                l ->
                            (listOf(l.first()) + l.drop(1).reversed()).joinToString("\n")
                        },
                    )
                        .map {
                                text ->
                            pool.submit<dev.ledgerline.app.ImportReport> {
                                start.await()
                                e.service.importCsv(text)
                            }
                        }
                        .map { it.get(120, TimeUnit.SECONDS) }
                reports.sumOf { it.posted } shouldBe 450
                reports.sumOf { it.duplicates } shouldBe 150
                reports.flatMap { it.rejected } shouldBe emptyList()
                e.count("entry") shouldBe 450
                e.db.shouldBeConsistent()
            } finally {
                pool.shutdownNow()
            }
        }

    test("L-02 attack: two concurrent imports with the same keys but different amounts: one payload wins per key")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()

            fun file(amount: String) =
                CsvRows.file(
                    *(1..100).map { CsvRows.row("idempotency_key" to "CF-$it", "amount" to amount) }.toTypedArray(),
                )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CyclicBarrier(2)
                val (ra, rb) =
                    listOf("10.00", "20.00")
                        .map {
                                a ->
                            pool.submit<dev.ledgerline.app.ImportReport> {
                                start.await()
                                e.service.importCsv(file(a))
                            }
                        }
                        .map { it.get(120, TimeUnit.SECONDS) }
                (ra.posted + rb.posted) shouldBe 100
                (ra.rejected.size + rb.rejected.size) shouldBe 100
                (ra.duplicates + rb.duplicates) shouldBe 0
                (ra.rejected + rb.rejected).forEach { it.code shouldBe "IDEMPOTENCY_KEY_MISMATCH" }
                val amounts = (1..100).map { e.service.find("CF-$it")!!.activity.amountMinor }
                amounts.count { it == 1_000L } shouldBe ra.posted
                amounts.count { it == 2_000L } shouldBe rb.posted
                e.count("entry") shouldBe 100
                e.db.shouldBeConsistent()
            } finally {
                pool.shutdownNow()
            }
        }

    // ---------- one file, repeated keys ----------

    test("L-02 attack: a mismatch after a duplicate inside one file is reported by its line and poisons nothing")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val file =
                    CsvRows.file(
                        CsvRows.row("idempotency_key" to "SF-1"),
                        CsvRows.row("idempotency_key" to "SF-1"),
                        CsvRows.row("idempotency_key" to "SF-1", "amount" to "9.99"),
                        CsvRows.row("idempotency_key" to "SF-1"),
                        CsvRows.row("idempotency_key" to "SF-2", "effective_date" to "not-a-date"),
                        CsvRows.row("idempotency_key" to "SF-1", "quantity" to "11"),
                        CsvRows.row("idempotency_key" to "SF-2"),
                    )
                val (status, body) = http.postCsv(file)
                status shouldBe HttpStatusCode.OK
                body.getValue("posted").toString() shouldBe "2"
                body.getValue("duplicates").toString() shouldBe "2"
                body.rejections() shouldBe
                    listOf(
                        4 to "IDEMPOTENCY_KEY_MISMATCH", 6 to "INVALID_DATE", 7 to "IDEMPOTENCY_KEY_MISMATCH",
                    )
                e.count("entry") shouldBe 2
                val again = http.postCsv(file).second
                (again.getValue("posted").toString() to again.getValue("duplicates").toString()) shouldBe ("0" to "4")
                again.rejections().map { it.first } shouldBe listOf(4, 6, 7)
                e.count("entry") shouldBe 2
                e.service.find("SF-1")!!.activity.amountMinor shouldBe 30_125L
                e.db.shouldBeConsistent()
            }
        }

    test("L-02 attack (B-012): free-text NUL in a CSV row is rejected by line and the rest of the file still imports")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            val file =
                CsvRows.file(
                    CsvRows.row("idempotency_key" to "NU-1"),
                    CsvRows.row("idempotency_key" to "NU-2", "description" to "a\u0000b"),
                    CsvRows.row("idempotency_key" to "NU-3", "account_id" to "AC\u0000"),
                    CsvRows.row("idempotency_key" to "NU-4"),
                )
            val report = runCatching { e.service.importCsv(file) }
            withClue("import threw ${report.exceptionOrNull()}") { report.isSuccess shouldBe true }
            report.getOrThrow().posted shouldBe 2
            report.getOrThrow().rejected.map { it.line } shouldBe listOf(3, 4)
            e.db.shouldBeConsistent()
        }

    // ---------- bulk ----------

    test("L-02 diagnostic: 500 plain submits, per-append cost (timing in the failure message)")
        .config(tags = setOf(Integration)) {
            val e = pg.l02Env()
            val n = 500
            val stream = ActivityStreams.generate(Random(20_251_120), "PF", n)
            val t0 = System.nanoTime()
            stream.forEach { e.service.submit(it) }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            val perRowMs = totalMs.toDouble() / stream.size
            println("L-02 diagnostic: ${stream.size} submits in $totalMs ms ($perRowMs ms/row)")
            withClue("rows=${stream.size} totalMs=$totalMs perRowMs=$perRowMs (bound 100 ms/row)") {
                (perRowMs < 100.0) shouldBe true
            }
        }

    test("L-02 attack: a 10 000-row file imports once, then re-uploads as 10 000 duplicates, in bounded time")
        .config(tags = setOf(Integration), timeout = kotlin.time.Duration.parse("15m")) {
            // Service level, not HTTP: the Ktor test host aborts a request body that runs longer than 60 s.
            val e = pg.l02Env()
            val n = 10_000
            val file =
                CsvRows.header + "\n" +
                    (1..n).joinToString("") {
                        CsvRows.row("idempotency_key" to "BIG-%05d".format(it), "amount" to "$it.50") + "\n"
                    }
            val t0 = System.nanoTime()
            val first = e.service.importCsv(file)
            val firstMs = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val second = e.service.importCsv(file)
            val secondMs = (System.nanoTime() - t1) / 1_000_000
            println("L-02 attack bulk: $n rows posted in $firstMs ms, re-uploaded in $secondMs ms")
            (first.posted to first.duplicates) shouldBe (n to 0)
            (second.posted to second.duplicates) shouldBe (0 to n)
            first.rejected shouldBe emptyList()
            e.count("entry") shouldBe n.toLong()
            e.count("outbox") shouldBe n.toLong()
            e.count("activity") shouldBe n.toLong()
            val last = "SELECT source_seq FROM ledger.entry WHERE idempotency_key = 'BIG-10000'"
            e.db.query(e.db.owner, last)[0][0] shouldBe "10001"
            val clue = "firstMs=$firstMs reuploadMs=$secondMs rows=$n"
            // Relative bounds only: absolute throughput is measured in L-13, not in CI.
            withClue(
                "re-upload more than 2x slower than first import: $clue",
            ) { (secondMs <= 2 * firstMs) shouldBe true }
            withClue("per-row time above 60 ms ceiling: $clue") { (firstMs < 60L * n) shouldBe true }
            println(
                "L-02 attack bulk: per-row first=${firstMs.toDouble() / n} ms, re-upload=${secondMs.toDouble() / n} ms",
            )
            e.db.shouldBeConsistent()
        }
})
