package dev.ledgerline.spec

import dev.ledgerline.testsupport.SourceText
import dev.ledgerline.testsupport.UiSource
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * L-23 UI guards (docs/stories/L-23-dashboard-tabs.md, PROVISIONAL: the story is a Draft). `ui/` has no browser or
 * component-test tool and `ui/` is Dev's, so QE adds none; these are source-text checks over `ui/src` in the style of
 * `L17UiGuardSpec`, `L18UiGuardSpec` and `L20UiGuardSpec`. CI also runs `npm run typecheck && npm run build`. They
 * prove structure only, never what a person sees: that a tab really switches, that back/forward works, that the
 * summary bar shows "2,440.00" for 244000, and the default-tab rule (Your year once the taxpayer has a tax year,
 * else Upload) are NOT proven here; the owner checks them by eye at sign-off. Wording of the "What to do" lines is an
 * owner decision, so only that a static map keyed by rule id exists is guarded, not the text.
 *
 * UI contract for Dev (file and component names the guards read; each guard fails with the file named if it is
 * missing):
 * - `ui/src/App.tsx`: the one `useState("TP-0001")` (the taxpayer), the tab state, the hash handling
 *   (`location.hash` read, written on click, a `hashchange` listener), the single checks list and the lifted preview
 *   report state. The tab strip may live in an optional `ui/src/Tabs.tsx`; the guards read `App.tsx` plus `Tabs.tsx`.
 * - Tab strip: a `role="tablist"` element holding `<button role="tab" aria-selected={<expression>} onClick=...>` in
 *   the order "Upload", "Your year", "Holdings", "Data checks" (quoted string or JSX text), and a `role="tabpanel"`.
 *   The hash slugs are `upload`, `your-year`, `holdings`, `data-checks` (quoted, with or without a leading `#`).
 *   `<AcbVsBook`, `<YourYear` and `<Upload`, if rendered in `App.tsx`, sit behind a condition or `hidden`.
 * - `ui/src/SummaryBar.tsx` exports `SummaryBar` and holds the only taxpayer `<input>` (non-file), a year `<select>`
 *   with `onChange`, labels for Taxpayer, Tax year, Net, Holdings, Warnings and Health (or Service), the text
 *   "unreachable", and `formatMoney(...netCents...)`. "CAD only" appears next to a `FX-NOT-CONVERTED` test.
 * - `AcbVsBook` and `YourYear` take `taxpayer` as a prop (`function X({ taxpayer ... })`), have no `<input` and no
 *   `useState("TP-0001")`, and reload on a change of it (`taxpayer` in a hook dependency array, or `key={taxpayer}`
 *   where they are rendered).
 * - `<SummaryBar ... />` and `<DataChecks ... />` are given the same identifier (for example `checks={checks}` and
 *   `warningCount={checks.length}`): one list, so the count and the tab cannot disagree.
 * - Holdings footer: the text "holdings fully explained" behind a condition built with `.every(` over `fullyExplained`,
 *   and a "not explained" naming line; no `.filter(` and no `.reduce(` in `AcbVsBook.tsx`; `ExplainPanel` reused.
 * - `ui/src/DataChecks.tsx` exports `DataChecks`: a static map keyed by `DATA-GAP-TRANSFER-NO-COST`,
 *   `DATA-GAP-OVERSELL`, `FX-NOT-CONVERTED`, `AMOUNT_OUT_OF_RANGE`, `REJECTED` to string values, the generic fallback
 *   text of AC6 behind `??` or `||`, a "What to do" label, the empty state "No data checks to show", `entryIds.map(`
 *   rendering `<button ... data-entry-id=...>`, the file `line` of rejected rows, and no `.filter(` over reasons,
 *   warnings or rows (L-20's guard already bans it near `rows` and `warnings`; use `flatMap` or a loop). `<DataChecks`
 *   is given an `onOpen...={...}` callback that opens the Holdings timeline.
 * - `Upload` is given the report and its setter by `App` (`report`/`preview` state lives in `App`, not in `Upload`).
 * - The tax-year 422 body is read (`422` or `.error` compared with `AMOUNT_OUT_OF_RANGE`).
 * - No `any`, `@ts-ignore`, `@ts-nocheck`; `ui/package.json` runtime dependencies stay `react` and `react-dom`.
 *
 * Existing guards this restructure can trip: see the report that came with this spec (L17 `<tr ... onClick=`,
 * `holdings...` and `Difference fully explained by`, L18 `warnings...filter(`, the single `/ 100`, L20 `rows`/
 * `warnings` `.filter(`, and "Confirm import" disabled expression) and keep them green.
 */
class L23DashboardGuardSpec : FunSpec({

    val ui = UiSource.all()
    val flatUi = flat(ui)

    fun exists(name: String): Boolean = runCatching { SourceText.read("ui/src/$name") }.isSuccess

    fun src(name: String): String = runCatching { flat(SourceText.read("ui/src/$name")) }.getOrDefault("")

    val appSrc = src("App.tsx") + " " + src("Tabs.tsx")

    fun mustExist(vararg names: String) {
        names.forEach { n -> withClue("ui/src/$n must exist (L-23 contract)") { exists(n) shouldBe true } }
    }

    fun tagOf(
        source: String,
        component: String,
    ): String = Regex("""<$component\b[\s\S]*?/>""").find(source)?.value.orEmpty()

    fun passedIdentifiers(tag: String): Set<String> =
        Regex("""=\{\s*([A-Za-z_]\w*)(?:\.length)?\s*\}""").findAll(tag).map { it.groupValues[1] }
            .filterNot {
                it.startsWith("taxpayer", ignoreCase = true) ||
                    Regex("""^(set|on)[A-Z]""").containsMatchIn(it)
            }
            .toSet()

    fun slug(s: String) = Regex("""["'`]#?$s["'`]|\b$s\s*:""")

    test("L-23 AC1: four role=tab buttons in a role=tablist, in order, aria-selected, one tabpanel at a time") {
        mustExist("App.tsx")
        assertSoftly {
            appSrc shouldMatchRegex """role=["']tablist["']"""
            appSrc shouldMatchRegex """<button\b[^>]*role=["']tab["']"""
            appSrc shouldMatchRegex """role=["']tabpanel["']"""
            // aria-selected is an expression, not a constant.
            appSrc shouldMatchRegex """aria-selected=\{\s*(?!true\b|false\b)[^}]+\}"""
            // Each tab button has a click handler within reach of its role attribute.
            Regex("""role=["']tab["']""").findAll(appSrc).toList().isNotEmpty() shouldBe true
            Regex("""role=["']tab["']""").findAll(appSrc).all {
                appSrc.substring(maxOf(0, it.range.first - 400), minOf(appSrc.length, it.range.last + 400))
                    .contains("onClick")
            } shouldBe true
            // Label order by index of the quoted or JSX-text label (an import or a tag does not count).
            val at =
                listOf("Upload", "Your year", "Holdings", "Data checks").map { l ->
                    Regex("""["'`>]\s*$l\s*["'`<]""").find(appSrc)?.range?.first ?: -1
                }
            withClue("labels Upload, Your year, Holdings, Data checks must all appear, in this order: $at") {
                at.all { it >= 0 } shouldBe true
                at shouldBe at.sorted()
            }
            // The three old views are no longer all rendered unconditionally in App.
            listOf("AcbVsBook", "YourYear", "Upload").forEach { c ->
                Regex("""<$c\b""").findAll(appSrc).forEach {
                    val before = appSrc.substring(maxOf(0, it.range.first - 100), it.range.first)
                    withClue("<$c in App must sit behind a condition or hidden") {
                        Regex("""&&|\?\s|===?|hidden|display""").containsMatchIn(before) shouldBe true
                    }
                }
            }
        }
    }

    test("L-23 AC2: the four hash slugs, location.hash read and written, and a hashchange listener") {
        mustExist("App.tsx")
        assertSoftly {
            listOf("upload", "your-year", "holdings", "data-checks").forEach { s ->
                withClue("hash slug $s in App.tsx or Tabs.tsx") { slug(s).containsMatchIn(appSrc) shouldBe true }
            }
            withClue("location.hash is read") {
                Regex("""location\.hash(?!\s*=[^=])""").containsMatchIn(appSrc) shouldBe true
            }
            withClue("clicking a tab writes the hash") {
                Regex("""location\.hash\s*=[^=]|history\.(push|replace)State\(|location\.(assign|replace)\(""")
                    .containsMatchIn(appSrc) shouldBe true
            }
            withClue("a hashchange listener keeps back/forward working") {
                Regex("""addEventListener\(\s*["']hashchange["']""").containsMatchIn(appSrc) shouldBe true
            }
            withClue("no router library: hash handling uses window.location") {
                ui shouldNotContain "react-router"
            }
        }
    }

    test("L-23 AC3: one shared taxpayer input and state; the two views take it as a prop") {
        mustExist("App.tsx", "AcbVsBook.tsx", "YourYear.tsx")
        val inputs = Regex("""<input\b[\s\S]*?/>""").findAll(flatUi).map { it.value }.toList()
        val nonFile = inputs.filterNot { Regex("""type=["']file["']""").containsMatchIn(it) }
        assertSoftly {
            withClue("exactly one non-file <input> in the whole UI (the taxpayer), found ${nonFile.size}") {
                nonFile.size shouldBe 1
                nonFile.single().contains("taxpayer", ignoreCase = true) shouldBe true
            }
            withClue("that input lives in SummaryBar.tsx or App.tsx") {
                val where = listOf("SummaryBar.tsx", "App.tsx").count { Regex("""<input\b""").containsMatchIn(src(it)) }
                (where >= 1) shouldBe true
                Regex("""<input\b""").containsMatchIn(src("AcbVsBook.tsx") + src("YourYear.tsx")) shouldBe false
            }
            withClue("exactly one useState(\"TP-0001\") in the UI, and not in AcbVsBook or YourYear") {
                val re = Regex("""useState(<[^>]*>)?\(\s*["']TP-0001["']""")
                re.findAll(flatUi).count() shouldBe 1
                re.containsMatchIn(src("AcbVsBook.tsx") + src("YourYear.tsx")) shouldBe false
                re.containsMatchIn(src("App.tsx") + src("SummaryBar.tsx") + src("Tabs.tsx")) shouldBe true
            }
            listOf("AcbVsBook", "YourYear").forEach { c ->
                withClue("$c takes taxpayer as a prop") {
                    Regex("""function $c\(\s*\{[^}]*\btaxpayer""").containsMatchIn(src("$c.tsx")) shouldBe true
                    Regex("""<$c\b[\s\S]{0,300}?\btaxpayer\w*=\{""").containsMatchIn(flatUi) shouldBe true
                }
                withClue("$c reloads when the taxpayer changes (dependency array or key)") {
                    val deps = Regex("""\[[^\]]*\btaxpayer\w*[^\]]*\]\s*\)""").containsMatchIn(src("$c.tsx"))
                    val key = Regex("""<$c\b[\s\S]{0,300}?key=\{[^}]*taxpayer""").containsMatchIn(flatUi)
                    (deps || key) shouldBe true
                }
            }
        }
    }

    test("L-23 AC4: summary bar fields from existing endpoints, money via formatMoney only, one warnings list") {
        mustExist("SummaryBar.tsx", "App.tsx", "DataChecks.tsx")
        val bar = src("SummaryBar.tsx")
        val barLower = bar.lowercase()
        assertSoftly {
            listOf("taxpayer", "tax year", "net", "holdings", "warnings", "unreachable").forEach {
                withClue("SummaryBar.tsx shows '$it'") { barLower.contains(it) shouldBe true }
            }
            withClue("a service health field") { Regex("health|service").containsMatchIn(barLower) shouldBe true }
            withClue("tax year is a select with a change handler") {
                Regex("""<select\b[^>]*onChange""").containsMatchIn(bar) shouldBe true
            }
            withClue("net goes through formatMoney") {
                Regex("""formatMoney\(\s*[\w.?!]*netCents""").containsMatchIn(bar) shouldBe true
            }
            withClue("'CAD only' next to an FX-NOT-CONVERTED test") {
                flatUi.contains("CAD only") shouldBe true
                flatUi.contains("FX-NOT-CONVERTED") shouldBe true
            }
            withClue("no float or locale formatting, no Number( on cents, and only formatMoney's own / 100") {
                listOf("toFixed(", "parseFloat(", "toLocaleString(", "Intl.NumberFormat", "Math.round(").forEach {
                    ui shouldNotContain it
                }
                Regex("""Number\([^)]*[cC]ents""").containsMatchIn(flatUi) shouldBe false
                Regex("""/\s*100\b""").findAll(ui).count() shouldBe 1
            }
            withClue("the bar and the Data checks tab are given the same list identifier (one source)") {
                val shared =
                    passedIdentifiers(tagOf(flatUi, "DataChecks")) intersect
                        passedIdentifiers(tagOf(flatUi, "SummaryBar"))
                withClue(
                    "DataChecks tag: ${tagOf(flatUi, "DataChecks")} / SummaryBar tag: ${tagOf(flatUi, "SummaryBar")}",
                ) {
                    shared.isNotEmpty() shouldBe true
                }
            }
        }
    }

    test("L-23 AC5: Holdings keeps the explain wiring, reads fullyExplained, and does not recompute or filter") {
        mustExist("AcbVsBook.tsx", "ExplainPanel.tsx")
        val book = src("AcbVsBook.tsx")
        assertSoftly {
            withClue("ExplainPanel is reused, defined once") {
                Regex("""<ExplainPanel\b""").containsMatchIn(flatUi) shouldBe true
                Regex("""function ExplainPanel\b""").findAll(flatUi).count() shouldBe 1
            }
            withClue("the five status labels") {
                listOf("Matches", "Explained", "Discrepancy", "Data gap", "No book value").forEach {
                    flatUi.contains(it) shouldBe true
                }
            }
            withClue("explain fields are read from the payload") {
                listOf("fullyExplained", "unexplainedCents", "adjustmentCount", "status").forEach {
                    flatUi.contains(it) shouldBe true
                }
            }
            withClue("a dash when there is no book value") {
                (ui.contains("—") || ui.contains("&mdash;")) shouldBe true
            }
            withClue("the footer sentence exists, is conditional, and is built with every() over fullyExplained") {
                val at = flatUi.indexOf("holdings fully explained")
                (at >= 0) shouldBe true
                val before = flatUi.substring(maxOf(0, at - 500), maxOf(0, at))
                Regex("""&&|\?\s|every\(""").containsMatchIn(before) shouldBe true
                Regex("""every\([^;]{0,200}fullyExplained""").containsMatchIn(flatUi) shouldBe true
            }
            withClue("the footer names the holdings that are not explained") {
                Regex("""not (fully )?explained""", RegexOption.IGNORE_CASE).containsMatchIn(flatUi) shouldBe true
            }
            withClue("no re-computation of reason sums and no filter in AcbVsBook.tsx") {
                book shouldNotContain "filter("
                book shouldNotContain ".reduce("
                Regex("""amountMinor\s*\+|\+\s*\w*\.?amountMinor""").containsMatchIn(book) shouldBe false
            }
        }
    }

    test("L-23 AC6: Data checks lists every source with trades and a What to do line, static map plus fallback") {
        mustExist("DataChecks.tsx", "Upload.tsx", "App.tsx")
        val dc = src("DataChecks.tsx")
        assertSoftly {
            listOf(
                "DATA-GAP-TRANSFER-NO-COST",
                "DATA-GAP-OVERSELL",
                "FX-NOT-CONVERTED",
                "AMOUNT_OUT_OF_RANGE",
                "REJECTED",
            ).forEach { id ->
                withClue("a static map entry keyed by $id with a string value") {
                    Regex("""["']?$id["']?\s*:\s*["'`]""").containsMatchIn(dc) shouldBe true
                }
            }
            withClue("generic fallback text, applied with ?? or ||") {
                dc.contains("Review the trades listed and correct or add the missing activity") shouldBe true
                Regex("""\?\?|\|\|""").containsMatchIn(dc) shouldBe true
            }
            withClue("What to do label, empty state, and the items are mapped") {
                dc.contains("What to do", ignoreCase = true) shouldBe true
                dc.contains("No data checks to show") shouldBe true
                Regex("""\.map\(""").containsMatchIn(dc) shouldBe true
                dc.contains("ruleId") shouldBe true
            }
            withClue("entry ids are buttons carrying data-entry-id; rejected rows show their file line") {
                dc.contains("entryIds.map(") shouldBe true
                Regex("""<button\b[^>]*data-entry-id""").containsMatchIn(dc) shouldBe true
                Regex("""\bline\b""").containsMatchIn(dc) shouldBe true
            }
            withClue("nothing dropped or merged away") {
                val hit =
                    Regex("""(reasons|warnings|rows|items|checks)\w*[^;]{0,80}\.filter\(""").containsMatchIn(
                        dc,
                    )
                hit shouldBe false
                Regex("""dedup|unique|uniq\b""", RegexOption.IGNORE_CASE).containsMatchIn(dc) shouldBe false
            }
            withClue("DataChecks gets a callback that opens the Holdings timeline and the report from App state") {
                val tag = tagOf(flatUi, "DataChecks")
                Regex("""\bon[A-Z]\w*=\{""").containsMatchIn(tag) shouldBe true
                val hit =
                    Regex("""report|preview""", RegexOption.IGNORE_CASE).containsMatchIn(
                        flatUi.let { tag + appSrc },
                    )
                hit shouldBe true
            }
            withClue("the last preview report is held in App and Upload receives its setter as a prop") {
                Regex("""\[\s*\w*(report|preview)\w*\s*,\s*set\w+\s*\]\s*=\s*useState""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(appSrc) shouldBe true
                val hit =
                    Regex("""\[\s*report\s*,\s*setReport\s*\]\s*=\s*useState""").containsMatchIn(
                        src("Upload.tsx"),
                    )
                hit shouldBe false
                Regex("""<Upload\b[\s\S]{0,300}?=\{\s*set\w*\s*\}""").containsMatchIn(appSrc) shouldBe true
            }
            withClue("the tax-year 422 body is read, not just thrown away") {
                Regex("""\b422\b|\.error\s*===?\s*["']AMOUNT_OUT_OF_RANGE""").containsMatchIn(flatUi) shouldBe true
            }
        }
    }

    test("L-23 AC7: strict typing stays, no any or ts-ignore, no new runtime dependency, no router") {
        mustExist("App.tsx")
        val pkg = SourceText.read("ui/package.json")
        val deps =
            Regex("""["']dependencies["']\s*:\s*\{([^}]*)\}""").find(pkg)?.groupValues?.get(1).orEmpty()
        val names = Regex("""["']([^"']+)["']\s*:""").findAll(deps).map { it.groupValues[1] }.toSet()
        assertSoftly {
            withClue("runtime dependencies are exactly react and react-dom, found $names") {
                names shouldBe setOf("react", "react-dom")
            }
            withClue("no any, @ts-ignore, @ts-nocheck in ui/src") {
                Regex(""":\s*any\b|<any>|\bas any\b|\bany\[\]""").containsMatchIn(ui) shouldBe false
                ui shouldNotContain "@ts-ignore"
                ui shouldNotContain "@ts-nocheck"
            }
            withClue("tsconfig stays strict") {
                Regex(""""strict"\s*:\s*true""").containsMatchIn(SourceText.read("ui/tsconfig.json")) shouldBe true
            }
        }
    }
})

private fun flat(s: String): String = s.replace(Regex("""\s+"""), " ")

private infix fun String.shouldMatchRegex(pattern: String) {
    withClue("pattern not found: $pattern") { Regex(pattern).containsMatchIn(this) shouldBe true }
}
