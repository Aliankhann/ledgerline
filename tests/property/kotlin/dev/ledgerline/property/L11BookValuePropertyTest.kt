package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Activities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate

/**
 * L-11 properties (provisional contract in `L11BookValueSpec`). For random buy/ROC histories over up to three
 * securities: book value equal to ACB gives zero diffs; one injected difference gives exactly one non-zero diff
 * carrying at least one reason with a known rule ID and entry IDs from that pool's history.
 */
class L11BookValuePropertyTest : FunSpec({
    val engine = AcbEngine(CaIta2026v1)

    fun history(rows: List<Triple<Int, Long, Long>>): List<PostedActivity> =
        rows.mapIndexed { i, (sec, shares, gross) ->
            L06Activities.trade(
                key = "P$i",
                type = ActivityType.BUY,
                date = LocalDate.of(2025, 1, 1).plusDays(i.toLong()),
                security = "SYN-S$sec",
                shares = shares * 1_000_000L,
                grossCents = gross,
                commissionCents = 495,
                seq = i.toLong() + 1,
            )
        }

    val row = Arb.bind(Arb.int(0..2), Arb.long(1L..500L), Arb.long(100L..5_000_000L)) { a, b, c -> Triple(a, b, c) }
    val rows = Arb.list(row, 1..12)

    test("L-11 property: book value equal to ACB gives zero diffs") {
        checkAll(rows) { r ->
            val pools = engine.replay(history(r)).pools
            val book = pools.mapValues { CaIta2026v1.toReportedCents(it.value.totalAcb) }
            val diffs = BookValueComparison.compare(pools, book)
            diffs.size shouldBe pools.size
            diffs.forEach {
                it.differenceCents shouldBe 0L
                it.reasons.shouldBeEmpty()
            }
        }
    }

    test("L-11 property: one injected difference gives exactly one diff with at least one reason") {
        checkAll(rows, Arb.int(0..1000), Arb.long(1L..1_000_000L)) { r, pick, delta ->
            val pools = engine.replay(history(r)).pools
            val keys = pools.keys.sortedBy { it.securityId }
            val victim = keys[pick % keys.size]
            val signed = if (pick % 2 == 0) delta else -delta
            val book = pools.mapValues { CaIta2026v1.toReportedCents(it.value.totalAcb) }.toMutableMap()
            book[victim] = book.getValue(victim) + signed
            val nonZero = BookValueComparison.compare(pools, book).filter { it.differenceCents != 0L }
            nonZero.size shouldBe 1
            val d = nonZero.single()
            d.key shouldBe victim
            d.differenceCents shouldBe -signed
            d.reasons.shouldNotBeEmpty()
            val ids = pools.getValue(victim).history.map { it.entryId }
            d.reasons.forEach { reason ->
                CaIta2026v1.ruleIds shouldContain reason.ruleId
                reason.entryIds.shouldNotBeEmpty()
                reason.entryIds.forEach { ids shouldContain it }
            }
        }
    }
})
