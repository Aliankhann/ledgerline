package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.GainKind
import dev.ledgerline.calc.TaxYearReport
import dev.ledgerline.calc.TaxYearRow
import dev.ledgerline.testsupport.CraFixture
import dev.ledgerline.testsupport.L17.ids
import dev.ledgerline.testsupport.L17.long
import dev.ledgerline.testsupport.L17.parse
import dev.ledgerline.testsupport.L17.strings
import dev.ledgerline.testsupport.L17.text
import dev.ledgerline.testsupport.L18
import dev.ledgerline.testsupport.L18.allNumbersAreIntegers
import dev.ledgerline.testsupport.L18.centsOf
import dev.ledgerline.testsupport.L18.parseCsv
import dev.ledgerline.testsupport.L18.rowList
import dev.ledgerline.testsupport.L18.totals
import dev.ledgerline.testsupport.L18.warningList
import dev.ledgerline.testsupport.SourceText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * L-18 your tax year (docs/stories/L-18-your-tax-year.md). PROVISIONAL: Draft story. The API contract Dev builds
 * against, and what is deliberately not asserted (the owner's decisions), are on `L18` in
 * `tests/support/.../L18.kt`. UI guards (AC10) are in `L18UiGuardSpec`. Tax values come only from
 * `cra-xyz-gain.properties` and the share ratio of `cra-ti-2004-0073011e5-partial-sl.properties`; everything else is
 * checked against the engine's own replay.
 */
class L18YourYearSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val tp = L18.tp
    val share = L18.SHARE
    val xyzF = CraFixture.load("cra-xyz-gain")
    val slF = CraFixture.load("cra-ti-2004-0073011e5-partial-sl")
    val xyzRows = xyzF.rows(2)
    val slRows = slF.rows(4)
    val y2025 = LocalDate.of(2025, 1, 1)

    fun json(path: String) = "/v1/tax-year/$tp/$path"

    // AC6 book: one security, 3 shares held, three 1-share sells at a gain; sequence numbers differ from entry order.
    val settleBook =
        listOf(
            L18.buy("s0", "SYN-SETTLE", LocalDate.of(2024, 1, 2), 3 * share, 3_000, 1, 1),
            L18.sell(
                "sa",
                "SYN-SETTLE",
                LocalDate.of(2025, 12, 30),
                share,
                5_000,
                12,
                9,
                settlement = LocalDate.of(2026, 1, 2),
            ),
            L18.sell("sb", "SYN-SETTLE", LocalDate.of(2026, 1, 2), share, 5_000, 13, 3),
            L18.sell("sc", "SYN-SETTLE", LocalDate.of(2025, 12, 31), share, 5_000, 14, 10),
        )

    // ---------------------------------------------------------------- AC1

    test(
        "L-18 AC1: the CRA XYZ fixture gives exactly one 2025 row with the fixture's figures and zero superficial loss",
    ) {
        val res = L18.fetch(xyzRows, json("2025"))
        res.status shouldBe HttpStatusCode.OK
        val body = parse(res.body)
        val row = body.rowList().single()
        row.text("taxDate") shouldBe xyzF.date("t2.date").toString()
        row.text("taxDate") shouldBe "2025-03-13"
        row.text("securityId") shouldBe xyzF.security
        row.long("sharesUnits") shouldBe xyzF.long("t2.shares.units")
        row.long("sharesUnits") shouldBe 400_000_000L
        row.long("proceedsCents") shouldBe xyzF.long("t2.gross.cents")
        row.long("outlaysCents") shouldBe xyzF.long("t2.commission.cents")
        row.long("acbSoldCents") shouldBe xyzF.long("t1.gross.cents")
        row.long("gainCents") shouldBe xyzF.long("t2.expected.gain.cents")
        row.long("gainCents") shouldBe 244_000L
        row.long("superficialLossDeniedCents") shouldBe 0L
        row.getValue("notes").jsonArray.shouldBeEmpty()
        row.ids("entryIds") shouldBe listOf(xyzRows[1].entryId)
        val t = body.totals()
        listOf(t.long("gainsCents"), t.long("netCents")).forEach { it shouldBe 244_000L }
        listOf(t.long("lossesCents"), t.long("superficialLossesDeniedCents")).forEach { it shouldBe 0L }
        // The on-screen identity: proceeds - outlays - ACB (+ denied) = gain.
        row.long("proceedsCents") - row.long("outlaysCents") - row.long("acbSoldCents") shouldBe row.long("gainCents")
    }

    test("L-18 AC1: the same book for 2024 returns 0 rows and all totals 0") {
        val body = L18.year(xyzRows, 2024)
        body.rowList().shouldBeEmpty()
        body.totals().keys.sorted() shouldBe
            listOf("gainsCents", "lossesCents", "netCents", "superficialLossesDeniedCents", "taxableCapitalGainCents")
        body.totals().keys.forEach { body.totals().long(it) shouldBe 0L }
    }

    // ---------------------------------------------------------------- AC2 / AC3

    test("L-18 AC2: the CRA XYZ example has taxable capital gain 122000 cents (1/2 of the 244000 gain)") {
        L18.year(xyzRows, 2025).totals().long("taxableCapitalGainCents") shouldBe 122_000L
        TaxYearReport.taxableCents(xyzF.long("t2.expected.gain.cents")) shouldBe 122_000L
        TaxYearReport.build(xyzRows, tp, 2025).totals.taxableCapitalGainCents shouldBe 122_000L
    }

    test(
        "L-18 AC3: an even net halves exactly and a net of 0 or less gives taxable 0 (odd cents untested)",
    ) {
        listOf(2L, 1_000L, 244_000L, 2_000_000L, 9_007_199_254_740_990L).forEach {
            TaxYearReport.taxableCents(it) shouldBe it / 2
        }
        listOf(0L, -1L, -2L, -1_001L, -244_000L).forEach { TaxYearReport.taxableCents(it) shouldBe 0L }
    }

    test("L-18 AC3: a year with a net loss shows a negative net, losses equal to net and taxable 0") {
        val book =
            listOf(
                L18.buy("l0", "SYN-LOSS", LocalDate.of(2024, 1, 2), 10 * share, 100_000, 1),
                L18.sell("l1", "SYN-LOSS", LocalDate.of(2025, 6, 2), 10 * share, 50_000, 2),
            )
        val t = L18.year(book, 2025).totals()
        t.long("netCents") shouldBe -50_000L
        t.long("lossesCents") shouldBe t.long("netCents")
        t.long("gainsCents") shouldBe 0L
        t.long("taxableCapitalGainCents") shouldBe 0L
    }

    // ---------------------------------------------------------------- AC4

    test(
        "L-18 AC4: the CRA partial superficial loss row has the denied ratio, the rule note and the sale and buy ids",
    ) {
        val year = slF.date("t3.date").year
        val sale = engine.replaySuperficial(slRows).sales.single { it.denied > 0 }
        val buyEntries =
            slRows.filter { r -> r.activityId in sale.triggers.map { it.activityId } }.map { it.entryId }.sorted()
        val saleEntry = slRows[2].entryId
        val body = L18.year(slRows, year)
        val row = body.rowList().single()
        val denied = row.long("superficialLossDeniedCents")
        val gain = row.long("gainCents")
        denied shouldBeGreaterThan 0L
        // denied x S = lossBeforeRule x (least of S, P, B), S and P from the fixture's source values (units).
        val lossBefore = denied - gain
        denied * slF.long("expected.sold.units") shouldBe lossBefore * slF.long("expected.acquired.units")
        abs(gain - CaIta2026v1.toReportedCents(sale.reportedGain)) shouldBeLessThanOrEqual 1L
        row.ids("entryIds") shouldBe listOf(saleEntry)
        row.ids("substituteEntryIds").sorted() shouldBe buyEntries
        row.ids("substituteEntryIds") shouldContain slRows[3].entryId
        val notes = row.strings("notes")
        notes.any { "SL-PARTIAL" in it } shouldBe true
        (listOf(saleEntry) + buyEntries).forEach { id -> notes.any { id.toString() in it } shouldBe true }
        body.totals().long("superficialLossesDeniedCents") shouldBe denied
        body.totals().long("lossesCents") shouldBe gain
    }

    // ---------------------------------------------------------------- AC5

    test("L-18 AC5: sums, signs, row set and amounts agree with the engine's own replay (30 seeded books, 3 years)") {
        var rowsSeen = 0
        var rejectionsSeen = 0
        var deniedSeen = 0
        for (seed in 1..30) {
            val book = L18.randomBook(seed)
            val replay = engine.replaySuperficial(book)
            for (y in 2024..2026) {
                val t = TaxYearReport.build(book, tp, y)
                val sales = replay.sales.filter { it.taxDate.year == y }.associateBy { it.activityId }
                t.rows.map { it.activityId }.toSet() shouldBe sales.keys
                t.rows.size shouldBe sales.size
                t.rows.sumOf { it.gainCents } shouldBe t.totals.netCents
                t.rows.filter { it.gainCents > 0 }.sumOf { it.gainCents } shouldBe t.totals.gainsCents
                t.rows.filter { it.gainCents < 0 }.sumOf { it.gainCents } shouldBe t.totals.lossesCents
                (t.totals.gainsCents + t.totals.lossesCents) shouldBe t.totals.netCents
                t.rows.sumOf { it.superficialLossDeniedCents } shouldBe t.totals.superficialLossesDeniedCents
                t.totals.taxableCapitalGainCents shouldBe TaxYearReport.taxableCents(t.totals.netCents)
                t.rows.forEach { r ->
                    r.kind shouldBe GainKind.DISPOSITION
                    val s = sales.getValue(r.activityId)
                    abs(r.gainCents - CaIta2026v1.toReportedCents(s.reportedGain)) shouldBeLessThanOrEqual 1L
                    abs(r.superficialLossDeniedCents - CaIta2026v1.toReportedCents(s.denied)) shouldBeLessThanOrEqual 1L
                    r.superficialLossDeniedCents shouldBeGreaterThan -1L
                    // Rounded parts may differ from the rounded whole by 1 cent (the owner decides how to show it).
                    val identity = r.proceedsCents - r.outlaysCents - r.acbSoldCents + r.superficialLossDeniedCents
                    abs(identity - r.gainCents) shouldBeLessThanOrEqual 1L
                }
                rowsSeen += t.rows.size
                deniedSeen += t.rows.count { it.superficialLossDeniedCents > 0 }
            }
            val rejected = replay.rejections.map { it.idempotencyKey }.toSet()
            val rejectedEntries = book.filter { it.activity.idempotencyKey in rejected }.map { it.entryId }
            rejectionsSeen += rejectedEntries.size
            val everyRow = (2024..2026).flatMap { TaxYearReport.build(book, tp, it).rows }
            everyRow.flatMap { it.entryIds }.forEach { it shouldBe book.first { p -> p.entryId == it }.entryId }
            everyRow.flatMap { it.entryIds }.none { it in rejectedEntries } shouldBe true
        }
        rowsSeen shouldBeGreaterThan 20
        rejectionsSeen shouldBeGreaterThan 0
        deniedSeen shouldBeGreaterThan 0
    }

    test("L-18 AC5: rows are ordered by tax date, then sequence, then entry id, in every seeded year") {
        val order = compareBy<TaxYearRow>({ it.taxDate }, { it.sequence }, { it.entryIds.first() })
        for (seed in 1..30) {
            val book = L18.randomBook(seed)
            (2024..2026).forEach { y ->
                val rows = TaxYearReport.build(book.reversed(), tp, y).rows
                rows shouldBe rows.sortedWith(order)
                rows.forEach { it.taxDate.year shouldBe y }
            }
        }
        // Same tax date, sequence decides before entry id (see AC6 book: entry 13 has sequence 3, entry 12 has 9).
        TaxYearReport.build(settleBook, tp, 2026).rows.map { it.entryIds.single() } shouldBe listOf(13L, 12L)
    }

    test("L-18 AC5: a rejected oversell never yields a row and every row's entry id is a SELL in the book") {
        val book =
            listOf(
                L18.buy("o0", "SYN-OS", LocalDate.of(2024, 1, 2), 2 * share, 2_000, 1),
                L18.sell("o1", "SYN-OS", LocalDate.of(2025, 3, 3), 5 * share, 9_000, 2),
                L18.sell("o2", "SYN-OS", LocalDate.of(2025, 3, 4), 2 * share, 3_000, 3),
            )
        val body = L18.year(book, 2025)
        body.rowList().map { it.ids("entryIds").single() } shouldBe listOf(3L)
        body.warningList().flatMap { it.ids("entryIds") } shouldContain 2L
    }

    test("L-18 AC5: arrival order and repeats do not change the bytes of the response, and amounts are integers") {
        var checked = 0
        for (seed in 1..6) {
            val book = L18.randomBook(seed)
            val reqs = (2024..2026).map { HttpMethod.Get to json(it.toString()) }
            val ref = L18.fetchAll(book, reqs)
            val again = L18.fetchAll(book, reqs)
            val shuffled = L18.fetchAll(book.shuffled(Random(seed)), reqs)
            val reversed = L18.fetchAll(book.reversed(), reqs)
            ref.map { it.body } shouldBe again.map { it.body }
            ref.map { it.body } shouldBe shuffled.map { it.body }
            ref.map { it.body } shouldBe reversed.map { it.body }
            ref.forEach {
                it.status shouldBe HttpStatusCode.OK
                parse(it.body).allNumbersAreIntegers() shouldBe true
                checked += parse(it.body).rowList().size
            }
        }
        checked shouldBeGreaterThan 5
    }

    // ---------------------------------------------------------------- AC6

    test("L-18 AC6: the tax date (settlement, else effective) decides the year and each row shows it") {
        val y26 = L18.year(settleBook, 2026).rowList()
        y26.map { it.text("taxDate") } shouldBe listOf("2026-01-02", "2026-01-02")
        y26.map { it.ids("entryIds").single() } shouldBe listOf(13L, 12L)
        val y25 = L18.year(settleBook, 2025).rowList()
        y25.map { it.text("taxDate") } shouldBe listOf("2025-12-31")
        y25.map { it.ids("entryIds").single() } shouldBe listOf(14L)
    }

    // ---------------------------------------------------------------- AC7

    test("L-18 AC7: an unknown taxpayer is 404 with the key in the body, on the years, JSON and CSV routes") {
        val reqs = listOf("/v1/tax-year/TP-NOPE/2025", "/v1/tax-year/TP-NOPE/2025.csv", "/v1/tax-year/TP-NOPE")
        L18.fetchAll(xyzRows, reqs.map { HttpMethod.Get to it }).forEach {
            it.status shouldBe HttpStatusCode.NotFound
            it.body shouldContain "TP-NOPE"
        }
    }

    test("L-18 AC7: a taxpayer with activities but no sale in the year gets 200, empty rows and zero totals") {
        val res = L18.fetch(xyzRows, json("2030"))
        res.status shouldBe HttpStatusCode.OK
        val body = parse(res.body)
        body.rowList().shouldBeEmpty()
        body.totals().keys.forEach { body.totals().long(it) shouldBe 0L }
    }

    test("L-18 AC7: a non-numeric or out-of-range year is 400 and the body names the year parameter") {
        val paths = listOf("20x5", "0", "10000", "-1", "20x5.csv", "0.csv").map { HttpMethod.Get to json(it) }
        L18.fetchAll(xyzRows, paths).forEach {
            it.status shouldBe HttpStatusCode.BadRequest
            it.body.lowercase() shouldContain "year"
        }
    }

    test("L-18 AC7: securities with only L-16 warnings add no rows and the warnings come back with their entry ids") {
        val book =
            listOf(
                L18.sell("w1", "SYN-GAP-S", LocalDate.of(2025, 2, 1), 5 * share, 5_000, 21),
                L18.transferIn("w2", "SYN-GAP-T", LocalDate.of(2025, 3, 1), 10 * share, 22),
            )
        val body = L18.year(book, 2025)
        body.rowList().shouldBeEmpty()
        body.totals().keys.forEach { body.totals().long(it) shouldBe 0L }
        val byRule = body.warningList().associate { it.text("ruleId") to it.ids("entryIds") }
        byRule["DATA-GAP-OVERSELL"] shouldBe listOf(21L)
        byRule["DATA-GAP-TRANSFER-NO-COST"] shouldBe listOf(22L)
        TaxYearReport.build(book, tp, 2025).warnings.map { it.ruleId }.toSet() shouldBe byRule.keys
    }

    // ---------------------------------------------------------------- AC8

    test(
        "L-18 AC8: the CRA XYZ CSV is text/csv with the header, one row and a totals line equal to the JSON",
    ) {
        val (j, c) = L18.fetchAll(xyzRows, listOf(HttpMethod.Get to json("2025"), HttpMethod.Get to json("2025.csv")))
        c.status shouldBe HttpStatusCode.OK
        c.contentType?.withoutParameters() shouldBe ContentType.Text.CSV
        val recs = parseCsv(c.body)
        recs.size shouldBe 3
        recs[0] shouldBe L18.CSV_HEADER
        val col = L18.CSV_HEADER.withIndex().associate { it.value to it.index }
        recs.forEach { it.size shouldBe L18.CSV_HEADER.size }
        recs[1][col.getValue("taxDate")] shouldBe "2025-03-13"
        recs[1][col.getValue("security")] shouldBe xyzF.security
        centsOf(recs[1][col.getValue("gain")]) shouldBe xyzF.long("t2.expected.gain.cents")
        val t = parse(j.body).totals()
        centsOf(recs[2][col.getValue("gain")]) shouldBe t.long("netCents")
        centsOf(recs[2][col.getValue("superficialLossDenied")]) shouldBe t.long("superficialLossesDeniedCents")
    }

    test(
        "L-18 AC8: a many-row CSV has the JSON rows in the same order and columns, and two requests are byte-identical",
    ) {
        val (seed, y) =
            (1..30).flatMap { s -> (2024..2026).map { s to it } }
                .first { (s, yy) -> TaxYearReport.build(L18.randomBook(s), tp, yy).rows.size >= 3 }
        val book = L18.randomBook(seed)
        val (j, c1, c2) =
            L18.fetchAll(book, listOf(json("$y"), json("$y.csv"), json("$y.csv")).map { HttpMethod.Get to it })
        c1.body shouldBe c2.body
        val body = parse(j.body)
        val rows = body.rowList()
        val recs = parseCsv(c1.body)
        recs.size shouldBe rows.size + 2
        val col = L18.CSV_HEADER.withIndex().associate { it.value to it.index }
        rows.forEachIndexed { i, r ->
            val rec = recs[i + 1]
            rec[col.getValue("taxDate")] shouldBe r.text("taxDate")
            rec[col.getValue("security")] shouldBe r.text("securityId")
            centsOf(rec[col.getValue("proceeds")]) shouldBe r.long("proceedsCents")
            centsOf(rec[col.getValue("acbSold")]) shouldBe r.long("acbSoldCents")
            centsOf(rec[col.getValue("outlays")]) shouldBe r.long("outlaysCents")
            centsOf(rec[col.getValue("gain")]) shouldBe r.long("gainCents")
            centsOf(rec[col.getValue("superficialLossDenied")]) shouldBe r.long("superficialLossDeniedCents")
        }
        centsOf(recs.last()[col.getValue("gain")]) shouldBe body.totals().long("netCents")
        centsOf(recs.last()[col.getValue("superficialLossDenied")]) shouldBe
            body.totals().long("superficialLossesDeniedCents")
    }

    test("L-18 AC8: RFC 4180 quoting, and a security starting with = + - or @ is prefixed with an apostrophe") {
        val names = listOf("=SUM(1,2)", "+X", "-Y", "@Z", "A,B \"q\"")
        val book =
            names.flatMapIndexed { i, n ->
                listOf(
                    L18.buy("i$i-b", n, LocalDate.of(2024, 2, 1), share, 1_000, 100L + 2 * i),
                    L18.sell("i$i-s", n, LocalDate.of(2025, 3, 1), share, 2_000, 101L + 2 * i),
                )
            }
        val res = L18.fetch(book, json("2025.csv"))
        res.status shouldBe HttpStatusCode.OK
        res.body shouldContain "\"A,B \"\"q\"\"\""
        val recs = parseCsv(res.body)
        recs.forEach { it.size shouldBe L18.CSV_HEADER.size }
        val secs = recs.subList(1, recs.size - 1).map { it[1] }
        secs.sorted() shouldBe listOf("'=SUM(1,2)", "'+X", "'-Y", "'@Z", "A,B \"q\"").sorted()
    }

    // ---------------------------------------------------------------- AC9

    test("L-18 AC9: repeated requests leave the activities and the replay output hash unchanged; writes are refused") {
        val book = xyzRows + L18.randomBook(3)
        val snapshot = book.toList()
        val hashBefore = engine.replaySuperficial(book).outputHash
        val gets = (1..3).flatMap { listOf(json("2025"), json("2025.csv"), "/v1/tax-year/$tp") }
        val writes = listOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)
        val out = L18.fetchAll(book, gets.map { HttpMethod.Get to it } + writes.map { it to json("2025") })
        out.take(gets.size).forEach { it.status shouldBe HttpStatusCode.OK }
        out.take(gets.size).chunked(3).forEach { chunk -> chunk.map { it.body } shouldBe out.take(3).map { it.body } }
        out.drop(gets.size).forEach { it.status.value shouldBeGreaterThan 399 }
        book shouldBe snapshot
        engine.replaySuperficial(book).outputHash shouldBe hashBefore
        L18.repoFile("ledger/src/main/resources/db/migration").listFiles().orEmpty().forEach {
            it.readText().lowercase().contains(Regex("tax_?year")) shouldBe false
        }
    }

    // ---------------------------------------------------------------- AC10 (server side of the tab)

    test("L-18 AC10: the years route lists years that have at least one row, newest first, and Main.kt wires it") {
        parse(L18.fetch(settleBook, "/v1/tax-year/$tp").body).getValue("years").jsonArray
            .map { it.toString().toInt() } shouldBe listOf(2026, 2025) // the AC6 book: rows in 2025 and 2026
        parse(L18.fetch(xyzRows, "/v1/tax-year/$tp").body).getValue("years").jsonArray
            .map { it.toString().toInt() } shouldBe listOf(2025)
        TaxYearReport.yearsWithRows(settleBook.shuffled(Random(1)), tp) shouldBe listOf(2026, 2025)
        TaxYearReport.yearsWithRows(xyzRows, "TP-NOPE").shouldBeEmpty()
        TaxYearReport.yearsWithRows(listOf(L18.buy("b", "S", y2025, share, 100, 1)), tp).shouldBeEmpty()
        val main = SourceText.withoutComments(SourceText.read("app/src/main/kotlin/dev/ledgerline/app/Main.kt"))
        main shouldContain "taxYearFromActivities"
    }
})
