package dev.ledgerline.spec

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ingest.AcbCsvExporter
import dev.ledgerline.testsupport.AcbRun
import dev.ledgerline.testsupport.AcbSecurity
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L15Compare
import dev.ledgerline.testsupport.L15Oracle
import dev.ledgerline.testsupport.LedgerlineSecurity
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.time.LocalDate

/**
 * Story L-15 (docs/stories/L-15-differential-acb-check.md): AC1, AC5 and AC6. The gating comparison over seeds
 * 1..200 (AC2 to AC4) is `dev.ledgerline.property.L15DifferentialAcbPropertyTest`.
 *
 * API contract for Dev (the story names none): package `dev.ledgerline.ingest`,
 * `object AcbCsvExporter { fun export(activities: List<Activity>): String }`. It writes the acb CSV of AC1 for the
 * BUY and SELL activities (other types produce no row), in canonical order (tax date = settlement date else trade
 * date, then `sourceSeq`, then idempotency key), whatever order the list arrives in. It throws (any exception, for
 * example `IllegalArgumentException`) and returns no text when a BUY or SELL unit price (`amount / quantity`) is not
 * an exact number of cents.
 *
 * The oracle (`acb`) is located by `ACB_BIN`, else `PATH`; see `tests/fixtures/l15/oracle.properties` for the pin.
 */
