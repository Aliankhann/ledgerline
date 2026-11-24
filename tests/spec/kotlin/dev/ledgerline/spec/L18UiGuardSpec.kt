package dev.ledgerline.spec

import dev.ledgerline.testsupport.UiSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * L-18 UI guards (AC10, and the UI parts of AC7): source-text checks over `ui/src`, in the style of
 * `L17UiGuardSpec`. `ui/` has no component-test tool (no vitest or testing-library in package.json) and `ui/` is
 * Dev's, so QE adds none; CI still runs `npm run typecheck && npm run build`. Not covered by a guard: "newest first,
 * default the latest" (the years route is newest first, tested in `L18YourYearSpec`; the UI must use it as given) and
 * the minus sign on losses (that is `formatMoney`'s own, tested in the UI build only). The markup contract is on
 * `L18` in `L18.kt`.
 */
class L18UiGuardSpec : FunSpec({

    val ui = UiSource.all()
    val flat = ui.replace("\n", " ")

    test("L-18 AC10: a Your year tab fetches the years and the chosen year with encoded path parts") {
        ui shouldContain "Your year"
        Regex("""/tax-year/\$\{encodeURIComponent\([^)]*\)\}""").containsMatchIn(ui) shouldBe true
        Regex("""/tax-year/\$\{encodeURIComponent\([^)]*\)\}/\$\{[^}]*\}""").containsMatchIn(ui) shouldBe true
    }

    test(
        "L-18 AC10: a year picker is a select with a change handler, and a Download CSV link points at the csv route",
    ) {
        Regex("""<select[^>]*onChange""").containsMatchIn(flat) shouldBe true
        ui shouldContain "Download CSV"
        Regex("""\.csv""").containsMatchIn(ui) shouldBe true
        Regex("""<a[^>]*href=[^>]*\.csv|\.csv[^<]*Download CSV|Download CSV""").containsMatchIn(flat) shouldBe true
    }

    test("L-18 AC10: the summary cards and a Not included panel with the out-of-scope items are present") {
        listOf("Total gains", "Total losses", "Net", "Taxable capital gain", "Superficial losses denied").forEach {
            ui shouldContain it
        }
        ui shouldContain "Not included"
        val lower = ui.lowercase()
        listOf("dividend", "t5008", "registered", "carry").forEach { lower shouldContain it }
    }

    test("L-18 AC10: every money cell and card goes through formatMoney; no float formatting anywhere in the UI") {
        listOf(
            "proceedsCents", "acbSoldCents", "outlaysCents", "gainCents", "superficialLossDeniedCents",
            "gainsCents", "lossesCents", "netCents", "taxableCapitalGainCents", "superficialLossesDeniedCents",
        ).forEach { f -> Regex("""formatMoney\(\s*[\w.?]*$f""").containsMatchIn(ui) shouldBe true }
        listOf("toFixed(", "parseFloat(", "toLocaleString(", "Intl.NumberFormat", "Math.round(").forEach {
            ui shouldNotContain it
        }
        // The only division by 100 is formatMoney's own integer math.
        Regex("""/\s*100\b""").findAll(ui).count() shouldBe 1
    }

    test("L-18 AC7: the UI shows the response warnings and does not hide them behind a filter") {
        ui shouldContain "warnings"
        Regex("""warnings(\?)?\.map\(""").containsMatchIn(ui) shouldBe true
        Regex("""warnings[^;]*\.filter\(""").containsMatchIn(flat) shouldBe false
    }
})
