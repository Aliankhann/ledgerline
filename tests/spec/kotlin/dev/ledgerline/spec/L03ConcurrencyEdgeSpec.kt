package dev.ledgerline.spec

import dev.ledgerline.ledger.EntryRequest
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side
import dev.ledgerline.ledger.UnbalancedEntryException
import dev.ledgerline.testsupport.FaultyDataSource
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LedgerDb
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.injectedSqlError
import dev.ledgerline.testsupport.killBackend
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * L-03 phase B attack tests, round 1: concurrency (AC11, draft ADR 0005 "sequence follows commit order") and
 * atomicity (AC5: a refused or failed append writes nothing). All need Docker.
 */
class L03ConcurrencyEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }
    val ac1 = L03Activities.ac1
    val date = LocalDate.of(2025, 1, 7)

    fun balanced(
        key: String,
        account: String = "TP-0001-NR1",
    ) = EntryRequest(
        key,
        date,
        1,
        listOf(LedgerLine(account, "CAD", Side.DEBIT, 100), LedgerLine("SYS:EXTERNAL", "CAD", Side.CREDIT, 100)),
    )

    fun LedgerDb.unbalancedUnits(): List<List<String?>> =
        query(
            owner,
            "SELECT entry_id, unit FROM ledger.line GROUP BY entry_id, unit " +
                "HAVING sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0",
        )

    fun LedgerDb.entryIds(): List<Long> =
        query(owner, "SELECT entry_id FROM ledger.entry ORDER BY 1").map {
            it[0]!!.toLong()
        }

    // ---------- Concurrency ----------

    test("L-03 edge: 8 threads x 25 appends give 200 unique, gap-free sequences that become visible in order")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val threads = 8
            val perThread = 25
            val pool = Executors.newFixedThreadPool(threads + 1)
            val start = CyclicBarrier(threads + 1)
            val writing = AtomicBoolean(true)
            val holes = Collections.synchronizedList(mutableListOf<String>())
            var snapshots = 0
            try {
                // A reader that must never see sequence n committed while a smaller one is still missing.
                val reader =
                    pool.submit {
                        start.await()
                        var previous = emptyList<Long>()
                        while (writing.get()) {
                            val ids = db.entryIds()
                            snapshots += 1
                            if (ids != (1L..ids.size).toList()) holes += "not contiguous from 1: $ids"
                            if (ids.take(previous.size) != previous) holes += "snapshot $previous not a prefix of $ids"
                            previous = ids
                        }
                    }
                val writers =
                    (0 until threads).map { t ->
                        pool.submit<List<Long>> {
                            start.await()
                            (0 until perThread).map { i ->
                                repo.post(ac1.copy(idempotencyKey = "CONC-$t-$i", sourceSeq = i.toLong())).sequence
                            }
                        }
                    }
                val perWriter = writers.map { it.get(120, TimeUnit.SECONDS) }
                writing.set(false)
                reader.get(30, TimeUnit.SECONDS)
                val all = perWriter.flatten()
                println(
                    "L-03 edge concurrency: ${all.size} appends from $threads threads; " +
                        "reader took $snapshots snapshots",
                )
                assertSoftly {
                    all.toSet() shouldHaveSize threads * perThread
                    all.sorted() shouldContainExactly (1L..(threads * perThread).toLong()).toList()
                    perWriter.forEach { it.shouldBeStrictlyIncreasing() }
                    holes.shouldBeEmpty()
                    snapshots shouldBeGreaterThan 1
                    val stored = repo.entries()
                    stored.map { it.sequence } shouldContainExactly all.sorted()
                    stored.forEach { it.lines.size shouldBe Posting.linesFor(ac1).size }
                    db.unbalancedUnits().shouldBeEmpty()
                }
            } finally {
                writing.set(false)
                pool.shutdownNow()
            }
        }

    test("L-03 edge: 8 concurrent posts with the same idempotency key give exactly one entry and no extra lines (10x)")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val threads = 8
            val rounds = 10
            val pool = Executors.newFixedThreadPool(threads)
            try {
                repeat(rounds) { r ->
                    val barrier = CyclicBarrier(threads)
                    val outcomes =
                        (0 until threads).map {
                            pool.submit<Result<Long>> {
                                barrier.await()
                                runCatching { repo.post(ac1.copy(idempotencyKey = "RACE-$r")).sequence }
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                    withClue("round $r outcomes $outcomes") {
                        outcomes.count { it.isSuccess } shouldBe 1
                        outcomes.mapNotNull { it.exceptionOrNull() }.forEach { e ->
                            // 23505 unique_violation on idempotency_key; anything else (deadlock, partial) is a defect.
                            (e as? SQLException)?.sqlState shouldBe "23505"
                        }
                    }
                }
                val linesPerEntry = Posting.linesFor(ac1).size
                assertSoftly {
                    db.query(db.owner, "SELECT count(*) FROM ledger.entry") shouldBe listOf(listOf("$rounds"))
                    db.query(db.owner, "SELECT count(*) FROM ledger.line") shouldBe
                        listOf(listOf("${rounds * linesPerEntry}"))
                    db.query(
                        db.owner,
                        "SELECT entry_id FROM ledger.line GROUP BY entry_id HAVING count(*) <> $linesPerEntry",
                    )
                        .shouldBeEmpty()
                    db.unbalancedUnits().shouldBeEmpty()
                }
            } finally {
                pool.shutdownNow()
            }
        }

    // ---------- Atomicity ----------

    test("L-03 edge: an entry balanced in CAD but off by -5 in USD is refused naming USD, and nothing is written")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            repo.post(ac1)
            val before = db.sha256()
            val e =
                shouldThrow<UnbalancedEntryException> {
                    repo.append(
                        EntryRequest(
                            "EDGE-USD-OFF",
                            date,
                            1,
                            listOf(
                                LedgerLine("A", "CAD", Side.DEBIT, 10),
                                LedgerLine("B", "CAD", Side.CREDIT, 10),
                                LedgerLine("A", "USD", Side.DEBIT, 5),
                                LedgerLine("B", "USD", Side.CREDIT, 10),
                            ),
                        ),
                    )
                }
            assertSoftly {
                e.code shouldBe "UNBALANCED"
                e.unit shouldBe "USD"
                e.difference shouldBe -5L
                db.sha256() shouldBe before
            }
        }

    test("L-03 edge: empty, zero, negative and blank lines and a blank key are refused before any write")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val before = db.sha256()
            val bad =
                listOf(
                    balanced("EDGE-EMPTY").copy(lines = emptyList()),
                    balanced("EDGE-ZERO").copy(
                        lines = listOf(LedgerLine("A", "CAD", Side.DEBIT, 0), LedgerLine("B", "CAD", Side.CREDIT, 0)),
                    ),
                    balanced("EDGE-NEG").copy(
                        lines = listOf(LedgerLine("A", "CAD", Side.DEBIT, -1), LedgerLine("B", "CAD", Side.CREDIT, -1)),
                    ),
                    balanced("EDGE-BLANK-ACCT", account = " "),
                    balanced("EDGE-BLANK-UNIT").copy(
                        lines = listOf(LedgerLine("A", "", Side.DEBIT, 1), LedgerLine("B", "", Side.CREDIT, 1)),
                    ),
                    balanced(""),
                    balanced("   "),
                )
            assertSoftly {
                bad.forEach { req -> withClue(req) { shouldThrowAny { repo.append(req) } } }
                db.sha256() shouldBe before
            }
        }

    test("L-03 edge: a database error on the lines insert rolls back the entry row; the key can be posted afterwards")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            repo.post(ac1)
            val before = db.sha256()
            // Passes the in-memory checks but Postgres text cannot hold U+0000, so the line insert fails after the
            // entry insert has already run inside the transaction.
            shouldThrowAny { repo.append(balanced("EDGE-NUL", account = "TP-0001-NR1\u0000")) }
            db.sha256() shouldBe before
            repo.append(balanced("EDGE-NUL")).idempotencyKey shouldBe "EDGE-NUL"
            db.unbalancedUnits().shouldBeEmpty()
        }

    test("L-03 edge: an injected driver error, then a killed backend, between entry and lines insert write nothing")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            val before = db.sha256()
            val faults = listOf("injected SQLException" to injectedSqlError, "backend killed" to killBackend(db.owner))
            assertSoftly {
                faults.forEach { (label, fault) ->
                    withClue(label) {
                        val faulty = FaultyDataSource(db.app, "INSERT INTO ledger.line", setOf("executeBatch"), fault)
                        shouldThrowAny { LedgerRepository(faulty).post(ac1.copy(idempotencyKey = "EDGE-FAULT")) }
                        faulty.fired shouldBe 1
                        db.sha256() shouldBe before
                        val stuck = "SELECT count(*) FROM pg_stat_activity WHERE state LIKE 'idle in transaction%'"
                        db.query(db.owner, stuck) shouldBe listOf(listOf("0"))
                    }
                }
                val retried = LedgerRepository(db.app).post(ac1.copy(idempotencyKey = "EDGE-FAULT"))
                retried.idempotencyKey shouldBe "EDGE-FAULT"
            }
        }

    test("L-03 edge: one entry with 5000 lines is stored whole and balanced")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val lines =
                (1..2500).flatMap {
                    listOf(LedgerLine("A$it", "CAD", Side.DEBIT, 1), LedgerLine("B", "CAD", Side.CREDIT, 1))
                }
            LedgerRepository(db.app).append(balanced("EDGE-WIDE").copy(lines = lines))
            db.query(db.owner, "SELECT count(*) FROM ledger.line") shouldBe listOf(listOf("5000"))
            LedgerRepository(db.app).entries().single().lines shouldContainExactly lines
        }
})