class L15DifferentialAcbSpec :
    FunSpec({
        val header =
            "security,trade date,settlement date,action,shares,amount/share,currency,exchange rate,commission,memo"
        val share = Activities.SHARE

        fun trade(
            key: String,
            type: ActivityType,
            effective: String,
            settlement: String?,
            security: String,
            units: Long,
            amountCents: Long,
            commissionCents: Long,
            seq: Long = 0L,
        ): Activity =
            Activities.activity(
                key = key,
                type = type,
                date = LocalDate.parse(effective),
                amountMinor = amountCents,
                securityId = security,
                quantity = units,
                commissionMinor = commissionCents,
                sourceSeq = seq,
            ).copy(settlementDate = settlement?.let(LocalDate::parse))

        val buy =
            trade(
                "TP-0001-2025-0002",
                ActivityType.BUY,
                "2025-01-07",
                "2025-01-08",
                "SYN-AAA",
                100 * share,
                3000_00,
                495,
            )
        val sell =
            trade(
                "TP-0001-2025-0003",
                ActivityType.SELL,
                "2025-02-10",
                "2025-02-11",
                "SYN-AAA",
                40 * share,
                1400_00,
                495,
            )

        fun lines(vararg row: String) = (listOf(header) + row).joinToString("\n", postfix = "\n")

        test("L-15 AC1: the story's two activities export to the exact text") {
            AcbCsvExporter.export(listOf(buy, sell)) shouldBe
                lines(
                    "SYN-AAA,2025-01-08,2025-01-08,Buy,100,30.00,CAD,,4.95,TP-0001-2025-0002",
                    "SYN-AAA,2025-02-11,2025-02-11,Sell,40,35.00,CAD,,4.95,TP-0001-2025-0003",
                )
        }

        test("L-15 AC1: rows are in canonical order whatever order the activities arrive in") {
            AcbCsvExporter.export(listOf(sell, buy)) shouldBe AcbCsvExporter.export(listOf(buy, sell))
            // Tax date, not trade date, decides: A traded first but settles last.
            val a = trade("K-A", ActivityType.BUY, "2025-03-03", "2025-03-20", "SYN-AAA", share, 10_00, 0)
            val b = trade("K-B", ActivityType.BUY, "2025-03-10", "2025-03-12", "SYN-AAA", share, 10_00, 0)
            AcbCsvExporter.export(listOf(a, b)).lines().drop(1).take(2).map { it.substringAfterLast(',') } shouldBe
                listOf("K-B", "K-A")
            // Same tax date: sourceSeq decides, then the key.
            val s2 = trade("K-1", ActivityType.BUY, "2025-03-12", "2025-03-12", "SYN-AAA", share, 10_00, 0, seq = 2)
            val s1 = trade("K-2", ActivityType.BUY, "2025-03-12", "2025-03-12", "SYN-AAA", share, 10_00, 0, seq = 1)
            AcbCsvExporter.export(listOf(s2, s1)).lines().drop(1).take(2).map { it.substringAfterLast(',') } shouldBe
                listOf("K-2", "K-1")
        }

        test("L-15 AC1: with no settlement date the trade date is written in both date columns") {
            val noSettlement = buy.copy(settlementDate = null)
            AcbCsvExporter.export(listOf(noSettlement)) shouldBe
                lines("SYN-AAA,2025-01-07,2025-01-07,Buy,100,30.00,CAD,,4.95,TP-0001-2025-0002")
        }

        test("L-15 AC1: only BUY and SELL produce rows") {
            val deposit = Activities.activity("K-D", ActivityType.DEPOSIT, LocalDate.parse("2025-01-02"), 5000_00)
            val fee = Activities.activity("K-F", ActivityType.FEE, LocalDate.parse("2025-01-09"), 10_00)
            AcbCsvExporter.export(listOf(deposit, buy, fee, sell)) shouldBe AcbCsvExporter.export(listOf(buy, sell))
        }

        test("L-15 AC1: shares are a plain decimal without trailing zeros") {
            fun sharesOf(units: Long): String {
                val cents = units * 200 / share // price 2.00 per share, exact for the units used below
                val text =
                    AcbCsvExporter.export(
                        listOf(trade("K", ActivityType.BUY, "2025-01-08", "2025-01-08", "SYN-AAA", units, cents, 0)),
                    )
                return text.lines()[1].split(',')[4]
            }
            sharesOf(100 * share) shouldBe "100"
            sharesOf(10_500_000) shouldBe "10.5"
            sharesOf(250_000) shouldBe "0.25"
            sharesOf(1_000_000) shouldBe "1"
            sharesOf(1_200_000_000) shouldBe "1200"
        }

        test("L-15 AC1: amount per share has exactly two decimals and commission has two decimals, no separators") {
            fun row(
                units: Long,
                cents: Long,
                commission: Long,
            ) = AcbCsvExporter.export(
                listOf(trade("K", ActivityType.SELL, "2025-01-08", "2025-01-08", "SYN-AAA", units, cents, commission)),
            )
                .lines()[1].split(',')

            row(10 * share, 10_50, 0)[5] shouldBe "1.05"
            row(2 * share, 1_00, 0)[5] shouldBe "0.50"
            row(1 * share, 12_30, 0)[5] shouldBe "12.30"
            row(1 * share, 1_000_000_000L, 0)[5] shouldBe "10000000.00"
            row(1 * share, 1_00, 0)[8] shouldBe "0.00"
            row(1 * share, 1_00, 5)[8] shouldBe "0.05"
            row(1 * share, 1_00, 995)[8] shouldBe "9.95"
            row(1 * share, 1_00, 123_456)[8] shouldBe "1234.56"
        }

        test("L-15 AC1: the exporter rejects a unit price that is not an exact number of cents") {
            val third = trade("K-3", ActivityType.BUY, "2025-01-08", "2025-01-08", "SYN-AAA", 3 * share, 10_00, 0)
            shouldThrowAny { AcbCsvExporter.export(listOf(third)) }
            val fractional = trade("K-4", ActivityType.SELL, "2025-01-08", "2025-01-08", "SYN-AAA", 2_500_000, 10_01, 0)
            shouldThrowAny { AcbCsvExporter.export(listOf(fractional)) }
            // One bad activity among good ones: nothing is returned.
            shouldThrowAny { AcbCsvExporter.export(listOf(buy, third, sell)) }
        }

        test("L-15 AC1: acb accepts the exported CSV (needs the acb binary)")
            .config(tags = setOf(Integration), enabledOrReasonIf = { L15Oracle.enabledOrSkip() }) {
                val run = L15Oracle.run(L15Oracle.locate()!!, AcbCsvExporter.export(listOf(buy, sell)))
                run.securities.keys shouldBe setOf("SYN-AAA")
                run.securities.getValue("SYN-AAA").sells.map { it.key } shouldBe listOf("TP-0001-2025-0003")
            }

        // ---- AC5 -----------------------------------------------------------------------------------------------

        test("L-15 AC5: a difference reads seed=<n> security=<s> field=<name> ledgerline=<v> acb=<v> (format only)") {
            // Hand-made values that exist only to exercise the message; no tax value is asserted.
            val ledger = mapOf("SYN-AAA" to LedgerlineSecurity("SYN-AAA", 60 * share, 100_00, emptyList()))
            val acb = AcbRun(mapOf("SYN-AAA" to AcbSecurity("SYN-AAA", 61 * share, 100_00, emptyList())))
            val diffs = L15Compare.compare(7, ledger, acb)
            diffs.map { it.message } shouldBe listOf("seed=7 security=SYN-AAA field=finalShares ledgerline=60 acb=61")
        }

        test("L-15 AC5: an altered Ledgerline result for seed 7 SYN-AAA fails with the named field")
            .config(tags = setOf(Integration), enabledOrReasonIf = { L15Oracle.enabledOrSkip() }) {
                val (ledger, acb) = L15Compare.both(7, L15Oracle.locate()!!)
                L15Compare.compare(7, ledger, acb) shouldBe emptyList()
                val aaa = ledger.getValue("SYN-AAA")
                val altered = ledger + ("SYN-AAA" to aaa.copy(finalShares = aaa.finalShares + share))
                val messages = L15Compare.compare(7, altered, acb).map { it.message }
                messages.shouldNotBeEmpty()
                messages.single() shouldMatch Regex("""seed=7 security=SYN-AAA field=\S+ ledgerline=\S+ acb=\S+""")
            }

        // ---- AC6 -----------------------------------------------------------------------------------------------

        test("L-15 AC6: without acb on PATH the skip reason is the story's message") {
            val empty = Files.createTempDirectory("l15-no-acb").toString()
            L15Oracle.skipReason(mapOf("PATH" to empty)) shouldBe
                "acb not found on PATH: install with cargo install --git https://github.com/tsiemens/acb.git"
            L15Oracle.skipReason(mapOf("PATH" to empty, "ACB_BIN" to "$empty/missing")) shouldBe L15Oracle.SKIP_MESSAGE
        }

        test("L-15 AC6: the pinned build, when present, is the one the fixture names")
            .config(tags = setOf(Integration), enabledOrReasonIf = { L15Oracle.enabledOrSkip() }) {
                L15Oracle.versionLine(L15Oracle.locate()!!) shouldBe L15Oracle.pinnedVersion()
            }
    })
