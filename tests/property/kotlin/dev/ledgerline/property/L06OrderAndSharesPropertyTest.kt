package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStep
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.RejectionCode
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.checkAll
import kotlin.random.Random

/**
 * Invariants 6 (order independence) and 7 (no negative shares) for the L-06 pool engine, rule set `CA-ITA-2026.1`.
 * The API contract is in the KDoc of `dev.ledgerline.spec.L06PooledAcbSpec`. Streams come from [L06Streams] in
 * canonical order; the tests shuffle them to simulate arrival order. No expected tax value is computed here: the
 * oracle only counts shares.
 */
class L06OrderAndSharesPropertyTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)

    fun isNonRegisteredBuy(p: PostedActivity): Boolean =
        p.activity.activityType == ActivityType.BUY && !L06Streams.isRegistered(p)

    /** Shares-only oracle: walking canonical order, the keys of the sells that ask for more than the pool holds. */
    fun expectedRejections(stream: List<PostedActivity>): List<Triple<String, Long, Long>> {
        val held = mutableMapOf<PoolKey, Long>()
        val out = mutableListOf<Triple<String, Long, Long>>()
        stream.filterNot(L06Streams::isRegistered).forEach { p ->
            val k = L06Streams.key(p)
            val h = held[k] ?: 0L
            val q = p.activity.quantity ?: 0L
            when (p.activity.activityType) {
                ActivityType.BUY -> held[k] = h + q
                ActivityType.SELL -> if (q > h) out += Triple(p.activity.idempotencyKey, h, q) else held[k] = h - q
                else -> Unit
            }
        }
        return out
    }

    test(
        "Invariant 6 (L-06 AC7) [CA-ITA-2026.1]: any arrival order gives an equal replay and output hash (500 cases)",
    ) {
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 30)) { stream ->
                val canonical = engine.replay(stream)
                repeat(3) { n ->
                    val arrival = stream.shuffled(Random(stream.size * 31 + n))
                    val replayed = engine.replay(arrival)
                    replayed shouldBe canonical
                    replayed.outputHash shouldBe canonical.outputHash
                }
                engine.replay(stream.reversed()) shouldBe canonical
            }
        println("L-06 invariant 6: ran ${ctx.attempts()} generated streams, 4 arrival orders each")
        ctx.attempts() shouldBeGreaterThanOrEqual 500
    }

    test("Invariant 7 (L-06 AC6) [CA-ITA-2026.1]: replay rejects exactly the oversells, never clips (500 cases)") {
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 30, overSellPercent = 25)) { stream ->
                val r = engine.replay(stream.shuffled(Random(stream.size.toLong())))
                val actual = r.rejections.map { Triple(it.idempotencyKey, it.held, it.requested) }
                actual shouldBe expectedRejections(stream)
                r.rejections.forEach { it.code shouldBe RejectionCode.INSUFFICIENT_SHARES }
                val rejected = r.rejections.map { it.idempotencyKey }.toSet()
                val rejectedIds = stream.filter { it.activity.idempotencyKey in rejected }.map { it.activityId }.toSet()
                r.pools.values.forEach { pool ->
                    var shares = 0L
                    pool.history.forEach { row ->
                        shares += row.sharesDelta
                        shares shouldBeGreaterThanOrEqual 0L
                    }
                    shares shouldBe pool.shares
                    pool.totalAcb shouldBeGreaterThanOrEqual 0L
                    pool.history.none { it.activityId in rejectedIds } shouldBe true
                }
            }
        println("L-06 invariant 7 (replay): ran ${ctx.attempts()} generated streams")
        ctx.attempts() shouldBeGreaterThanOrEqual 500
    }

    test("Invariant 7 (L-06 AC6) [CA-ITA-2026.1]: a sell over the held shares is rejected; the pool is unchanged") {
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 12, overSellPercent = 0)) { stream ->
                val buys = stream.filter(::isNonRegisteredBuy)
                if (buys.isEmpty()) return@checkAll
                val k = L06Streams.key(buys.first())
                val poolBuys = buys.filter { L06Streams.key(it) == k }
                var pool = AcbPool.empty(k.taxpayerId, k.securityId)
                poolBuys.forEach { pool = engine.apply(pool, it).pool }
                val base = poolBuys.last()
                val sell =
                    base.activity.copy(
                        idempotencyKey = "over",
                        activityType = ActivityType.SELL,
                        effectiveDate = base.activity.effectiveDate.plusDays(1),
                        quantity = pool.shares + 1,
                    )
                val over = base.copy(activityId = "act-over", entryId = 1L, activity = sell)
                val gate = engine.check(pool, over.activity)
                gate?.code shouldBe RejectionCode.INSUFFICIENT_SHARES
                gate?.held shouldBe pool.shares
                gate?.requested shouldBe pool.shares + 1
                val step = engine.apply(pool, over).shouldBeInstanceOf<PoolStep.Rejected>()
                step.pool shouldBe pool
                step.rejection shouldBe gate

                val all =
                    over.copy(
                        activityId = "act-all",
                        activity = sell.copy(idempotencyKey = "all", quantity = pool.shares),
                    )
                val emptied = engine.apply(pool, all).shouldBeInstanceOf<PoolStep.Applied>().pool
                emptied.shares shouldBe 0L
                emptied.totalAcb shouldBe 0L
            }
        ctx.attempts() shouldBeGreaterThanOrEqual 500
    }

    test("Invariant 7 [CA-ITA-2026.1]: a sell dated before its buys is rejected even when it arrives last") {
        checkAll(300, L06Streams.stream(maxLength = 10, overSellPercent = 0)) { stream ->
            val buys = stream.filter(::isNonRegisteredBuy)
            if (buys.isEmpty()) return@checkAll
            val k = L06Streams.key(buys.first())
            val first = buys.first { L06Streams.key(it) == k }
            val early =
                first.copy(
                    activityId = "act-early",
                    entryId = 1L,
                    activity =
                        first.activity.copy(
                            idempotencyKey = "early",
                            activityType = ActivityType.SELL,
                            effectiveDate = first.activity.effectiveDate.minusDays(1),
                            quantity = 1L,
                        ),
                )
            val r = engine.replay(buys + early)
            val rejection = r.rejections.single { it.idempotencyKey == "early" }
            rejection.held shouldBe 0L
            rejection.requested shouldBe 1L
        }
    }
})
