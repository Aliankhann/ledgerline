package dev.ledgerline.property

import dev.ledgerline.core.ActivityType
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.CsvRows.BOM
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/** L-01 generator properties (invariants 5 and 7 applied to ingestion). Source: docs/stories/L-01 AC6, AC7, AC9. */
class L01CsvPropertyTest : FunSpec({
    val seeds = Arb.long()
    val taxpayerCounts = Arb.int(1..4)

    test("L-01 property: same seed gives byte-identical output (20 seeds)") {
        checkAll(20, seeds, taxpayerCounts) { seed, n ->
            SyntheticGenerator.generate(seed, n, 2025) shouldBe SyntheticGenerator.generate(seed, n, 2025)
        }
    }

    test("L-01 property: generated files parse with no rejections and unique keys (50 seeds)") {
        checkAll(50, seeds, taxpayerCounts) { seed, n ->
            val r = CanonicalCsv.parse(SyntheticGenerator.generate(seed, n, 2025))
            r.rejections shouldHaveSize 0
            r.fileErrors shouldHaveSize 0
            r.activities.map { it.idempotencyKey }.toSet() shouldHaveSize r.activities.size
            r.activities.all { it.effectiveDate.year == 2025 } shouldBe true
        }
    }

    test("L-01 property: no SELL exceeds shares held at its effective date (invariant 7) (100 seeds)") {
        checkAll(100, seeds, taxpayerCounts) { seed, n ->
            val acts = CanonicalCsv.parse(SyntheticGenerator.generate(seed, n, 2025)).activities
            val held = mutableMapOf<Pair<String, String>, Long>()
            // stable sort keeps file order within a date
            for (a in acts.sortedBy { it.effectiveDate }) {
                val k = a.accountId to (a.securityId ?: continue)
                when (a.activityType) {
                    ActivityType.BUY, ActivityType.TRANSFER_IN -> held.merge(k, a.quantity!!, Long::plus)
                    ActivityType.SELL -> {
                        val after = (held[k] ?: 0L) - a.quantity!!
                        (after >= 0L) shouldBe true
                        held[k] = after
                    }
                    else -> Unit
                }
            }
        }
    }

    test("L-01 property: parse(serialize(x)) == x and serialize(parse(file)) == file (50 seeds)") {
        checkAll(50, seeds, taxpayerCounts) { seed, n ->
            val text = SyntheticGenerator.generate(seed, n, 2025)
            val acts = CanonicalCsv.parse(text).activities
            CanonicalCsv.parse(CanonicalCsv.serialize(acts)).activities shouldBe acts
            CanonicalCsv.serialize(acts) shouldBe text
        }
    }

    test("L-01 property: output is stable under CRLF and BOM re-encoding (20 seeds)") {
        checkAll(20, seeds, taxpayerCounts) { seed, n ->
            val text = SyntheticGenerator.generate(seed, n, 2025)
            val base = CanonicalCsv.parse(text).activities
            CanonicalCsv.parse(BOM + text.replace("\n", "\r\n")).activities shouldBe base
        }
    }
})
