package dev.ledgerline.spec

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.TaxYearReport
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Currency
import dev.ledgerline.testsupport.L17.ids
import dev.ledgerline.testsupport.L17.long
import dev.ledgerline.testsupport.L17.parse
import dev.ledgerline.testsupport.L17.strings
import dev.ledgerline.testsupport.L17.text
import dev.ledgerline.testsupport.L18
import dev.ledgerline.testsupport.L18.centsOf
import dev.ledgerline.testsupport.L18.parseCsv
import dev.ledgerline.testsupport.L18.rowList
import dev.ledgerline.testsupport.L18.totals
import dev.ledgerline.testsupport.L18.warningList
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * L-18 phase B: adversarial tests against `TaxYearReport` and the tax-year routes. No tax figure is asserted here
 * except through conservation identities (proceeds - outlays - costs) or the engine's own fields; nothing asserts an
 * OWNER-INPUT choice (odd-cent half inclusion, CSV money format, DEEMED_GAIN_ROC rows). Reversals are applied below the
 * route (`ActivityStore.effectiveActivities` drops reversed pairs, which needs Postgres), so the reversal-shaped test
 * feeds the effective set that results.
 */
class L18AttackSpec : FunSpec({

    val tp = L18.tp
    val share = L18.SHARE

    fun d(text: String) = LocalDate.parse(text)

    fun inAccount(
        p: PostedActivity,
        type: AccountType,
    ): PostedActivity = p.copy(activity = p.activity.copy(accountType = type, accountId = "$tp-${type.name}"))

    fun years(book: List<PostedActivity>) = (2020..2030).filter { TaxYearReport.build(book, tp, it).rows.isNotEmpty() }

    // Superficial loss straddling the year end, then a final sale of everything in 2027.
    val straddle =
        listOf(
            L18.buy("st-b1", "SYN-STR", d("2025-06-02"), 10 * share, 100_000, 1, commission = 500),
            L18.sell("st-s1", "SYN-STR", d("2025-12-18"), 10 * share, 60_000, 2, commission = 700),
            L18.buy("st-b2", "SYN-STR", d("2026-01-05"), 10 * share, 50_000, 3, commission = 300),
            L18.sell("st-s2", "SYN-STR", d("2027-03-01"), 10 * share, 90_000, 4, commission = 900),
        )

    // A loss sale whose substituted property is bought in a TFSA, plus TFSA noise that must never become a row.
    val registered =
        listOf(
            L18.buy("rg-b1", "SYN-REG", d("2025-02-03"), 10 * share, 100_000, 1),
            L18.sell("rg-s1", "SYN-REG", d("2025-05-05"), 10 * share, 60_000, 2),
            inAccount(L18.buy("rg-t1", "SYN-REG", d("2025-05-20"), 10 * share, 55_000, 3), AccountType.TFSA),
            inAccount(L18.sell("rg-t2", "SYN-REG", d("2025-08-01"), 10 * share, 80_000, 4), AccountType.TFSA),
            inAccount(L18.buy("rg-o1", "SYN-TFSA-ONLY", d("2025-02-03"), 10 * share, 10_000, 5), AccountType.TFSA),
            inAccount(L18.sell("rg-o2", "SYN-TFSA-ONLY", d("2025-09-03"), 20 * share, 90_000, 6), AccountType.RRSP),
            L18.buy("rg-n1", "SYN-PLAIN", d("2025-01-03"), 4 * share, 4_000, 7),
            L18.sell("rg-n2", "SYN-PLAIN", d("2025-09-03"), 4 * share, 9_000, 8),
        )

    // ------------------------------------------------------------------ 1

    test(
        "L-18 attack 1: a loss sold in late December and repurchased in January is a 2025 row, and years conserve cash",
    ) {
        val y25 = L18.year(straddle, 2025)
        val row = y25.rowList().single()
        row.text("taxDate") shouldBe "2025-12-18"
        row.long("superficialLossDeniedCents") shouldBeGreaterThan 0L
        row.ids("entryIds") shouldBe listOf(2L)
        row.ids("substituteEntryIds") shouldBe listOf(3L) // the January buy, a 2026 entry
        row.strings("notes").any { "SL-PARTIAL" in it && "3" in it } shouldBe true
        // The repurchase does not move the sale into 2026, and 2026 has no sale of its own.
        L18.year(straddle, 2026).rowList().shouldBeEmpty()
        y25.totals().long("superficialLossesDeniedCents") shouldBe row.long("superficialLossDeniedCents")
        // proceeds - outlays - ACB + denied = gain, within the 1 cent of per-row rounding.
        val identity =
            row.long("proceedsCents") - row.long("outlaysCents") - row.long("acbSoldCents") +
                row.long("superficialLossDeniedCents")
        abs(identity - row.long("gainCents")) shouldBeLessThanOrEqual 1L
        // Everything is sold by 2027, so the denied loss must come back through the later ACB: lifetime net equals
        // sale proceeds - sale commissions - buy costs - buy commissions (a conservation law, not a tax figure).
        val lifetime = (2025..2027).sumOf { L18.year(straddle, it).totals().long("netCents") }
        val cash = (60_000L - 700) + (90_000L - 900) - (100_000L + 500) - (50_000L + 300)
        withClue("lifetime net across 2025..2027 vs cash conservation") { lifetime shouldBe cash }
        L18.year(straddle, 2027).rowList().single().ids("substituteEntryIds").shouldBeEmpty()
    }

    // ------------------------------------------------------------------ 2

    test("L-18 attack 2: a TFSA repurchase denies the non-registered loss, and registered sales are never rows") {
        val body = L18.year(registered, 2025)
        val rows = body.rowList()
        rows.map { it.ids("entryIds").single() }.sorted() shouldBe listOf(2L, 8L) // never TFSA 4 or RRSP 6
        val sl = rows.single { it.ids("entryIds") == listOf(2L) }
        sl.long("superficialLossDeniedCents") shouldBeGreaterThan 0L
        sl.ids("substituteEntryIds") shouldContain 3L
        sl.strings("ruleIds") shouldContain "SL-REGISTERED"
        body.totals().long("superficialLossesDeniedCents") shouldBe sl.long("superficialLossDeniedCents")
        body.warningList().shouldBeEmpty() // a registered sale larger than its registered holding is not a data gap
        // Registered activity does not change the plain security: same row with the TFSA/RRSP noise removed.
        val plain = registered.filter { it.entryId in setOf(7L, 8L) }
        L18.year(plain, 2025).rowList().single().long("gainCents") shouldBe
            rows.single { it.ids("entryIds") == listOf(8L) }.long("gainCents")
        TaxYearReport.yearsWithRows(registered.filter { it.entryId in setOf(5L, 6L) }, tp).shouldBeEmpty()
    }

    // ------------------------------------------------------------------ 3

    test("L-18 attack 3: eight securities and 96 sales across three years keep every sum identity, JSON and CSV") {
        val rnd = Random(2026)
        val book = mutableListOf<PostedActivity>()
        var entry = 0L
        var sells = 0
        for (s in 1..8) {
            var held = 0L
            var date = d("2024-01-02")
            repeat(24) { i ->
                date = date.plusDays(rnd.nextLong(3, 40))
                entry++
                val gross = rnd.nextLong(500, 80_000)
                val commission = rnd.nextLong(0, 900)
                val units = rnd.nextLong(1, 9) * share + rnd.nextLong(0, 1_000) * 1_000
                if (held == 0L || i % 2 == 0) {
                    book += L18.buy("m$s-$i", "SYN-M$s", date, units, gross, entry, entry, commission)
                    held += units
                } else {
                    val take = minOf(units, held)
                    book += L18.sell("m$s-$i", "SYN-M$s", date, take, gross, entry, entry, commission)
                    held -= take
                    sells++
                }
            }
        }
        var rowTotal = 0
        for (y in 2024..2027) {
            val res =
                L18.fetchAll(
                    book,
                    listOf(HttpMethod.Get to "/v1/tax-year/$tp/$y", HttpMethod.Get to "/v1/tax-year/$tp/$y.csv"),
                )
            val body = parse(res[0].body)
            val rows = body.rowList()
            val t = body.totals()
            rowTotal += rows.size
            t.long("netCents") shouldBe rows.sumOf { it.long("gainCents") }
            t.long("gainsCents") shouldBe rows.map { it.long("gainCents") }.filter { it > 0 }.sum()
            t.long("lossesCents") shouldBe rows.map { it.long("gainCents") }.filter { it < 0 }.sum()
            t.long("gainsCents") + t.long("lossesCents") shouldBe t.long("netCents")
            t.long("superficialLossesDeniedCents") shouldBe rows.sumOf { it.long("superficialLossDeniedCents") }
            rows.forEach { it.long("superficialLossDeniedCents") shouldBeGreaterThan -1L }
            val recs = parseCsv(res[1].body)
            recs.size shouldBe rows.size + 2
            val gainCol = L18.CSV_HEADER.indexOf("gain")
            recs.subList(1, recs.size - 1).sumOf { centsOf(it[gainCol]) } shouldBe centsOf(recs.last()[gainCol])
            centsOf(recs.last()[gainCol]) shouldBe t.long("netCents")
        }
        withClue("every accepted SELL is exactly one row in exactly one year") { rowTotal shouldBe sells }
    }

    // ------------------------------------------------------------------ 4

    test("L-18 attack 4: hostile security ids stay one CSV record each, are neutralised, and JSON keeps the raw id") {
        val ids =
            listOf(
                "=HYPERLINK(\"http://x\",\"y\")", "+1", "-1", "@SUM(A1)", "A,B", "Q\"UOTE", "NEW\nLINE", "CR\rX",
                "=A,\"B\"\nC",
            )
        val book =
            ids.flatMapIndexed { i, id ->
                listOf(
                    L18.buy("h$i-b", id, d("2025-02-01"), share, 1_000, 10L + 2 * i),
                    // Alternate gain and loss so negative money cells start with '-' too.
                    L18.sell("h$i-s", id, d("2025-03-01"), share, if (i % 2 == 0) 2_000 else 500, 11L + 2 * i),
                )
            }
        val res =
            L18.fetchAll(
                book,
                listOf(HttpMethod.Get to "/v1/tax-year/$tp/2025", HttpMethod.Get to "/v1/tax-year/$tp/2025.csv"),
            )
        res.forEach { it.status shouldBe HttpStatusCode.OK }
        val json = parse(res[0].body).rowList().map { it.text("securityId") }
        json.sorted() shouldBe ids.sorted()
        val recs = parseCsv(res[1].body)
        recs.size shouldBe ids.size + 2
        recs.forEach { it.size shouldBe L18.CSV_HEADER.size }
        val secCol = L18.CSV_HEADER.indexOf("security")
        val notesCol = L18.CSV_HEADER.indexOf("notes")
        val cells = recs.subList(1, recs.size - 1)
        cells.map { it[secCol].removePrefix("'") }.sorted() shouldBe ids.sorted()
        cells.forEach { rec ->
            withClue("security '${rec[secCol]}'") {
                (rec[secCol].firstOrNull() in setOf('=', '+', '-', '@')) shouldBe false
                (rec[notesCol].firstOrNull() in setOf('=', '+', '-', '@')) shouldBe false
            }
            // Money cells are numbers (a loss starts with '-') and are never prefixed.
            listOf("shares", "proceeds", "acbSold", "outlays", "gain", "superficialLossDenied").forEach {
                centsOf(rec[L18.CSV_HEADER.indexOf(it)])
            }
        }
    }

    // ------------------------------------------------------------------ 5

    test("L-18 attack 5: year path edge cases are 4xx with a message naming the year, never a 5xx or a silent 200") {
        val bad =
            listOf(
                "0", "00", "0000", "10000", "99999", "-1", "-2025", "+2025", "2024.csv.csv", ".csv", "csv", "2025.CSV",
                "2025.json", "2025.", "20x5.csv", "%D9%A2%D9%A0%D9%A2%D9%A5", "%20", "2025%20", "1e3", "0x7E9",
                "2147483648", "99999999999999999999",
            )
        val good = listOf("1", "9999", "2025", "2025.csv", "0001")
        val res = L18.fetchAll(straddle, (bad + good).map { HttpMethod.Get to "/v1/tax-year/$tp/$it" })
        bad.forEachIndexed { i, p ->
            withClue("GET /$p -> ${res[i].status} ${res[i].body.take(80)}") {
                res[i].status shouldBe HttpStatusCode.BadRequest
                res[i].body.lowercase().contains("year") shouldBe true
            }
        }
        good.forEachIndexed { i, p ->
            withClue("GET /$p -> ${res[bad.size + i].status}") { res[bad.size + i].status shouldBe HttpStatusCode.OK }
        }
    }

    // ------------------------------------------------------------------ 6

    test(
        "L-18 attack 6: the years list holds exactly the years that have rows (not rejected, zero-share or registered)",
    ) {
        val book =
            listOf(
                L18.buy("y-b1", "SYN-Y1", d("2024-01-02"), 2 * share, 2_000, 1),
                // rejected oversell, 2025 only
                L18.sell("y-s1", "SYN-Y1", d("2025-03-03"), 5 * share, 9_000, 2),
                L18.buy("y-b2", "SYN-Y2", d("2024-01-02"), 2 * share, 2_000, 3),
                // zero-quantity sell
                L18.sell("y-s2", "SYN-Y2", d("2026-03-03"), 0L, 100, 4),
                L18.buy("y-b3", "SYN-Y3", d("2024-01-02"), 2 * share, 2_000, 5),
                // the only real sale
                L18.sell("y-s3", "SYN-Y3", d("2027-03-03"), 2 * share, 3_000, 6),
                inAccount(L18.buy("y-b4", "SYN-Y4", d("2024-01-02"), 2 * share, 2_000, 7), AccountType.TFSA),
                inAccount(L18.sell("y-s4", "SYN-Y4", d("2028-03-03"), 2 * share, 3_000, 8), AccountType.TFSA),
                L18.transferIn("y-t5", "SYN-Y5", d("2029-03-03"), 3 * share, 9),
            )
        val listed =
            parse(L18.fetch(book, "/v1/tax-year/$tp").body).getValue("years").jsonArray.map { it.toString().toInt() }
        listed shouldBe listOf(2027)
        listed shouldBe years(book).reversed()
        (2020..2030).forEach { y ->
            withClue("year $y") { (y in listed) shouldBe L18.year(book, y).rowList().isNotEmpty() }
        }
        // The rejected sale is still visible as a warning in its own year, so it is not read as "no gain".
        L18.year(book, 2025).warningList().flatMap { it.ids("entryIds") } shouldContain 2L
    }

    // ------------------------------------------------------------------ 7

    test("L-18 attack 7: 30 arrival orders of a book with ties, a straddle and registered noise give identical bytes") {
        val ties =
            listOf(
                L18.buy("t-b", "SYN-TIE", d("2024-01-02"), 5 * share, 5_000, 40, 1),
                // same date, same sequence,
                L18.sell("t-sa", "SYN-TIE", d("2025-04-01"), 4 * share, 4_000, 41, 7),
                // one of them is an oversell
                L18.sell("t-sb", "SYN-TIE", d("2025-04-01"), 4 * share, 9_000, 42, 7),
                L18.sell("t-sc", "SYN-TIE", d("2025-04-01"), 1 * share, 700, 43, 7, settlement = d("2026-01-02")),
            )
        val book = straddle + registered.map { it.copy(entryId = it.entryId + 100) } + ties
        val paths =
            (2024..2028).flatMap { y -> listOf("$y", "$y.csv") }.map { HttpMethod.Get to "/v1/tax-year/$tp/$it" } +
                (HttpMethod.Get to "/v1/tax-year/$tp")
        val reference = L18.fetchAll(book, paths).map { it.body }
        val oversellStays = reference.joinToString().contains("DATA-GAP-OVERSELL")
        withClue("the tie book must actually contain a rejected sale") { oversellStays shouldBe true }
        repeat(30) { seed ->
            val shuffled = if (seed == 0) book.reversed() else book.shuffled(Random(seed))
            withClue("shuffle seed $seed") { L18.fetchAll(shuffled, paths).map { it.body } shouldBe reference }
        }
    }

    // ------------------------------------------------------------------ 8

    test("L-18 attack 8: a corrected sale (original reversed, replacement posted) moves with its new tax date") {
        val buy = L18.buy("c-b", "SYN-FIX", d("2024-01-02"), 5 * share, 5_000, 1)
        val original = L18.sell("c-s1", "SYN-FIX", d("2025-12-30"), 5 * share, 9_000, 2)
        val replacement = L18.sell("c-s2", "SYN-FIX", d("2026-01-03"), 5 * share, 9_500, 3)
        val before = listOf(buy, original)
        val after = listOf(buy, replacement) // effectiveActivities drops the reversed original
        L18.year(before, 2025).rowList().single().ids("entryIds") shouldBe listOf(2L)
        L18.year(after, 2025).rowList().shouldBeEmpty()
        val listed = parse(L18.fetch(after, "/v1/tax-year/$tp").body).getValue("years").jsonArray
        listed.map { it.toString().toInt() } shouldBe
            listOf(2026)
        val row = L18.year(after, 2026).rowList().single()
        row.ids("entryIds") shouldBe listOf(3L)
        row.long("proceedsCents") shouldBe 9_500L
        // Reversing the repurchase of a superficial loss restores the full loss.
        val saleAtLoss = L18.sell("c-l", "SYN-FIX2", d("2025-05-05"), 5 * share, 1_000, 11)
        val base = listOf(L18.buy("c-lb", "SYN-FIX2", d("2025-01-02"), 5 * share, 5_000, 10), saleAtLoss)
        val rebuy = L18.buy("c-lr", "SYN-FIX2", d("2025-05-10"), 5 * share, 1_000, 12)
        L18.year(base + rebuy, 2025).totals().long("superficialLossesDeniedCents") shouldBeGreaterThan 0L
        val restored = L18.year(base, 2025)
        restored.totals().long("superficialLossesDeniedCents") shouldBe 0L
        restored.rowList().single().long("gainCents") shouldBe -restored.rowList().single().long("acbSoldCents") +
            restored.rowList().single().long("proceedsCents") - restored.rowList().single().long("outlaysCents")
    }

    // ------------------------------------------------------------------ 9

    test("L-18 attack 9: a USD activity is not presented as CAD cents without conversion, a flag or a warning") {
        val usdBuy = L18.buy("u-b", "SYN-USD", d("2024-01-02"), 5 * share, 5_000, 1)
        val usdSell = L18.sell("u-s", "SYN-USD", d("2025-06-03"), 5 * share, 9_000, 2)
        val book = listOf(usdBuy, usdSell).map { it.copy(activity = it.activity.copy(currency = Currency.USD)) }
        val body = L18.year(book, 2025)
        val row = body.rowList().single()
        val raw = row.long("proceedsCents") == 9_000L
        val labelledCad = body.getValue("currency").toString().trim('"') == "CAD"
        val flagged = body.warningList().isNotEmpty() || row.keys.any { it.lowercase().contains("currency") }
        withClue("USD proceeds 9000 minor units shown as CAD 9000 cents, no warning, no per-row currency") {
            (raw && labelledCad && !flagged) shouldBe false
        }
    }

    // ------------------------------------------------------------------ 10

    test("L-18 attack 10: one huge amount does not turn the whole tax year or the years list into a 5xx") {
        val good =
            listOf(
                L18.buy("g-b", "SYN-OK", d("2024-01-02"), 5 * share, 5_000, 1),
                L18.sell("g-s", "SYN-OK", d("2025-03-03"), 5 * share, 9_000, 2),
            )
        val huge =
            listOf(
                L18.buy("h-b", "SYN-HUGE", d("2024-01-02"), 5 * share, 2_000_000_000_000_000L, 3),
                L18.sell("h-s", "SYN-HUGE", d("2025-03-03"), 5 * share, 9_000, 4),
            )
        val outcomes =
            listOf("/v1/tax-year/$tp", "/v1/tax-year/$tp/2025", "/v1/tax-year/$tp/2025.csv").map { path ->
                path to runCatching { L18.fetch(good + huge, path) }
            }
        outcomes.forEach { (path, r) ->
            withClue(
                "GET $path -> ${r.exceptionOrNull()?.let { "EXCEPTION ${it::class.simpleName}: ${it.message}" }}",
            ) {
                r.isSuccess shouldBe true
                (r.getOrThrow().status.value < 500) shouldBe true
            }
        }
        // The control: the good book on its own is fine.
        L18.year(good, 2025).rowList().size shouldNotBe 0
    }
})
