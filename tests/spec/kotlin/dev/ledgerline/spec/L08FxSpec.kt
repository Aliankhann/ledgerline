package dev.ledgerline.spec

import dev.ledgerline.calc.fx.FxRate
import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.FxRateUnavailableException
import dev.ledgerline.calc.fx.ValetResponse
import dev.ledgerline.testsupport.L08Fx
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

/**
 * Story L-08 (docs/stories/L-08-usd-cad-fx.md), pure: no database, no network. Rules `FX-*` of docs/tax-model.md.
 * Data: real Bank of Canada Valet responses in `tests/fixtures/l08/` (README cites the URLs). Selection is a project
 * rule plus ITA-261(1) "closest preceding day for which such a rate is quoted" (FX-FALLBACK).
 *
 * API contract (package `dev.ledgerline.calc.fx`; Dev may add members, not change these):
 * - `class ValetResponse(val json: String, val start: LocalDate, val end: LocalDate)`: one raw Valet response and the
 *   inclusive range it was requested for (the response itself does not say; the range is the declared coverage).
 * - `data class FxRate(val date: LocalDate, val rateScaled: Long)`: the date of the rate USED and the rate at 10^-4
 *   (`FX-SCALE`; "1.4371" is 14371).
 * - `class FxRateCache` with `companion fun fromValetJson(responses: List<ValetResponse>): FxRateCache`. Parses `d` and
 *   the string `v` with integer arithmetic only (no Double/Float/BigDecimal). `v` with more than 4 decimals throws
 *   `IllegalArgumentException`; fewer decimals are padded ("1.4" is 14000). Coverage is the union of the ranges.
 * - `fun rateFor(date: LocalDate): FxRate`: the rate of `date`, else of the closest preceding published date
 *   inside the same covered range (`FX-FALLBACK`, `FX-COVERAGE`). A date outside every range, or whose range has no
 *   rate on or before it, throws `FxRateUnavailableException` (a `RuntimeException`) whose message contains `FXUSDCAD`
 *   and the ISO date. A later rate is never used.
 * - `fun rateFor(currency: String, date: LocalDate): FxRate?`: `null` for "CAD" with no lookup (`FX-CAD-REQUIRED`);
 *   "USD" as above. `val lookupCount: Int` counts actual lookups.
 * - `fun toCadInternal(amountUsdMinor: Long, rate: FxRate): Long`: exact product, CAD at 10^-4 cent (`FX-SCALE`).
 *   `Math.multiplyExact`; overflow throws `ArithmeticException`.
 * - `fun toCadCents(rules: TaxRuleSet, amountUsdMinor: Long, rate: FxRate): Long`: the above through
 *   `rules.toReportedCents` (`ROUND-MODE`).
 *
 * Not covered here (needs the ledger, a later phase): the "no ledger entry is posted" half of AC11, and proof of "no
 * network call" in AC13 (this spec only uses in-memory strings).
 */
