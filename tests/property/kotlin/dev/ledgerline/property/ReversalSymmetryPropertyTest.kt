package dev.ledgerline.property

import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.testsupport.ActivityStreams
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.checkAll

/**
 * Invariant 4 (Reversal symmetry), story L-04 AC9. The API contract is in the KDoc of
 * `dev.ledgerline.spec.L04ReversalSpec`.
 *
 * Each case posts a whole generated stream, then walks the activities in random order and tries to reverse each
 * one that the test's own oracle says is allowed: a reversal is allowed only if, after removing it and everything
 * already reversed, no account ever holds fewer than 0 units of a security (invariant 7, walking effective date
 * then source sequence). Allowed reversals must succeed. Disallowed ones must be refused with NEGATIVE_SHARES and
 * leave the account's balances unchanged. At the end, the case's investor accounts must hold exactly what posting
 * only the activities that were not reversed would give.
 *
 * Cases share one database. Every case remaps the generator's accounts to unique per-case accounts, so cases cannot
 * interfere; the system accounts they all touch are checked once at the end against the sum of the expectations.
 */
class ReversalSymmetryPropertyTest : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    test(
        "Invariant 4 (L-04 AC9, pure): reversed lines cancel the entry and balance (1000 cases)",
    ) {
        val ctx =
            checkAll(1_000, ActivityStreams.stream(maxLength = 20, keyPrefix = "AC9PURE")) { stream ->
                stream.forEach { a ->
                    val lines = Posting.linesFor(a)
                    val reversed = Posting.reversalOf(lines)
                    LineMath.imbalances(reversed).shouldBeEmpty()
                    Balances.of(lines + reversed).shouldBeEmpty()
                    Posting.reversalOf(reversed) shouldBe lines
                }
            }
        println("L-04 AC9 / invariant 4 (pure): ran ${ctx.attempts()} generated cases")
        ctx.attempts() shouldBeGreaterThanOrEqual 1_000
    }

    test(
        "Invariant 4 (L-04 AC9): reverse a random subset; balances equal a ledger without them (1000 cases)",
    )
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            var expectedAll = mapOf<Pair<String, String>, Long>()
            var reversedTotal = 0
            var refusedTotal = 0
            var activitiesTotal = 0
            val ctx =
                checkAll(1_000, ActivityStreams.stream(maxLength = 8, keyPrefix = "AC9")) { generated ->
                    val tag = generated.first().idempotencyKey.substringBeforeLast('-')
                    val stream = generated.map { it.copy(accountId = "${it.accountId}-$tag") }
                    val entries = stream.associate { it.idempotencyKey to repo.post(it) }
                    activitiesTotal += stream.size

                    val live = stream.toMutableList()
                    val order = stream.shuffled(kotlin.random.Random(tag.hashCode().toLong()))
                    order.forEach { a ->
                        val without = live - a
                        val allowed = Balances.holdingsNeverNegative(without)
                        val sequence = entries.getValue(a.idempotencyKey).sequence
                        if (allowed && kotlin.random.Random(a.idempotencyKey.hashCode().toLong()).nextBoolean()) {
                            repo.reverse(sequence, "REV-${a.idempotencyKey}", "property test")
                            live.remove(a)
                            reversedTotal += 1
                        } else if (!allowed) {
                            val e =
                                runCatching { repo.reverse(sequence, "REV-${a.idempotencyKey}", "property test") }
                                    .exceptionOrNull()
                            e.shouldBeInstanceOf<ReversalRefusedException>().code shouldBe "NEGATIVE_SHARES"
                            refusedTotal += 1
                        }
                    }

                    val expected = Balances.ofActivities(live)
                    val stored =
                        db.query(
                            db.owner,
                            "SELECT account, unit, sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) " +
                                "FROM ledger.line WHERE account LIKE '%-$tag' GROUP BY account, unit",
                        ).associate { (it[0]!! to it[1]!!) to it[2]!!.toLong() }.filterValues { it != 0L }
                    stored shouldBe expected.filterKeys { it.first.endsWith("-$tag") }
                    expectedAll = merge(expectedAll, expected)
                }
            println(
                "L-04 AC9 / invariant 4: ran ${ctx.attempts()} generated cases, $activitiesTotal activities, " +
                    "$reversedTotal reversed, $refusedTotal refused NEGATIVE_SHARES",
            )
            ctx.attempts() shouldBeGreaterThanOrEqual 1_000
            // Whole ledger, system accounts included: reversed activities leave no trace in any balance.
            Balances.ofEntries(repo.entries()) shouldBe expectedAll
            repo.entries().forEach { LineMath.imbalances(it.lines).shouldBeEmpty() }
            (reversedTotal > 0) shouldBe true
        }
})

private fun merge(
    a: Map<Pair<String, String>, Long>,
    b: Map<Pair<String, String>, Long>,
): Map<Pair<String, String>, Long> =
    (a.keys + b.keys).associateWith { Math.addExact(a[it] ?: 0L, b[it] ?: 0L) }.filterValues { it != 0L }
