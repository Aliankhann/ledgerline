package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.ledger.SubmitOutcome
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Env
import dev.ledgerline.testsupport.L09F1
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.inboxEventIds
import dev.ledgerline.testsupport.pollUntilInbox
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Phase B attack on the B-029 fix (a correction's replacement gets a `ledger.activity` row and a
 * `ledger.correction` link, so the consumer judges reversal and replacement as one unit). Source: CLAUDE.md
 * invariants 2, 5 and 7, and story L-09 (fixture F1, AC1: 100 bought, 60 sold, 20 bought; correcting the 100 to 110
 * gives 70 shares). Every expected value is a hand count of shares, no tax value is reasoned here.
 *
 * All four tests are [Integration] (Postgres and Redpanda); they compile without Docker and run in CI.
 */
class B029FixAttackSpec : FunSpec({
    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val integration = setOf(Integration)
    val engine = AcbEngine(CaIta2026v1)

    fun consumer(
        e: L05Env,
        group: String,
    ) = PoolConsumer(e.db.app, e.consumerConfig(group), engine, e.topic)

    fun publishAndConsume(
        e: L05Env,
        c: PoolConsumer,
    ) {
        e.draining { check(e.awaitDelivered(Duration.ofSeconds(120)).isEmpty()) { "outbox not drained" } }
        c.pollUntilInbox(e.db, e.events().size)
        e.db.inboxEventIds().size shouldBe e.events().size
    }

    fun entryOf(
        posted: List<dev.ledgerline.calc.PostedActivity>,
        key: String,
    ) = posted.single { it.activity.idempotencyKey == key }.entryId

    fun aaaShares(e: L05Env) = L10.livePool(e.db, L10.TP1, L10.AAA)!!.first

    fun shouldHaveZeroDrift(
        e: L05Env,
        context: String,
    ) {
        val run = L10.run(e.db.app)
        withClue("$context:\n${run.out}") {
            run.exit shouldBe 0
            L10.summary(run.out).drift shouldBe 0
        }
    }

    test("B-029 attack 1: correcting a replacement again, both corrections written before the consumer runs")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })
            val buy0002 = entryOf(posted, "TP-0001-2025-0002")

            // First correction: 100 -> 110 shares (story AC1). Second: the replacement itself, 110 -> 120.
            val first =
                e.repo.correct(
                    buy0002,
                    "TP-0001-2025-0002-COR",
                    "late fix",
                    L09F1.replacement(110, 331_375).activity,
                )
            val second =
                e.repo.correct(
                    first.replacement.sequence,
                    "TP-0001-2025-0002-COR2",
                    "fix the fix",
                    L09F1.replacement(120, 361_500).activity.copy(idempotencyKey = "TP-0001-2025-0002-C2"),
                )

            consumer(e, "b029-1").use { c ->
                // The consumer is behind: the reversal of entry 2 is read while the replacement C1 is already reversed.
                publishAndConsume(e, c)
            }
            // 120 - 60 + 20, hand count. The sell of 60 on 2025-06-02 is never rejected.
            aaaShares(e) shouldBe 80L * Activities.SHARE
            shouldHaveZeroDrift(e, "after a chain of two corrections")
        }

    test("B-029 attack 2: a replacement reversed on its own behaves like any standalone reversal")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })

            // (a) Correct the late BUY of 20 (nothing later depends on it) to 25, then reverse the replacement alone.
            val fix0005 =
                e.repo.correct(
                    entryOf(posted, "TP-0001-2025-0005"),
                    "TP-0001-2025-0005-COR",
                    "late fix",
                    L09F1.trade(
                        "TP-0001-2025-0005-C1",
                        ActivityType.BUY,
                        L09F1.d(9, 15),
                        L09F1.AAA,
                        25,
                        78_750,
                        0,
                        seq = 5,
                    )
                        .activity,
                )
            e.repo.reverse(fix0005.replacement.sequence, "TP-0001-2025-0005-C1-REV", "undo the fix")

            // (b) Correct the BUY of 100 to 110, then try to reverse that replacement alone: the sell of 60 would have
            // nothing behind it (entry 2 is already reversed), so the ledger must refuse and write nothing.
            val fix0002 =
                e.repo.correct(
                    entryOf(posted, "TP-0001-2025-0002"),
                    "TP-0001-2025-0002-COR",
                    "late fix",
                    L09F1.replacement(110, 331_375).activity,
                )
            val before = e.db.sha256()
            val refused =
                shouldThrow<ReversalRefusedException> {
                    e.repo.reverse(fix0002.replacement.sequence, "TP-0001-2025-0002-C1-REV", "undo")
                }
            refused.code shouldBe ReversalRefusedException.NEGATIVE_SHARES
            e.db.sha256() shouldBe before

            consumer(e, "b029-2").use { c -> publishAndConsume(e, c) }
            // Entry 5 and its replacement are both gone; entry 2 became 110: 110 - 60.
            aaaShares(e) shouldBe 50L * Activities.SHARE
            shouldHaveZeroDrift(e, "after correct-then-reverse and a refused reversal")
        }

    test("B-029 attack 3: the replacement's key submitted again through ingestion is a duplicate or a mismatch")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })
            val replacement = L09F1.replacement(110, 331_375).activity
            val result =
                e.repo.correct(
                    entryOf(posted, "TP-0001-2025-0002"),
                    "TP-0001-2025-0002-COR",
                    "late fix",
                    replacement,
                )

            consumer(e, "b029-3").use { c ->
                publishAndConsume(e, c)
                val pools = PoolStore(e.db.app).pools()
                val before = e.db.sha256()

                // Same payload: invariant 2, a no-op that names the replacement's entry.
                val again = e.repo.submit(replacement, Instant.parse("2025-12-31T00:00:00Z"))
                (again as? SubmitOutcome.Duplicate)?.entryId shouldBe result.replacement.sequence
                // Same key, different payload (one more share): rejected.
                val changed = replacement.copy(quantity = 111L * Activities.SHARE)
                e.repo.submit(changed, Instant.parse("2025-12-31T00:00:00Z")) shouldBe SubmitOutcome.Mismatch

                withClue("neither submission writes a ledger, activity or outbox row") { e.db.sha256() shouldBe before }
                e.pending().size shouldBe 0
                publishAndConsume(e, c)
                PoolStore(e.db.app).pools() shouldBe pools
            }
            aaaShares(e) shouldBe 70L * Activities.SHARE
            shouldHaveZeroDrift(e, "after resubmitting the replacement's key")
        }

    test("B-029 attack 4: a replacement that leaves the later sell short is refused or stalls, never clipped")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })
            val buy0002 = entryOf(posted, "TP-0001-2025-0002")
            val poolsBefore = PoolStore(e.db.app).pools()

            // (a) The replacement is dated after the sell of 60 on 2025-06-02: 100 shares, but none held on that day.
            val dbBefore = e.db.sha256()
            val late =
                L09F1.trade(
                    "TP-0001-2025-0002-C1",
                    ActivityType.BUY,
                    LocalDate.of(2025, 7, 1),
                    L09F1.AAA,
                    100,
                    301_250,
                    0,
                    seq = 2,
                )
                    .activity
            shouldThrow<ReversalRefusedException> {
                e.repo.correct(buy0002, "TP-0001-2025-0002-COR", "date moved past the sell", late)
            }.code shouldBe ReversalRefusedException.NEGATIVE_SHARES
            withClue("a refused correction writes nothing") { e.db.sha256() shouldBe dbBefore }

            // (b) Same effective date, but settling on 2025-06-05, after the sell: the tax date (settlement) puts the
            // buy behind the sell. The ledger checks effective dates, so it may accept; then the consumer must stall
            // on the reversal event and leave the pool as it was, never apply a clipped sell.
            val settlesLate =
                L09F1.replacement(100, 301_250).activity.copy(settlementDate = LocalDate.of(2025, 6, 5))
            val refusedOrStalled =
                try {
                    e.repo.correct(buy0002, "TP-0001-2025-0002-COR", "settles after the sell", settlesLate)
                    false
                } catch (x: ReversalRefusedException) {
                    x.code shouldBe ReversalRefusedException.NEGATIVE_SHARES
                    e.db.sha256() shouldBe dbBefore
                    true
                }
            if (!refusedOrStalled) {
                e.draining { check(e.awaitDelivered(Duration.ofSeconds(120)).isEmpty()) { "outbox not drained" } }
                consumer(e, "b029-4").use { c ->
                    shouldThrow<Exception> { repeat(20) { c.pollOnce(100) } }
                }
                PoolStore(e.db.app).pools() shouldBe poolsBefore
            }
            aaaShares(e) shouldBe 60L * Activities.SHARE // 100 - 60 + 20, unchanged
        }
})
