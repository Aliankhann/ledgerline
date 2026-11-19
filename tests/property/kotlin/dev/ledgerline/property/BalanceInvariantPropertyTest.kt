package dev.ledgerline.property

import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.ledger.EntryRequest
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side
import dev.ledgerline.ledger.UnbalancedEntryException
import dev.ledgerline.testsupport.ActivityStreams
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.LineMath.securityUnit
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import java.time.LocalDate

/**
 * Invariant 1 (Balance), story L-03 AC8: every ledger entry's debits equal its credits in every unit, where a unit
 * is a currency (`CAD`, `USD`) or a security commodity `SEC:<securityId>` (owner placeholder: share holdings are
 * ledger lines). Share scale is the C3 placeholder 10^-6; `sourceSeq` follows the C4 placeholder. The API contract
 * is in the KDoc of `dev.ledgerline.spec.L03LedgerSpec`.
 */
class BalanceInvariantPropertyTest : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    test("Invariant 1 (L-03 AC8): every generated entry balances per currency and per security unit (1000 cases)") {
        val seenTypes = mutableSetOf<ActivityType>()
        val seenCurrencies = mutableSetOf<Currency>()
        var entries = 0
        val ctx =
            checkAll(1_000, ActivityStreams.stream(maxLength = 20, keyPrefix = "AC8")) { stream ->
                stream.forEach { a ->
                    seenTypes += a.activityType
                    seenCurrencies += a.currency
                    val lines = Posting.linesFor(a)
                    entries += 1
                    withClue("$a -> $lines") {
                        lines.size shouldBeGreaterThanOrEqual 2
                        lines.forEach { it.amount shouldBeGreaterThan 0L }
                        LineMath.imbalances(lines).shouldBeEmpty()
                        lines.map { it.account } shouldContain a.accountId
                        // Only the units the activity names may appear (for example no CAD line on a USD buy).
                        val allowed =
                            setOfNotNull(a.currency.name, a.counterCurrency?.name, a.securityId?.let(::securityUnit))
                        lines.filter { it.unit !in allowed }.shouldBeEmpty()
                    }
                }
                // The whole stream also balances, as a sum of balanced entries.
                LineMath.imbalances(stream.flatMap(Posting::linesFor)).shouldBeEmpty()
            }
        println("L-03 AC8 / invariant 1: ran ${ctx.attempts()} generated cases, $entries entries")
        ctx.attempts() shouldBeGreaterThanOrEqual 1_000
        seenTypes shouldBe ActivityType.entries.toSet()
        seenCurrencies shouldBe Currency.entries.toSet()
    }

    test("Invariant 1 (L-03 AC8): every entry stored in Postgres balances per unit, checked in SQL (100 streams)")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val ctx =
                checkAll(100, ActivityStreams.stream(maxLength = 10, keyPrefix = "AC8DB")) { stream ->
                    stream.forEach {
                        repo.post(it)
                    }
                }
            println("L-03 AC8 / invariant 1 (stored): ran ${ctx.attempts()} generated cases")
            db.query(
                db.owner,
                "SELECT entry_id, unit FROM ledger.line GROUP BY entry_id, unit " +
                    "HAVING sum(CASE side WHEN 'DEBIT' THEN amount WHEN 'CREDIT' THEN -amount END) <> 0",
            ).shouldBeEmpty()
            repo.entries().forEach { LineMath.imbalances(it.lines).shouldBeEmpty() }
        }

    test(
        "Invariant 1 (L-03 AC5 generalised): an entry unbalanced in one unit is refused, naming it (200 cases)",
    )
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val units = listOf("CAD", "USD", securityUnit("SYN-AAA"))
            var n = 0
            val cases =
                arbitrary { rs ->
                    val r = rs.random
                    // A balanced set of 1..4 debit/credit pairs over random units, then one line nudged by +-1..1000.
                    val balanced =
                        (1..r.nextInt(1, 5)).flatMap {
                            val unit = units[r.nextInt(units.size)]
                            val amt = r.nextLong(1_001, ActivityStreams.MAX_AMOUNT_MINOR + 1)
                            listOf(
                                LedgerLine("P-ACC-${r.nextInt(3)}", unit, Side.DEBIT, amt),
                                LedgerLine("P-OTHER", unit, Side.CREDIT, amt),
                            )
                        }
                    val i = r.nextInt(balanced.size)
                    val nudge = r.nextLong(1, 1_001) * (if (r.nextBoolean()) 1 else -1)
                    val lines = balanced.mapIndexed { j, l -> if (j == i) l.copy(amount = l.amount + nudge) else l }
                    val target = balanced[i]
                    val difference = if (target.side == Side.DEBIT) nudge else -nudge
                    Triple(lines, target.unit, difference)
                }
            val ctx =
                checkAll(200, cases) { (lines, unit, difference) ->
                    n += 1
                    val before = db.rowCounts()
                    val e =
                        shouldThrow<UnbalancedEntryException> {
                            repo.append(EntryRequest("AC5P-$n", LocalDate.of(2025, 1, 7), n.toLong(), lines))
                        }
                    e.code shouldBe "UNBALANCED"
                    e.unit shouldBe unit
                    e.difference shouldBe difference
                    db.rowCounts() shouldBe before
                }
            println("L-03 AC5 property: ran ${ctx.attempts()} generated cases")
            repo.entries().shouldBeEmpty()
        }
})
