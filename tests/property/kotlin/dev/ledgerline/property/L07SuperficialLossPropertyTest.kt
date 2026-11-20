package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.SlOutcome
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlin.random.Random

/**
 * L-07 properties, rule set `CA-ITA-2026.1`. The API contract is in the KDoc of
 * `dev.ledgerline.spec.L07SuperficialLossSpec`.
 * Streams come from [L06Streams] (two securities, two taxpayers, TFSA and non-registered accounts, dates a few days
 * apart so windows overlap often). No expected tax value is computed here: the oracles are "a gain is never flagged"
 * (ITA-54 defines a superficial loss as a loss) and equality across arrival orders (invariant 6).
 */
class L07SuperficialLossPropertyTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)

    test("L-07 [CA-ITA-2026.1]: a sale at a gain is never flagged and has no triggers (500 cases)") {
        var gains = 0
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 40)) { stream ->
                engine.replaySuperficial(stream).sales.filter { it.gainBeforeRule >= 0L }.forEach { s ->
                    s.denied shouldBe 0L
                    s.outcome shouldBe SlOutcome.NONE
                    s.triggers.size shouldBe 0
                    s.reportedGain shouldBe s.gainBeforeRule
                    gains++
                }
            }
        println("L-07 gain-never-flagged: ${ctx.attempts()} streams, $gains gain sales checked")
        ctx.attempts() shouldBeGreaterThanOrEqual 500
        gains shouldBeGreaterThanOrEqual 1
    }

    test("Invariant 6 (L-07 AC10) [CA-ITA-2026.1]: any arrival order gives equal sales, pools and hash (500 cases)") {
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 40)) { stream ->
                val canonical = engine.replaySuperficial(stream)
                repeat(3) { n ->
                    val replayed = engine.replaySuperficial(stream.shuffled(Random(stream.size * 31 + n)))
                    replayed shouldBe canonical
                    replayed.outputHash shouldBe canonical.outputHash
                }
                engine.replaySuperficial(stream.reversed()) shouldBe canonical
            }
        ctx.attempts() shouldBeGreaterThanOrEqual 500
    }
})
