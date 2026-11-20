package dev.ledgerline.property

import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.fx.FxRate
import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.FxRateUnavailableException
import dev.ledgerline.calc.fx.ValetResponse
import dev.ledgerline.testsupport.L08Fx
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/** L-08 phase B attacks on `FxRateCache`: coverage edges, overlapping files, malformed JSON, decimals, overflow. */
class L08FxRobustnessTest : FunSpec({
    val cache = L08Fx.cache()

    fun d(s: String) = LocalDate.parse(s)

    fun one(
        date: String,
        body: String,
    ) = ValetResponse("""{"observations":[{"d":"$date",$body}]}""", d(date), d(date))

    fun build(
        date: String,
        v: String,
    ) = FxRateCache.fromValetJson(listOf(one(date, """"FXUSDCAD":{"v":"$v"}""")))

    test("L-08 attack: trade dates before coverage start and after coverage end are rejected") {
        for (s in listOf("2025-03-02", "2025-01-01", "2025-03-01")) shouldThrow<FxRateUnavailableException> {
            cache.rateFor(d(s))
        }
        for (s in listOf("2026-01-06", "2026-02-01", "2030-01-01")) shouldThrow<FxRateUnavailableException> {
            cache.rateFor(d(s))
        }
    }

    test("L-08 attack: the same date in two overlapping files with different rates is not silently resolved") {
        val a = ValetResponse(one("2025-03-05", """"FXUSDCAD":{"v":"1.4370"}""").json, d("2025-03-03"), d("2025-03-07"))
        val b = ValetResponse(one("2025-03-05", """"FXUSDCAD":{"v":"1.9999"}""").json, d("2025-03-05"), d("2025-03-10"))
        shouldThrow<IllegalArgumentException> { FxRateCache.fromValetJson(listOf(a, b)) }
        // identical duplicates are harmless, in either order
        val c = ValetResponse(a.json, d("2025-03-05"), d("2025-03-10"))
        FxRateCache.fromValetJson(listOf(a, c)).rateFor(d("2025-03-05")) shouldBe FxRate(d("2025-03-05"), 14370)
    }

    test("L-08 attack: missing or malformed v is rejected at load, never a zero, negative or default rate") {
        shouldThrow<Exception> { FxRateCache.fromValetJson(listOf(one("2025-03-03", """"FXUSDCAD":{}"""))) }
        shouldThrow<Exception> { FxRateCache.fromValetJson(listOf(one("2025-03-03", """"FXUSDCAD":{"v":null}"""))) }
        shouldThrow<Exception> { FxRateCache.fromValetJson(listOf(one("2025-03-03", """"OTHER":{"v":"1.4"}"""))) }
        for (v in listOf("", "abc", "-1.4371", "1,4371", "1.", ".5", "1e0", " 1.4", "0", "0.0000")) {
            withClue("v='$v'") { runCatching { build("2025-03-03", v) }.isFailure shouldBe true }
        }
    }

    test("L-08 attack: 3 decimals are exact, 5 decimals are rejected or exact, never rounded") {
        build("2025-03-03", "1.437").rateFor(d("2025-03-03")).rateScaled shouldBe 14370L
        build("2025-03-03", "1.4371").rateFor(d("2025-03-03")).rateScaled shouldBe 14371L
        shouldThrow<IllegalArgumentException> { build("2025-03-03", "1.43715") }
        val r = runCatching { build("2025-03-03", "1.43710").rateFor(d("2025-03-03")).rateScaled }
        (r.isFailure || r.getOrNull() == 14371L) shouldBe true
    }

    test("L-08 attack: toCad near overflow fails loudly, never wraps") {
        val rate = cache.rateFor(d("2025-03-07"))
        val rules = CaIta2026v1
        val big = Long.MAX_VALUE / rate.rateScaled
        shouldThrow<ArithmeticException> { cache.toCadInternal(big + 1, rate) }
        shouldThrow<ArithmeticException> { cache.toCadInternal(Long.MAX_VALUE, rate) }
        shouldThrow<ArithmeticException> { cache.toCadInternal(Long.MIN_VALUE, rate) }
        shouldThrow<ArithmeticException> { cache.toCadCents(rules, -(big + 1), rate) }
        cache.toCadInternal(big, rate) shouldBe big * rate.rateScaled
        cache.toCadInternal(0, rate) shouldBe 0L
    }
})
