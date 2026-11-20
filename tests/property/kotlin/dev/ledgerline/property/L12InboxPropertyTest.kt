package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.InboxState
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.ReplayHash
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlin.random.Random

/**
 * Invariants 2, 5 and 6 for the consumer's inbox (story L-12), the pure part. Contract, package `dev.ledgerline.calc`
 * (the Docker-backed `PoolConsumer` must apply events through this logic):
 *
 * ```kotlin
 * class InboxState(engine: AcbEngine) {
 *     fun deliver(eventId: Long, activity: PostedActivity): Boolean  // true if applied, false if eventId already seen
 *     val pools: Map<PoolKey, AcbPool>
 *     val seen: Set<Long>
 * }
 * ```
 *
 * Oracle: `engine.replay` of the same activities; no tax value is computed here.
 */
class L12InboxPropertyTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)

    fun canonical(s: List<PostedActivity>) =
        s.sortedWith(
            compareBy({ it.activity.effectiveDate }, { it.activity.sourceSeq }, { it.activity.idempotencyKey }),
        )

    test("L-12: random duplicates and redeliveries through the inbox give the pools of a replay (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 30, big = true)) { stream ->
            val ordered = canonical(stream)
            val r = Random(stream.size * 31L + 7)
            val delivered = mutableListOf<Int>()
            ordered.indices.forEach { i ->
                delivered += i
                repeat(r.nextInt(3)) { delivered += r.nextInt(i + 1) } // redeliver any earlier event, any time
            }
            delivered += ordered.indices.filter { r.nextBoolean() } // a late partial replay from the start
            val inbox = InboxState(engine)
            val applied = delivered.count { inbox.deliver(it.toLong() + 1, ordered[it]) }
            applied shouldBe ordered.size
            inbox.seen shouldBe ordered.indices.map { it.toLong() + 1 }.toSet()
            ReplayHash.of(inbox.pools) shouldBe ReplayHash.of(engine.replay(ordered).pools)
        }
    }

    test("L-12: a second delivery of an event id returns false and leaves the pools untouched (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 20)) { stream ->
            val ordered = canonical(stream)
            val inbox = InboxState(engine)
            ordered.forEachIndexed { i, a -> inbox.deliver(i + 1L, a) shouldBe true }
            val before = ReplayHash.of(inbox.pools)
            ordered.forEachIndexed { i, a -> inbox.deliver(i + 1L, a) shouldBe false }
            ReplayHash.of(inbox.pools) shouldBe before
        }
    }
})
