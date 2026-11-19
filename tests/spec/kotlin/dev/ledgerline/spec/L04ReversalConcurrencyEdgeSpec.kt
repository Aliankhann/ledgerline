package dev.ledgerline.spec

import dev.ledgerline.core.Activity
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LedgerDb
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * L-04 phase B attack tests, round 1: N threads race to reverse or correct the same entry. The append lock must make
 * the outcome serial: exactly one reversal, never a duplicate link, a deadlock or a half-written correction.
 * All need Docker.
 */
class L04ReversalConcurrencyEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val deposit = L03Activities.ac4[0]
    val buy = L03Activities.ac1
    val threads = 8
    val rounds = 5

    fun <T> race(
        n: Int,
        job: (Int) -> T,
    ): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val barrier = CyclicBarrier(n)
            return (0 until n).map { i ->
                pool.submit<Result<T>> {
                    barrier.await()
                    runCatching { job(i) }
                }
            }.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    fun LedgerDb.reversalRows(): Int = query(owner, "SELECT count(*) FROM ledger.reversal")[0][0]!!.toInt()

    fun LedgerDb.unbalancedUnits() =
        query(
            owner,
            "SELECT entry_id, unit FROM ledger.line GROUP BY entry_id, unit " +
                "HAVING sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0",
        )

    fun fresh(): Pair<LedgerDb, LedgerRepository> {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        repo.post(deposit)
        return Pair(db, repo)
    }

    test("L-04 edge: $threads threads reversing the same entry under different keys give exactly one reversal")
        .config(tags = setOf(Integration)) {
            repeat(rounds) { r ->
                val (db, repo) = fresh()
                val original = repo.post(buy)
                val outcomes = race(threads) { i -> repo.reverse(original.sequence, "REV-RACE-$r-$i", "race") }
                withClue("round $r outcomes ${outcomes.map { it.exceptionOrNull() ?: "ok" }}") {
                    assertSoftly {
                        outcomes.count { it.isSuccess } shouldBe 1
                        outcomes.mapNotNull { it.exceptionOrNull() }.forEach { e ->
                            (e as? ReversalRefusedException)?.code shouldBe "ALREADY_REVERSED"
                        }
                        db.reversalRows() shouldBe 1
                        repo.entries().count { it.reversesSequence == original.sequence } shouldBe 1
                        db.query(db.owner, "SELECT count(*) FROM ledger.entry")[0][0] shouldBe "3"
                        Balances.balance(Balances.ofEntries(repo.entries()), "TP-0001-NR1", "CAD") shouldBe 1_000_000L
                        db.unbalancedUnits() shouldBe emptyList()
                    }
                }
            }
        }

    test("L-04 edge: $threads threads reversing the same entry under ONE key all get the same reversal")
        .config(tags = setOf(Integration)) {
            repeat(rounds) { r ->
                val (db, repo) = fresh()
                val original = repo.post(buy)
                val outcomes = race(threads) { repo.reverse(original.sequence, "REV-SAME-$r", "race") }
                withClue("round $r outcomes ${outcomes.map { it.exceptionOrNull() ?: "ok" }}") {
                    outcomes.all { it.isSuccess } shouldBe true
                    outcomes.map { it.getOrThrow().sequence }.toSet().size shouldBe 1
                    db.reversalRows() shouldBe 1
                    db.query(db.owner, "SELECT count(*) FROM ledger.entry")[0][0] shouldBe "3"
                }
            }
        }

    fun replacement(i: Int): Activity = buy.copy(idempotencyKey = "TP-0001-2025-0002-C$i", amountMinor = 302_150L)

    test("L-04 edge: $threads threads correcting the same entry under different keys give exactly one correction")
        .config(tags = setOf(Integration)) {
            repeat(rounds) { r ->
                val (db, repo) = fresh()
                val original = repo.post(buy)
                val outcomes =
                    race(threads) { i -> repo.correct(original.sequence, "COR-RACE-$r-$i", "race", replacement(i)) }
                withClue("round $r outcomes ${outcomes.map { it.exceptionOrNull() ?: "ok" }}") {
                    assertSoftly {
                        outcomes.count { it.isSuccess } shouldBe 1
                        outcomes.mapNotNull { it.exceptionOrNull() }.forEach { e ->
                            (e as? ReversalRefusedException)?.code shouldBe "ALREADY_REVERSED"
                        }
                        db.reversalRows() shouldBe 1
                        // deposit, buy, reversal, one replacement: no orphan replacement from a losing thread.
                        db.query(db.owner, "SELECT count(*) FROM ledger.entry")[0][0] shouldBe "4"
                        val balances = Balances.ofEntries(repo.entries())
                        Balances.balance(balances, "TP-0001-NR1", "CAD") shouldBe 697_355L
                        Balances.balance(balances, "TP-0001-NR1", LineMath.securityUnit("SYN-AAA")) shouldBe
                            100_000_000L
                        db.unbalancedUnits() shouldBe emptyList()
                    }
                }
            }
        }

    test("L-04 edge: $threads threads sending the SAME correction all get the same result, two new entries")
        .config(tags = setOf(Integration)) {
            repeat(rounds) { r ->
                val (db, repo) = fresh()
                val original = repo.post(buy)
                val outcomes =
                    race(threads) { repo.correct(original.sequence, "COR-SAME-$r", "race", replacement(0)) }
                withClue("round $r outcomes ${outcomes.map { it.exceptionOrNull() ?: "ok" }}") {
                    outcomes.all { it.isSuccess } shouldBe true
                    outcomes.map { it.getOrThrow().reversal.sequence }.toSet().size shouldBe 1
                    outcomes.map { it.getOrThrow().replacement.sequence }.toSet().size shouldBe 1
                    db.reversalRows() shouldBe 1
                    db.query(db.owner, "SELECT count(*) FROM ledger.entry")[0][0] shouldBe "4"
                }
            }
        }
})
