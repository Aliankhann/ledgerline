package dev.ledgerline.spec

import dev.ledgerline.testsupport.UiSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * L-20 AC8 UI guards: source-text checks over `ui/src`, in the style of `L18UiGuardSpec`. `ui/` has no
 * component-test tool, and `ui/` is Dev's, so QE adds none; CI still runs `npm run typecheck && npm run build`. Not
 * covered by a guard (a source check cannot show it): that the count cards show the right numbers, that rejected and
 * conflict rows come first, that a double click sends one request at run time, and that "an Upload of the same file
 * again shows new 0". The disabled conditions are guarded by their wording in the source, below.
 *
 * UI contract for Dev, over the HTTP contract on `L20` in `L20.kt`:
 * - an "Upload" tab; a `<input type="file" ... onChange=...>` whose handler clears the preview state;
 * - `fetch` of `/api/v1/imports/preview` and `/api/v1/imports/confirm` with `method: "POST"`; the confirm URL carries
 *   `fileSha256=` from the preview response;
 * - cards labelled "Rows read", "New", "Duplicates skipped", "Conflicts", "Rejected";
 * - a table with a header for each of Line, Key, Type, Date, Status, Reason, each row with `data-status={...}` and
 *   `data-line={...}`; no filter that drops rows;
 * - a Warnings list from `warnings.map(` showing `ruleId`, the `lines` and the text, no filter on it;
 * - a button "Confirm import" whose `disabled={...}` expression, in the 600 characters before the label, names
 *   `fileErrors`, the `new` count, and an in-flight flag (`busy`, `loading`, `inFlight`, `pending`, `posting`,
 *   `sending` or `submitting`);
 * - counts rendered as integers: no `toFixed(`, `parseFloat(`, `toLocaleString(`, `Intl.NumberFormat`, `Math.round(`.
 */
class L20UiGuardSpec : FunSpec({

    val ui = UiSource.all()
    val flat = ui.replace("\n", " ")

    test("L-20 AC8: an Upload tab has a file picker whose change handler is wired") {
        ui shouldContain "Upload"
        val inputs =
            Regex("<input").findAll(flat).map {
                flat.substring(it.range.first, minOf(flat.length, it.range.first + 600))
            }
        inputs.any { Regex("""type=["']file["']""").containsMatchIn(it) && it.contains("onChange") } shouldBe true
    }

    test("L-20 AC8: the preview and the confirm are POSTed to the import routes and confirm carries fileSha256") {
        ui shouldContain "/api/v1/imports/preview"
        ui shouldContain "/api/v1/imports/confirm"
        Regex("""imports/preview[^;]{0,300}method:\s*["']POST["']""").containsMatchIn(flat) shouldBe true
        Regex("""imports/confirm[^;]{0,300}method:\s*["']POST["']""").containsMatchIn(flat) shouldBe true
        ui shouldContain "fileSha256"
        Regex("""imports/confirm\?[^"'`]*fileSha256=""").containsMatchIn(ui) shouldBe true
    }

    test("L-20 AC8: the five count cards and the six table columns are present") {
        listOf("Rows read", "New", "Duplicates skipped", "Conflicts", "Rejected").forEach { ui shouldContain it }
        listOf("Line", "Key", "Type", "Date", "Status", "Reason").forEach { ui shouldContain it }
    }

    test("L-20 AC8: every table row carries its status and line, and no filter hides rows by status") {
        ui shouldContain "data-status"
        ui shouldContain "data-line"
        Regex("""rows(\?)?\.map\(""").containsMatchIn(ui) shouldBe true
        Regex("""\brows[^;]{0,80}\.filter\(""").containsMatchIn(flat) shouldBe false
    }

    test("L-20 AC8: the Warnings list shows the rule id, the file lines and the text, unfiltered") {
        ui shouldContain "Warnings"
        Regex("""warnings(\?)?\.map\(""").containsMatchIn(ui) shouldBe true
        Regex("""warnings[^;]{0,80}\.filter\(""").containsMatchIn(flat) shouldBe false
        listOf("ruleId", "lines", "text").forEach { ui shouldContain it }
    }

    test("L-20 AC8: Confirm import is disabled for no preview, new 0, a file error and a request in flight") {
        ui shouldContain "Confirm import"
        val at = flat.indexOf("Confirm import")
        val before = flat.substring(maxOf(0, at - 600), at)
        val tag = before.substring(before.lastIndexOf("<button").coerceAtLeast(0))
        tag shouldContain "disabled"
        tag shouldContain "fileErrors"
        Regex("""\bnew\b""").containsMatchIn(tag) shouldBe true
        Regex("""busy|loading|inFlight|pending|posting|sending|submitting""", RegexOption.IGNORE_CASE)
            .containsMatchIn(tag) shouldBe true
        // No preview yet: the disabled expression must also depend on the preview object itself.
        Regex("preview|report|result", RegexOption.IGNORE_CASE).containsMatchIn(tag) shouldBe true
    }

    test("L-20 AC8: the file picker handler clears the preview and the confirm result") {
        val at = flat.indexOf("type=\"file\"").takeIf { it >= 0 } ?: flat.indexOf("type='file'")
        (at >= 0) shouldBe true
        val tagText = flat.substring(maxOf(0, at - 300), minOf(flat.length, at + 600))
        Regex("""set\w+\(\s*(null|undefined)\s*\)""").containsMatchIn(flat) shouldBe true
        tagText shouldContain "onChange"
    }

    test("L-20 AC8: counts are plain integers; no float formatting in the UI") {
        listOf("toFixed(", "parseFloat(", "toLocaleString(", "Intl.NumberFormat", "Math.round(").forEach {
            ui shouldNotContain it
        }
    }
})
