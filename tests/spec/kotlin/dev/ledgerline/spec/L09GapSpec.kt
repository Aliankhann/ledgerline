package dev.ledgerline.spec

import dev.ledgerline.app.SeedLoader
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.AppFx
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Env
import dev.ledgerline.testsupport.L09F1
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.SeedSupport
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.inboxEventIds
import dev.ledgerline.testsupport.pollUntilInbox
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.util.SplittableRandom

/**
 * Story L-09 gap tests for the traceability gate: the database and consumer parts of AC6, AC7 and AC8.
 * The pure parts of AC1 to AC7 are in [L09RecomputeSpec].
 *
 * Every test here is tagged [Integration] (Docker: Postgres and Redpanda). They compile in a Docker-less environment
 * and run only in CI.
 *
 * NOT COVERED, and why (listed for the owner and Dev, no production change was made):
 * - AC2 and AC7 `ledgerline_pool_recompute_total`: no such counter exists in production (grep of app, calc, ledger).
 * - AC3 and AC4 history endpoint `GET /v1/pools/{taxpayerId}/{securityId}/history`: does not exist.
 * - AC6 "naming `TP-0001-2025-0004` dated `2025-06-02`": `ReversalRefusedException` carries only the code and the
 *   sequence of the entry being reversed, not the sell that would go negative. The code and "nothing written" parts
 *   are tested; the naming part is not.
 * - AC8 "L-10 reports zero drift" is tested through `ReconcileCommand` (exit 0, `drift=0`); the "each 1 to 10 months
 *   back" is read as: the effective date is 1 to 10 months before 2025-12-31, never before 2025-01-01 (the year of
 *   the seed), because the story does not say what the months are counted from.
 */
