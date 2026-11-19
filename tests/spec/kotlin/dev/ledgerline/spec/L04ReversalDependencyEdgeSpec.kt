package dev.ledgerline.spec

import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LedgerDb
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * L-04 phase B attack tests, round 1: reversals and corrections of a buy that later sells depend on (invariant 7).
 *
 * OWNER DECISION (not a bug): `LedgerRepository.correct()` counts the replacement activity in the NEGATIVE_SHARES
 * check, so correcting a buy that a later sell depends on is allowed when the replacement keeps enough shares, while a
 * plain `reverse()` of the same buy is refused. The two "correct" tests below pin that behaviour both ways. If the
 * owner prefers to ignore the replacement, the "allowed" test changes and nothing else.
 */
class L04ReversalDependencyEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val nr1 = Activities.ACCOUNT
    val aaa = LineMath.securityUnit("SYN-AAA")
    val buy = L03Activities.ac1 // 2025-01-07, 100 SYN-AAA
    val sell = L03Activities.ac4[3].copy(quantity = 60 * SHARE) // TP-0001-2025-0004, 2025-02-03, sells 60

    class Ctx(val db: LedgerDb, val repo: LedgerRepository) {
        fun balances() = Balances.ofEntries(repo.entries())

        fun shares(account: String = nr1) = Balances.balance(balances(), account, aaa)
    }

    fun withBuyAndSell(): Triple<Ctx, Long, Long> {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        repo.post(L03Activities.ac4[0])
        val b = repo.post(buy)
        val s = repo.post(sell)
        return Triple(Ctx(db, repo), b.sequence, s.sequence)
    }

    fun code(block: () -> Unit) = shouldThrow<ReversalRefusedException>(block).code

    test("L-04 edge: reversing a buy while a later sell depends on it is refused NEGATIVE_SHARES and writes nothing")
        .config(tags = setOf(Integration)) {
            val (t, buySeq, _) = withBuyAndSell()
            val before = t.db.sha256()
            code { t.repo.reverse(buySeq, "REV-BUY", "x") } shouldBe "NEGATIVE_SHARES"
            assertSoftly {
                t.db.sha256() shouldBe before
                t.repo.statusOf(buySeq).state.name shouldBe "POSTED"
                t.shares() shouldBe 40 * SHARE
            }
        }

    test("L-04 edge: once the sell is reversed first, the buy can be reversed; the reverse order is refused")
        .config(tags = setOf(Integration)) {
            val (t, buySeq, sellSeq) = withBuyAndSell()
            t.repo.reverse(sellSeq, "REV-SELL", "x")
            t.shares() shouldBe 100 * SHARE
            t.repo.reverse(buySeq, "REV-BUY", "x")
            t.shares() shouldBe 0L
        }

    test("L-04 edge: a buy that only a later sell on ANOTHER account depends on can be reversed")
        .config(tags = setOf(Integration)) {
            val (t, _, _) = withBuyAndSell()
            val other = Activities.ACCOUNT.replace("NR1", "NR2")
            val buy2 = t.repo.post(buy.copy(idempotencyKey = "OTHER-BUY", accountId = other))
            t.repo.reverse(buy2.sequence, "REV-OTHER", "x")
            assertSoftly {
                t.shares(other) shouldBe 0L
                t.shares() shouldBe 40 * SHARE
            }
        }

    test("L-04 edge: reversing a transfer-in that a later sell depends on is refused NEGATIVE_SHARES")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val transfer =
                repo.post(
                    Activities.activity(
                        "XFER-IN",
                        ActivityType.TRANSFER_IN,
                        LocalDate.of(2025, 1, 7),
                        amountMinor = 45_000L,
                        securityId = "SYN-AAA",
                        quantity = 100 * SHARE,
                        sourceSeq = 2,
                    ),
                )
            repo.post(sell)
            code { repo.reverse(transfer.sequence, "REV-XFER", "x") } shouldBe "NEGATIVE_SHARES"
        }

    test(
        "L-04 edge (owner decision): correct() of a buy a later sell needs is ALLOWED if the replacement suffices",
    )
        .config(tags = setOf(Integration)) {
            val (t, buySeq, _) = withBuyAndSell()
            val result =
                t.repo.correct(
                    buySeq,
                    "COR-BUY",
                    "x",
                    buy.copy(idempotencyKey = "BUY-C1", quantity = 70 * SHARE, amountMinor = 210_875L),
                )
            assertSoftly {
                result.reversal.reversesSequence shouldBe buySeq
                t.shares() shouldBe 10 * SHARE
            }
        }

    test(
        "L-04 edge (owner decision): correct() is refused NEGATIVE_SHARES if the replacement is too small or late",
    )
        .config(tags = setOf(Integration)) {
            val (t, buySeq, _) = withBuyAndSell()
            val before = t.db.sha256()
            val small = buy.copy(idempotencyKey = "BUY-C-SMALL", quantity = 50 * SHARE, amountMinor = 150_000L)
            val late = buy.copy(idempotencyKey = "BUY-C-LATE", effectiveDate = LocalDate.of(2025, 3, 1))
            val exactlyEnough = buy.copy(idempotencyKey = "BUY-C-EXACT", quantity = 60 * SHARE, amountMinor = 180_000L)
            code { t.repo.correct(buySeq, "COR-SMALL", "x", small) } shouldBe "NEGATIVE_SHARES"
            code { t.repo.correct(buySeq, "COR-LATE", "x", late) } shouldBe "NEGATIVE_SHARES"
            t.db.sha256() shouldBe before
            // boundary: ending at exactly 0 shares is fine
            t.repo.correct(buySeq, "COR-EXACT", "x", exactlyEnough)
            t.shares() shouldBe 0L
        }

    test("L-04 edge: huge holdings: reversing an unrelated small buy is not broken by the sum of other holdings")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val huge = 6_000_000_000_000_000_000L // two of these exceed Long.MAX_VALUE (9.22e18)
            repo.post(buy.copy(idempotencyKey = "HUGE-1", quantity = huge, amountMinor = 1_000L, sourceSeq = 1))
            repo.post(buy.copy(idempotencyKey = "HUGE-2", quantity = huge, amountMinor = 1_000L, sourceSeq = 2))
            val small =
                repo.post(
                    buy.copy(idempotencyKey = "SMALL", quantity = SHARE, amountMinor = 3_000L, sourceSeq = 3),
                )
            val outcome = runCatching { repo.reverse(small.sequence, "REV-SMALL", "x") }
            withClue(outcome) { outcome.isSuccess shouldBe true }
        }
})
