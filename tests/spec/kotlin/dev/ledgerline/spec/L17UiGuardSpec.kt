package dev.ledgerline.spec

import dev.ledgerline.testsupport.SourceText
import dev.ledgerline.testsupport.UiSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * L-17 UI guards (AC6, and the UI parts of AC7 and AC8): source-text checks over `ui/src`, in the style of L-16's UI
 * guard. `ui/` has no component-test tool (no vitest or testing-library in package.json) and `ui/` is Dev's, so QE adds
 * none; CI still runs `npm run typecheck && npm run build`. The markup contract is on `L17` in `L17.kt`.
 */
class L17UiGuardSpec : FunSpec({

    val ui = UiSource.all()

    test("L-17 AC6: the UI fetches the explain endpoint for the clicked holding, with encoded path parts") {
        ui shouldContain "/explain"
        Regex("""/holdings/\$\{encodeURIComponent\([^)]*\)\}/\$\{encodeURIComponent\([^)]*\)\}/explain""")
            .containsMatchIn(ui) shouldBe true
    }

    test("L-17 AC6: a holding row has a click handler (more than the existing Load button's)") {
        Regex("""onClick=""").findAll(ui).count() shouldBeGreaterThan 1
        Regex("""<tr[^>]*onClick=""").containsMatchIn(ui) shouldBe true
    }

    test("L-17 AC6: reason chips list their entry ids and selecting one highlights the steps with that entry id") {
        ui shouldContain "entryIds.map("
        ui shouldContain "data-entry-id"
        ui shouldContain "data-highlighted"
        Regex("""<button[^>]*data-entry-id""").containsMatchIn(ui) shouldBe true
        Regex("""<(tr|li)[^>]*data-highlighted""").containsMatchIn(ui) shouldBe true
    }

    test("L-17 AC6: all money in the panel goes through formatMoney; no float formatting anywhere in the UI") {
        listOf("costDeltaCents", "runningAcbCents", "unexplainedCents").forEach { f ->
            Regex("""formatMoney\(\s*[\w.?]*$f""").containsMatchIn(ui) shouldBe true
        }
        listOf("toFixed(", "parseFloat(", "toLocaleString(", "Intl.NumberFormat", "Math.round(").forEach {
            ui shouldNotContain it
        }
        // The only division by 100 is formatMoney's own integer math.
        Regex("""/\s*100\b""").findAll(ui).count() shouldBe 1
    }

    test("L-17 AC7: the UI does not filter UNEXPLAINED (or any reason) out of what it shows") {
        Regex("""ruleId\s*!==?\s*["']UNEXPLAINED""").containsMatchIn(ui) shouldBe false
        Regex("""filter\([^)]*UNEXPLAINED""").containsMatchIn(ui) shouldBe false
        // L-16's guard forbids any filter( in AcbVsBook.tsx; keep that true.
        SourceText.read("ui/src/AcbVsBook.tsx") shouldNotContain "filter("
    }

    test("L-17 AC8: the UI footer says fully explained by N adjustments, shows unexplained, and never Unreconciled") {
        ui shouldContain "Difference fully explained by"
        ui shouldContain "adjustment"
        ui shouldContain "unexplained"
        listOf("Matches", "Explained", "Discrepancy", "Data gap").forEach { ui shouldContain it }
        ui shouldNotContain "Unreconciled"
        // The "fully explained" sentence is conditional on the server's flag, not printed unconditionally.
        val flat = ui.replace("\n", " ")
        val sentence = "Difference fully explained by"
        Regex("""fullyExplained\s*(\?|&&)[^<]*$sentence""").containsMatchIn(flat) shouldBe true
    }
})