class L08FxSpec : FunSpec({

    val cache = L08Fx.cache()

    fun used(d: String) = cache.rateFor(LocalDate.parse(d))

    fun expect(
        trade: String,
        usedDate: String,
        scaled: Long,
    ) = used(trade) shouldBe FxRate(LocalDate.parse(usedDate), scaled)

    test("L-08 AC1: a business day uses its own rate") { expect("2025-03-06", "2025-03-06", 14309) }

    test("L-08 AC2: Saturday and Sunday use Friday") {
        expect("2025-03-08", "2025-03-07", 14371)
        expect("2025-03-09", "2025-03-07", 14371)
    }

    test("L-08 AC3: the first business day after a weekend uses its own rate") {
        expect("2025-03-10", "2025-03-10", 14431)
    }

    test("L-08 AC4: Thanksgiving long weekend uses Friday's date (not Thursday, same value)") {
        for (d in listOf("2025-10-11", "2025-10-12", "2025-10-13")) expect(d, "2025-10-10", 14000)
    }

    test("L-08 AC5: the first business day after the long weekend uses its own rate") {
        expect("2025-10-14", "2025-10-14", 14046)
    }

    test("L-08 AC6: a mid-week holiday uses the prior day, the next day its own") {
        expect("2025-07-01", "2025-06-30", 13643)
        expect("2025-07-02", "2025-07-02", 13622)
    }

    test("L-08 AC7: Good Friday weekend uses Thursday; Easter Monday has its own rate") {
        for (d in listOf("2025-04-18", "2025-04-19", "2025-04-20")) expect(d, "2025-04-17", 13857)
        expect("2025-04-21", "2025-04-21", 13820)
    }

    test("L-08 AC8: a four-day gap uses 2025-12-24; the Monday after uses its own") {
        for (d in listOf("2025-12-25", "2025-12-26", "2025-12-27", "2025-12-28")) expect(d, "2025-12-24", 13674)
        expect("2025-12-29", "2025-12-29", 13686)
    }

    test("L-08 AC9: across the year boundary") {
        expect("2026-01-01", "2025-12-31", 13706)
        expect("2026-01-02", "2026-01-02", 13737)
        expect("2026-01-03", "2026-01-02", 13737)
        expect("2026-01-04", "2026-01-02", 13737)
    }

    // OWNER-INPUT / NEEDS SOURCED EXAMPLE: no published worked example converts a USD trade at a stated rate.
    xtest("L-08 AC10: converted CAD amount (NEEDS SOURCED EXAMPLE; blocked, no value invented)") {}

    test("L-08 AC10 [FX-SCALE structural, not a tax value]: conversion is exact at 10^-4 cent") {
        // Structure only: 100 cents x 1.4000 = 140.0000 cents; asserts exactness, not a sourced amount.
        val r = FxRate(LocalDate.parse("2025-10-10"), 14000)
        cache.toCadInternal(100, r) shouldBe 100L * 14000L
        cache.toCadInternal(0, r) shouldBe 0L
        shouldThrow<ArithmeticException> { cache.toCadInternal(Long.MAX_VALUE, r) }
    }

    test("L-08 AC11: no rate on or before the trade date is a rejection naming FXUSDCAD and the date") {
        val e = shouldThrow<FxRateUnavailableException> { used("2025-03-01") }
        e.message!! shouldContain "FXUSDCAD"
        e.message!! shouldContain "2025-03-01"
    }

    test("L-08 AC11 [FX-COVERAGE]: dates in an uncovered gap, after coverage, or before it are rejected") {
        for (d in listOf("2025-03-02", "2025-04-01", "2026-01-06", "2030-01-01")) {
            shouldThrow<FxRateUnavailableException> { used(d) }.message!! shouldContain d
        }
    }

    test("L-08 AC11: a later rate is never used for a date before the first observation of a range") {
        // 2025-04-14 is before range 2 (starts 2025-04-15); 2025-03-14 (range 1) must not be borrowed across the gap.
        shouldThrow<FxRateUnavailableException> { used("2025-04-14") }
    }

    test("L-08 AC12: CAD activities skip FX and perform no lookup") {
        val fresh = L08Fx.cache()
        fresh.rateFor("CAD", LocalDate.parse("2025-03-08")) shouldBe null
        fresh.lookupCount shouldBe 0
        fresh.rateFor("USD", LocalDate.parse("2025-03-08")) shouldBe FxRate(LocalDate.parse("2025-03-07"), 14371)
        fresh.lookupCount shouldBe 1
    }

    test("L-08 AC13: selections are identical across two runs and across cache instances (in-memory data only)") {
        val dates = generateSequence(LocalDate.parse("2025-03-03")) { it.plusDays(1) }.take(12).toList()
        val a = dates.map { cache.rateFor(it) }
        val b = dates.map { cache.rateFor(it) }
        val c = dates.map { L08Fx.cache().rateFor(it) }
        a shouldBe b
        a shouldBe c
    }

    test("L-08 AC14: rates are fixed-point integers; 1.4 equals 1.4000") {
        used("2025-03-07").rateScaled shouldBe 14371L

        fun one(v: String) =
            FxRateCache
                .fromValetJson(listOf(valet("2025-03-03", v)))
                .rateFor(LocalDate.parse("2025-03-03"))
                .rateScaled
        one("1.4000") shouldBe 14000L
        one("1.4") shouldBe 14000L
        one("1.4000") shouldBe one("1.4")
    }

    test("L-08 AC14 [FX-SCALE]: a value with more than four decimals is rejected, not truncated") {
        shouldThrow<IllegalArgumentException> {
            FxRateCache.fromValetJson(listOf(valet("2025-03-03", "1.43711")))
        }
    }
})

private fun valet(
    date: String,
    v: String,
) = ValetResponse(
    """{"observations":[{"d":"$date","FXUSDCAD":{"v":"$v"}}]}""",
    LocalDate.parse(date),
    LocalDate.parse(date),
)
