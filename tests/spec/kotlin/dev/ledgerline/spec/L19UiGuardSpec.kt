package dev.ledgerline.spec

import dev.ledgerline.testsupport.UiSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * L-19 AC6 UI guards: source-text checks over `ui/src`, in the style of `L20UiGuardSpec` (`ui/` has no
 * component-test tool; CI still runs `npm run typecheck && npm run build`). Not covered (a source check cannot show it):
 * that the form really appears for a Broker B file at run time and that no preview request is sent before both answers
 * are given. The wording of the guard expression is checked instead.
 *
 * UI contract for Dev, over the HTTP contract on `L19` in `L19.kt`:
 * - the preview response's `format` is shown ("CANONICAL" or "BROKER_B"), read as `.format`;
 * - when the format is BROKER_B: a Taxpayer text input and an "Account type" `<select>` with NON_REGISTERED, TFSA, RRSP;
 * - the preview URL carries `taxpayer=` and `accountType=` built with `encodeURIComponent`;
 * - the expression that disables or guards the preview names both answers.
 */
class L19UiGuardSpec : FunSpec({
    val ui = UiSource.all()
    val flat = ui.replace("\n", " ")

    test("L-19 AC6: the Upload tab renders the detected format, covering both format names") {
        ui shouldContain "BROKER_B"
        ui shouldContain "CANONICAL"
        Regex("""\bformat\b""").containsMatchIn(flat) shouldBe true
        Regex("""\.format\b""").containsMatchIn(flat) shouldBe true
    }

    test(
        "L-19 AC6: for Broker B the tab has a Taxpayer field and an Account type choice with the three account types",
    ) {
        ui shouldContain "Taxpayer"
        ui shouldContain "Account type"
        listOf("NON_REGISTERED", "TFSA", "RRSP").forEach { ui shouldContain it }
        Regex("""<(input|select)[^>]{0,400}""").findAll(flat).any { it.value.contains("axpayer") } shouldBe true
        Regex("""<select""").containsMatchIn(flat) shouldBe true
    }

    test("L-19 AC6: the preview request carries taxpayer and accountType in the query, URL-encoded") {
        val at = flat.indexOf("/api/v1/imports/preview")
        (at >= 0) shouldBe true
        val around = flat.substring(maxOf(0, at - 400), minOf(flat.length, at + 600))
        around shouldContain "taxpayer="
        around shouldContain "accountType="
        around shouldContain "encodeURIComponent"
    }

    test("L-19 AC6: the Broker B form is conditional on the detected format, and the preview waits for the answers") {
        // The Broker B string must sit in a comparison or a conditional render, not only in a label.
        Regex("""(===|!==|==|&&|\?)\s*["']BROKER_B["']|["']BROKER_B["']\s*(===|!==|&&|\?)""")
            .containsMatchIn(flat) shouldBe true
        // The disabled or guard expression of the preview trigger names both answers.
        val guard = Regex("""(disabled=\{[^}]{0,300}\}|if\s*\([^)]{0,300}\))""").findAll(flat).map { it.value }
        guard.any {
            it.contains("taxpayer", ignoreCase = true) && it.contains("accountType", ignoreCase = true)
        } shouldBe true
    }

    test("L-19 AC6: the L-20 upload guards still hold: Upload tab and the count labels are present") {
        listOf("Upload", "Rows read", "Duplicates skipped", "Conflicts", "Rejected").forEach { ui shouldContain it }
    }
})
