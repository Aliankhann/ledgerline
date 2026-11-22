package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.InboxState
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import java.time.LocalDate
import kotlin.random.Random

/**
 * Invariant 6 for the consumer path after the B-025 fix: whatever order the events arrive in (and however many are
 * redelivered), [InboxState] ends on the pools of `AcbEngine.replay` of the same set. Oracle: the replay itself; no
 * tax value is computed here. Streams come from [L06Streams] (over-sells included), then get a random settlement date
 * 0-2 days after the trade date so that tax-date order and the stream's order disagree (the B-025 trigger).
 */
class B025ArrivalOrderPropertyTest : FunSpec({
    val engine = AcbEngine(CaIta2026v1)

    fun settled(
        stream: List<PostedActivity>,
        r: Random,
    ): List<PostedActivity> =
        stream.map { p ->
            val d: LocalDate = p.activity.effectiveDate
            val settles = if (r.nextInt(4) == 0) null else d.plusDays(r.nextLong(3))
            p.copy(activity = p.activity.copy(settlementDate = settles))
        }

    fun live(
        arrival: List<PostedActivity>,
        redeliveries: Int = 0,
        r: Random = Random(0),
    ): Map<PoolKey, Pair<Long, Long>> {
        val inbox = InboxState(engine)
        arrival.forEach { inbox.deliver(it.entryId, it) }
        repeat(redeliveries) {
            val p = arrival[r.nextInt(arrival.size)]
            inbox.deliver(p.entryId, p) shouldBe false
        }
        return inbox.pools.mapValues { (_, p) -> p.shares to p.totalAcb }
    }

    fun reference(set: List<PostedActivity>) = engine.replay(set).pools.mapValues { (_, p) -> p.shares to p.totalAcb }

    fun <T> permutations(items: List<T>): Sequence<List<T>> =
        if (items.size <= 1) {
            sequenceOf(items)
        } else {
            items.indices.asSequence().flatMap { i ->
                permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(items[i]) + it }
            }
        }

    test("B-025: every arrival order of a short history gives the replay's pools (invariant 6; 150 cases)") {
        checkAll(150, L06Streams.stream(maxLength = 5, big = true, overSellPercent = 25)) { stream ->
            val set = settled(stream, Random(stream.size * 17L + stream.sumOf { it.activity.amountMinor }))
            val expected = reference(set)
            permutations(set).forEach { arrival -> live(arrival) shouldBe expected }
        }
    }

    test("B-025: shuffled arrival with redeliveries of a long history gives the replay's pools (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 40, big = true)) { stream ->
            val r = Random(stream.size * 31L + stream.sumOf { it.activity.amountMinor })
            val set = settled(stream, r)
            val expected = reference(set)
            repeat(3) { live(set.shuffled(r), redeliveries = r.nextInt(10), r = r) shouldBe expected }
            live(set.reversed()) shouldBe expected
            live(set.sortedByDescending { it.activity.settlementDate ?: it.activity.effectiveDate }) shouldBe expected
        }
    }
})
