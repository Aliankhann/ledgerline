package dev.ledgerline.property

import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side
import dev.ledgerline.ledger.UnbalancedEntryException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigInteger

/**
 * Invariants 1 and 8, L-03 phase B: `Posting.requireBalanced` never wraps. Line amounts are drawn mostly from near
 * Long.MAX_VALUE so per-unit sums overflow 64 bits. The oracle sums in BigInteger (test-side only; production code
 * must not). Allowed outcomes: accepted only if every exact per-unit sum is 0; `UNBALANCED` only with the exact
 * difference; or a loud `ArithmeticException`.
 */
class L03BalanceArithmeticPropertyTest : FunSpec({

    val max = Long.MAX_VALUE
    val amount: Arb<Long> =
        Arb.choice(
            Arb.long(max - 3, max),
            Arb.long(max / 2 - 2, max / 2 + 2),
            Arb.long(1, 3),
            Arb.long(1, max),
        )
    val line: Arb<LedgerLine> =
        arbitrary {
            LedgerLine(
                Arb.element("A", "B", "C").bind(),
                Arb.element("CAD", "USD").bind(),
                Arb.enum<Side>().bind(),
                amount.bind(),
            )
        }

    fun exactNet(lines: List<LedgerLine>): Map<String, BigInteger> =
        lines.groupBy { it.unit }.mapValues { (_, ls) ->
            ls.fold(BigInteger.ZERO) { acc, l ->
                val v = BigInteger.valueOf(l.amount)
                if (l.side == Side.DEBIT) acc + v else acc - v
            }
        }

    test("Invariants 1 and 8 (L-03 edge): requireBalanced agrees with exact sums or fails loudly (2000 cases)") {
        var accepted = 0
        var unbalanced = 0
        var overflow = 0
        val ctx =
            checkAll(2_000, Arb.list(line, 1..7)) { lines ->
                val net = exactNet(lines)
                withClue("lines=$lines exact=$net") {
                    try {
                        Posting.requireBalanced(lines)
                        accepted += 1
                        net.values.forEach { it shouldBe BigInteger.ZERO }
                    } catch (e: UnbalancedEntryException) {
                        unbalanced += 1
                        net[e.unit] shouldBe BigInteger.valueOf(e.difference)
                        net[e.unit] shouldNotBe BigInteger.ZERO
                    } catch (e: ArithmeticException) {
                        overflow += 1
                    }
                }
            }
        println(
            "L-03 requireBalanced arithmetic: ${ctx.attempts()} cases; " +
                "accepted=$accepted unbalanced=$unbalanced overflow=$overflow",
        )
        overflow shouldBeGreaterThan 0
        unbalanced shouldBeGreaterThan 0
    }

    test(
        "Invariants 1 and 8 (L-03 edge): balanced entries near Long.MAX are never UNBALANCED (1000 cases)",
    ) {
        checkAll(1_000, Arb.list(amount, 1..3)) { amounts ->
            val lines =
                amounts.mapIndexed { i, a -> LedgerLine("D$i", "CAD", Side.DEBIT, a) } +
                    amounts.mapIndexed { i, a -> LedgerLine("C$i", "CAD", Side.CREDIT, a) }
            withClue("lines=$lines") {
                try {
                    Posting.requireBalanced(lines)
                } catch (e: ArithmeticException) {
                    // Loud, not wrapped. Whether this should be accepted instead is B-005.
                }
            }
        }
    }
})
