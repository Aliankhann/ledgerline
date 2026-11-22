package dev.ledgerline.property

import dev.ledgerline.testsupport.AcbRun
import dev.ledgerline.testsupport.Difference
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L15Compare
import dev.ledgerline.testsupport.L15Histories
import dev.ledgerline.testsupport.L15Oracle
import dev.ledgerline.testsupport.LedgerlineSecurity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Story L-15, AC2 to AC4: Ledgerline against tsiemens/acb over seeds 1..200 (settings in [L15Histories]). Every
 * expected value is what the pinned `acb` build printed; nothing here computes a tax value. A difference fails the
 * test with one `seed=.. security=.. field=.. ledgerline=.. acb=..` line each (AC5). Nobody edits a rule, fixture
 * or expected value to make it pass: file it under docs/bugs/ and list it in docs/status.md instead.
 *
 * Partial repurchases are included and gate (the story's open choice (b)); the run prints how many histories hold a
 * partial denial so the owner can see that share of the evidence.
 */
class L15DifferentialAcbPropertyTest :
    FunSpec({
        data class Case(val seed: Long, val ledgerline: Map<String, LedgerlineSecurity>, val acb: AcbRun)

        // Computed once, on first use, so AC2 to AC4 share the 200 acb runs.
        val cases: List<Case> by lazy {
            val bin = checkNotNull(L15Oracle.locate()) { L15Oracle.SKIP_MESSAGE }
            L15Histories.seeds.map { seed ->
                val (ledgerline, acb) = L15Compare.both(seed, bin)
                Case(seed, ledgerline, acb)
            }
        }

        fun gate(
            ac: Int,
            what: String,
        ) {
            val diffs: List<Difference> =
                cases.flatMap {
                    L15Compare.compare(it.seed, it.ledgerline, it.acb)
                }.filter { it.ac == ac }
            val failed = diffs.map { it.seed }.distinct().size
            val sells = cases.sumOf { c -> c.acb.securities.values.sumOf { it.sells.size } }
            println(
                "L-15 AC$ac ($what): ${cases.size - failed} of ${cases.size} histories match, $sells sells compared",
            )
            check(diffs.isEmpty()) {
                "L-15 AC$ac: $failed of ${cases.size} histories differ (${diffs.size} differences). First 25:\n" +
                    diffs.take(25).joinToString("\n") { it.message }
            }
        }

        val enabled = { _: Any -> L15Oracle.enabledOrSkip() }

        test("L-15 oracle: the binary is the pinned build")
            .config(tags = setOf(Integration), enabledOrReasonIf = enabled) {
                L15Oracle.versionLine(L15Oracle.locate()!!) shouldBe L15Oracle.pinnedVersion()
            }

        test("L-15 AC2: final shares and final ACB match acb in 200 of 200 histories")
            .config(tags = setOf(Integration), enabledOrReasonIf = enabled) { gate(2, "final position") }

        test("L-15 AC3: every sell's gain or loss matches acb to the cent, same sells in the same order")
            .config(tags = setOf(Integration), enabledOrReasonIf = enabled) { gate(3, "gain per sell") }

        test("L-15 AC4: denied amounts, ACB added back and the set of denied sells match acb")
            .config(tags = setOf(Integration), enabledOrReasonIf = enabled) { gate(4, "superficial loss") }

        test("L-15 AC4: at least 20 of the 200 histories hold a denial in acb (the check is not vacuous)")
            .config(tags = setOf(Integration), enabledOrReasonIf = enabled) {
                val withDenial =
                    cases.count {
                            c ->
                        c.acb.securities.values.any { s -> s.sells.any { it.deniedCents != 0L } }
                    }
                val withPartial = cases.count { c -> c.acb.securities.values.any { s -> s.sells.any { it.partial } } }
                println(
                    "L-15 AC4 coverage: $withDenial of ${cases.size} with a denial, $withPartial with a partial denial",
                )
                withDenial shouldBeGreaterThanOrEqual 20
            }
    })
