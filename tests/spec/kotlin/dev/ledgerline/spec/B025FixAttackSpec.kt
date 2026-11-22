package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.calc.InboxState
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.ScriptedLedger
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.inboxEventIds
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.pollUntilInbox
import dev.ledgerline.testsupport.postAndPublish
import dev.ledgerline.testsupport.runReversalRecompute
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate

/**
 * Phase B attack on the B-025 fix (consumer rebuilds the touched pool by canonical replay). Source: CLAUDE.md
 * invariants 5, 6 and 7 (replay is the reference; order independence; a sell over the holding is rejected, never
 * clipped). Expected pools are `AcbEngine.replay` of the same activities or hand-countable share totals; no tax value
 * is reasoned here.
 */
class B025FixAttackSpec : FunSpec({
    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val engine = AcbEngine(CaIta2026v1)
    val aaa = PoolKey("TP-0001", "SYN-AAA")
    val bbb = PoolKey("TP-0001", "SYN-BBB")
    val other = PoolKey("TP-0002", "SYN-AAA")

    fun buy(
        key: String,
        day: Int,
        shares: Long,
        cents: Long = shares * 100_000L,
        security: String = "SYN-AAA",
        taxpayer: String = "TP-0001",
        settleDay: Int? = null,
    ): Activity =
        Activities.activity(
            key,
            ActivityType.BUY,
            LocalDate.of(2025, 3, day),
            amountMinor = cents,
            securityId = security,
            quantity = shares * Activities.SHARE,
            accountId = "$taxpayer-NR1",
            taxpayerId = taxpayer,
        ).copy(settlementDate = settleDay?.let { LocalDate.of(2025, 3, it) })

    fun sell(
        key: String,
        day: Int,
        shares: Long,
        security: String = "SYN-AAA",
    ): Activity =
        Activities.activity(
            key,
            ActivityType.SELL,
            LocalDate.of(2025, 3, day),
            amountMinor = shares * 120_000L,
            securityId = security,
            quantity = shares * Activities.SHARE,
        )

    fun posted(activities: List<Activity>) = activities.mapIndexed { i, a -> PostedActivity("act-$i", i + 1L, a) }

    fun shown(inbox: InboxState) = inbox.pools.mapValues { (_, p) -> p.shares to p.totalAcb }

    fun replayed(history: List<PostedActivity>) =
        engine.replay(history).pools.mapValues { (_, p) -> p.shares to p.totalAcb }

    test("B-025 attack 1: a sell the replay rejects leaves the pool as it was and later events still apply") {
        val history = posted(listOf(buy("B1", 3, 10), sell("S1", 4, 50), buy("B2", 5, 5)))
        val inbox = InboxState(engine)
        inbox.deliver(1, history[0]) shouldBe true
        val afterBuy = shown(inbox)
        afterBuy[aaa]!!.first shouldBe 10L * Activities.SHARE

        inbox.deliver(2, history[1]) shouldBe true // delivered and recorded, not applied: rejected for 50 > 10
        shown(inbox) shouldBe afterBuy

        inbox.deliver(3, history[2]) shouldBe true // the consumer keeps going
        shown(inbox)[aaa]!!.first shouldBe 15L * Activities.SHARE
        shown(inbox) shouldBe replayed(history)
        // the rejected sell contributes nothing: same pool as the history without it
        shown(inbox) shouldBe replayed(listOf(history[0], history[2]))
    }

    test("B-025 attack 1: a sell rejected on arrival is accepted when its earlier BUY arrives late") {
        val history = posted(listOf(buy("B1", 3, 10), sell("S1", 4, 6)))
        val inbox = InboxState(engine)
        inbox.deliver(2, history[1]) // the SELL arrives first: nothing held, rejected
        inbox.deliver(1, history[0]) // the BUY arrives later with an earlier tax date
        shown(inbox)[aaa]!!.first shouldBe 4L * Activities.SHARE // 10 - 6, hand count
        shown(inbox) shouldBe replayed(history)
    }

    test("B-025 attack 3: redelivering an applied event after later events is a no-op, whatever its payload") {
        val history = posted(listOf(buy("B1", 3, 10), buy("B2", 5, 5), sell("S1", 6, 3)))
        val inbox = InboxState(engine)
        history.forEachIndexed { i, p -> inbox.deliver(i + 1L, p) shouldBe true }
        val before = shown(inbox)
        inbox.deliver(1, history[0]) shouldBe false
        inbox.deliver(2, history[1]) shouldBe false
        inbox.deliver(1, history[2]) shouldBe false // same event id, different payload: still ignored
        shown(inbox) shouldBe before
        inbox.seen shouldBe setOf(1L, 2L, 3L)
    }

    test("B-025 attack 5: events for another security or taxpayer do not alter a pool, even out of tax-date order") {
        val mine = posted(listOf(buy("A1", 3, 10, settleDay = 5), buy("A2", 4, 4)))
        val inbox = InboxState(engine)
        mine.forEachIndexed { i, p -> inbox.deliver(i + 1L, p) }
        val snapshot = inbox.pools[aaa]!!

        // N2 has an earlier tax date than anything in aaa; N3 is rejected, in another pool.
        val noise =
            listOf(
                buy("N1", 6, 7, security = "SYN-BBB"),
                buy("N2", 1, 9, security = "SYN-BBB"),
                sell("N3", 2, 20, security = "SYN-BBB"),
                buy("N4", 1, 11, taxpayer = "TP-0002"),
            )
        posted(noise).forEachIndexed { i, p -> inbox.deliver(100L + i, p) }

        inbox.pools[aaa] shouldBe snapshot
        shown(inbox)[bbb]!!.first shouldBe 16L * Activities.SHARE // 9 + 7; the 20-share sell was rejected
        shown(inbox)[other]!!.first shouldBe 11L * Activities.SHARE
    }

    test("B-025 attack 2 (scripted JDBC): a reversal after a rebuild writes the replay of the remaining history") {
        // History after the reversal of the ROC (entry 1): only the BUY remains. The reference gives 10 shares, ACB 1000.00.
        val buy = buy("R-BUY", 4, 10).copy(settlementDate = LocalDate.of(2025, 3, 5))
        val remaining = listOf(PostedActivity("act-buy", 2, buy))
        val ledger = ScriptedLedger(remaining, "TP-0001" to "SYN-AAA")
        runReversalRecompute(engine, ledger, reversedEntryId = 1, reversalEntryId = 3)
        val expected = engine.replay(remaining).pools.getValue(aaa)
        ledger.poolWrite shouldBe (expected.shares to expected.totalAcb)
        ledger.poolWrite!!.first shouldBe 10L * Activities.SHARE
    }

    test("B-025 attack 2 (scripted JDBC): a reversal of a BUY is applied although the pool holds a rejected sell") {
        // Pool history, entries 1-2 (entry 3, the reversed BUY, is not in the history the query returns). The SELL of 50
        // was rejected when it arrived and is rejected again by every replay; it has nothing to do with the reversed BUY.
        val remaining = posted(listOf(buy("U-B1", 3, 10), sell("U-S1", 4, 50)))
        val ledger = ScriptedLedger(remaining, "TP-0001" to "SYN-AAA")
        // Expected: the consumer applies the reversal (no throw) and the pool is the replay's: 10 shares.
        runReversalRecompute(engine, ledger, reversedEntryId = 3, reversalEntryId = 4)
        ledger.poolWrite!!.first shouldBe 10L * Activities.SHARE
    }

    test("B-025 attack 1+2 (integration): rejected sell in the stream, then more events and a reversal; zero drift")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            CalcMigrations.migrate(e.db.owner)
            // I-S1 (50 shares of 10 held) is rejected by the replay.
            val work =
                listOf(
                    buy("I-B1", 3, 10),
                    sell("I-S1", 4, 50),
                    buy("I-B2", 5, 5),
                    buy("I-X1", 5, 8, security = "SYN-BBB"),
                )
            val history = e.postAndPublish(work)
            PoolConsumer(e.db.app, e.consumerConfig("b025-attack"), engine, e.topic).use { c ->
                c.pollUntilInbox(e.db, 4)
                e.db.inboxEventIds().size shouldBe 4
                val pools = PoolStore(e.db.app).pools()
                pools.getValue(aaa).shares shouldBe 15L * Activities.SHARE
                pools.getValue(bbb).shares shouldBe 8L * Activities.SHARE
                Reconciler(engine).reconcile(pools, history).drift.shouldBeEmpty()

                // reverse the later, unrelated BUY: the consumer must take the reversal and carry on
                e.repo.reverse(history[2].entryId, "I-B2-R", "attack: reverse a BUY beside a rejected SELL")
                e.draining { check(e.awaitDelivered(Duration.ofSeconds(60)).isEmpty()) }
                c.pollUntilInbox(e.db, 5)
                e.db.inboxEventIds().size shouldBe 5
                val after = PoolStore(e.db.app).pools()
                Reconciler(engine).reconcile(after, listOf(history[0], history[1], history[3])).drift.shouldBeEmpty()
                after.getValue(aaa).shares shouldBe 10L * Activities.SHARE
                after.getValue(bbb) shouldBe pools.getValue(bbb)
            }
        }
})
