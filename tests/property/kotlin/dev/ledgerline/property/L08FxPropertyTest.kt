package dev.ledgerline.property

import dev.ledgerline.calc.fx.FxRateUnavailableException
import dev.ledgerline.testsupport.L08Fx
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.LocalDate

/**
 * L-08 property (FX-FALLBACK, FX-COVERAGE). API contract in the KDoc of `dev.ledgerline.spec.L08FxSpec`. The oracle is
 * independent of the cache: published dates are read from the fixture JSON by regex. For any date in
 * 2025-02-01..2026-02-28 either the lookup is rejected, or the used date is published, is <= the trade date, and no
 * published date lies strictly between them (closest preceding). Lookup is deterministic.
 */
class L08FxPropertyTest : FunSpec({
    val cache = L08Fx.cache()
    val published = L08Fx.publishedDates
    val base = LocalDate.parse("2025-02-01")

    test("L-08 [FX-FALLBACK]: selected date is published, <= trade date, closest, deterministic") {
        var accepted = 0
        checkAll(1000, Arb.long(0, 392)) { offset ->
            val trade = base.plusDays(offset)
            val first = runCatching { cache.rateFor(trade) }
            val second = runCatching { L08Fx.cache().rateFor(trade) }
            first.isSuccess shouldBe second.isSuccess
            if (first.isSuccess) {
                accepted++
                val r = first.getOrThrow()
                r shouldBe second.getOrThrow()
                r.date shouldBeLessThanOrEqualTo trade
                (r.date in published) shouldBe true
                published.none { it > r.date && it <= trade } shouldBe true
            } else {
                (first.exceptionOrNull() is FxRateUnavailableException) shouldBe true
            }
        }
        (accepted > 0) shouldBe true
    }
})