class L09GapSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val integration = setOf(Integration)
    val engine = AcbEngine(CaIta2026v1, AppFx.cache())

    fun consumer(
        e: L05Env,
        group: String,
    ) = PoolConsumer(e.db.app, e.consumerConfig(group), engine, e.topic)

    /** Relays everything pending, then lets [c] apply every event until the inbox holds one row per outbox event. */
    fun publishAndConsume(
        e: L05Env,
        c: PoolConsumer,
    ) {
        e.draining { check(e.awaitDelivered(Duration.ofSeconds(120)).isEmpty()) { "outbox not drained" } }
        c.pollUntilInbox(e.db, e.events().size)
        e.db.inboxEventIds().size shouldBe e.events().size
    }

    test("L-09 AC6: a correction that would make a later sell negative is refused NEGATIVE_SHARES and writes nothing")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })
            val buy0002 = posted.single { it.activity.idempotencyKey == "TP-0001-2025-0002" }.entryId
            val poolsBefore = PoolStore(e.db.app).pools()
            val dbBefore = e.db.sha256()

            // Story AC6: correct TP-0001-2025-0002 to 30 shares (amount 90375); the sell of 60 on 2025-06-02 would
            // then have only 30 held.
            val tooSmall = L09F1.replacement(30, 90_375).activity.copy(idempotencyKey = "TP-0001-2025-0002-C1")
            val refused =
                shouldThrow<ReversalRefusedException> {
                    e.repo.correct(buy0002, "TP-0001-2025-0002-COR", "late fix", tooSmall)
                }
            refused.code shouldBe ReversalRefusedException.NEGATIVE_SHARES

            withClue("neither the reversal nor the replacement is written (every ledger and outbox row is unchanged)") {
                e.db.sha256() shouldBe dbBefore
            }
            e.pending().size shouldBe 0 // no new outbox row to publish
            PoolStore(e.db.app).pools() shouldBe poolsBefore
            L10.livePool(e.db, L10.TP1, L10.AAA)!!.first shouldBe 60L * Activities.SHARE
        }

    test("L-09 AC7: redelivering the reversal and replacement events changes no pool and no inbox row")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val posted = L10.postAndApply(e, L09F1.f1().map { it.activity })
            val buy0002 = posted.single { it.activity.idempotencyKey == "TP-0001-2025-0002" }.entryId

            // AC1's correction: shares 110, amount 331375.
            val replacement = L09F1.replacement(110, 331_375).activity
            e.repo.correct(buy0002, "TP-0001-2025-0002-COR", "late fix", replacement)

            consumer(e, "l09-ac7").use { c ->
                publishAndConsume(e, c)
                // AC1's number: 110 - 60 + 20 = 70 shares.
                L10.livePool(e.db, L10.TP1, L10.AAA)!!.first shouldBe 70L * Activities.SHARE
                val pools = PoolStore(e.db.app).pools()
                val inbox = e.db.inboxEventIds()

                // Redeliver everything, the reversal and replacement events included: forget the consumer's offsets
                // (as L-12 AC6 does) so that the whole partition is read again from the earliest record.
                L10.execAsOwner(e.db, "DELETE FROM calc.consumer_offset")
                repeat(10) { c.pollOnce(100) }

                PoolStore(e.db.app).pools() shouldBe pools
                e.db.inboxEventIds() shouldContainExactly inbox
            }
            L10.run(e.db.app).exit shouldBe 0
        }

    test("L-09 AC8: seed 42 year, then 20 random back-dated corrections and late activities: zero drift after each")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            SeedLoader.load(e.db.app, SeedSupport.SEED)
            val rnd = SplittableRandom(42)

            // Back-dated corrections move a seeded non-registered BUY earlier; late activities are new BUYs. Both
            // only ever add shares at an earlier date, so none can make a sell negative (invariant 7 stays valid).
            val nonRegistered =
                SeedSupport.expected().filter { it.accountType == AccountType.NON_REGISTERED }
            val correctable =
                nonRegistered.filter { it.activityType == ActivityType.BUY }.toMutableList()
            val taxpayers = nonRegistered.map { it.taxpayerId }.distinct().sorted()
            check(taxpayers.isNotEmpty()) { "seed 42 year has no non-registered taxpayer" }

            fun backDate(): LocalDate =
                maxOf(
                    LocalDate.of(2025, 12, 31).minusMonths(1L + rnd.nextInt(10)).minusDays(rnd.nextInt(28).toLong()),
                    LocalDate.of(2025, 1, 1),
                )

            fun entryIdOf(key: String): Long =
                e.db.query(e.db.owner, "SELECT entry_id FROM ledger.entry WHERE idempotency_key = '$key'")
                    .single()[0]!!.toLong()

            fun lateBuy(n: Int): Activity {
                val tp = taxpayers[rnd.nextInt(taxpayers.size)]
                return Activities.activity(
                    key = "$tp-2025-L09-LATE-$n",
                    type = ActivityType.BUY,
                    date = backDate(),
                    amountMinor = 10_000L + rnd.nextInt(90_000),
                    securityId = "SYN-AAA",
                    quantity = (1L + rnd.nextInt(20)) * Activities.SHARE,
                    commissionMinor = 495,
                    accountId = "$tp-NR1",
                    taxpayerId = tp,
                    sourceSeq = 10_000L + n,
                )
            }

            consumer(e, "l09-ac8").use { c ->
                publishAndConsume(e, c)
                L10.run(e.db.app).exit shouldBe 0 // the seeded year starts clean

                for (n in 1..20) {
                    val correction = n % 2 == 1 && correctable.isNotEmpty()
                    if (correction) {
                        val original = correctable.removeAt(rnd.nextInt(correctable.size))
                        val moved = minOf(backDate(), original.effectiveDate)
                        e.repo.correct(
                            entryIdOf(original.idempotencyKey),
                            "${original.idempotencyKey}-COR$n",
                            "AC8 back-dated correction $n",
                            original.copy(idempotencyKey = "${original.idempotencyKey}-C$n", effectiveDate = moved),
                        )
                    } else {
                        e.post(lateBuy(n))
                    }
                    publishAndConsume(e, c)
                    val run = L10.run(e.db.app)
                    withClue("after change $n (${if (correction) "correction" else "late activity"}):\n${run.out}") {
                        run.exit shouldBe 0
                        L10.summary(run.out).drift shouldBe 0
                    }
                }
            }
        }
})
