package dev.ledgerline.spec

import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.FixedPoint
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ledger.EntryRequest
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side
import dev.ledgerline.ledger.ZeroAmountException
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.Activities.activity
import dev.ledgerline.testsupport.CsvRows
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * L-03 phase B attack tests, round 1: arithmetic near the Long limits (invariant 8: fail loudly, never wrap), the
 * C4 placeholder order (effective_date, source_seq, idempotency_key) at the storage level, and the L-01 to L-03
 * hand-off for zero amounts. No production API orders by C4 yet (calculation is L-06+), so ordering is checked
 * on the stored columns with SQL and against a test-side comparator.
 */
class L03ArithmeticOrderingEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }
    val max = Long.MAX_VALUE
    val date = LocalDate.of(2025, 1, 7)

    fun line(
        account: String,
        side: Side,
        amount: Long,
        unit: String = "CAD",
    ) = LedgerLine(account, unit, side, amount)

    fun outcome(lines: List<LedgerLine>): String =
        try {
            Posting.requireBalanced(lines)
            "accepted"
        } catch (e: Exception) {
            e::class.simpleName ?: "exception"
        }

    // ---------- Arithmetic ----------

    test("L-03 edge: debits MAX + MAX + 2 with no credits wrap to 0 in 64 bits, and are still refused") {
        val lines = listOf(line("A", Side.DEBIT, max), line("B", Side.DEBIT, max), line("C", Side.DEBIT, 2))
        // The wrapped two's-complement sum really is zero, so only exact arithmetic catches this.
        (max + max + 2) shouldBe 0L
        shouldThrowAny { Posting.requireBalanced(lines) }
    }

    test("L-03 edge (B-005): whether an entry is accepted does not depend on the order of its lines") {
        val a = line("A", Side.DEBIT, max)
        val b = line("B", Side.DEBIT, 1)
        val c = line("C", Side.CREDIT, max)
        val d = line("D", Side.CREDIT, 1)
        val orders = listOf(listOf(a, c, b, d), listOf(a, b, c, d), listOf(c, d, a, b), listOf(b, d, a, c))
        val outcomes = orders.associate { o -> o.joinToString { "${it.side.name.first()}${it.amount}" } to outcome(o) }
        withClue("outcome per line order: $outcomes") { outcomes.values.toSet().size shouldBe 1 }
    }

    test(
        "L-03 edge: a BUY whose amount plus commission exceeds Long.MAX fails loudly instead of posting a wrapped line",
    ) {
        val buy =
            activity(
                "EDGE-BUY-MAX",
                ActivityType.BUY,
                date,
                amountMinor = max,
                securityId = "SYN-AAA",
                quantity = SHARE,
            )
        assertSoftly {
            listOf(1L, 2L, max).forEach { commission ->
                withClue(
                    "commission $commission",
                ) { shouldThrowAny { Posting.linesFor(buy.copy(commissionMinor = commission)) } }
            }
            val edge = Posting.linesFor(buy.copy(amountMinor = max - 1, commissionMinor = 1))
            LineMath.imbalances(edge).shouldBeEmpty()
            LineMath.delta(edge, "TP-0001-NR1", "CAD") shouldBe -max
        }
    }

    test("L-03 edge: Long.MAX amounts and quantities post exact, balanced lines for every type that allows them") {
        val big =
            listOf(
                activity("E1", ActivityType.DEPOSIT, date, amountMinor = max),
                activity("E2", ActivityType.WITHDRAWAL, date, amountMinor = max),
                activity(
                    "E3",
                    ActivityType.SELL,
                    date,
                    amountMinor = max,
                    securityId = "S",
                    quantity = max,
                    commissionMinor = max,
                ),
                activity("E4", ActivityType.TRANSFER_IN, date, amountMinor = max, securityId = "S", quantity = max),
                activity(
                    "E5",
                    ActivityType.FX_CONVERSION,
                    date,
                    amountMinor = max,
                    counterCurrency = Currency.USD,
                    counterAmountMinor = max,
                ),
            )
        assertSoftly {
            big.forEach { a ->
                withClue(a.idempotencyKey) {
                    val lines = shouldNotThrowAny { Posting.linesFor(a) }
                    LineMath.imbalances(lines).shouldBeEmpty()
                    lines.forEach { it.amount shouldBe max }
                }
            }
        }
    }

    test("L-03 edge: Long.MAX line amounts round-trip through Postgres bigint unchanged")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val lines = listOf(line("A", Side.DEBIT, max), line("B", Side.CREDIT, max))
            repo.append(EntryRequest("EDGE-MAX", date, max, lines))
            val stored = repo.entries().single()
            stored.lines shouldContainExactly lines
            stored.sourceSeq shouldBe max
        }

    test("L-03 edge (B-007): FixedPoint.format of Long.MIN_VALUE gives the exact value or fails, never garbage") {
        val text = runCatching { FixedPoint.format(Long.MIN_VALUE, 2, minimal = false) }
        text.getOrNull()?.let { it shouldBe "-92233720368547758.08" }
        FixedPoint.format(Long.MIN_VALUE + 1, 2, minimal = false) shouldBe "-92233720368547758.07"
        FixedPoint.format(max, 6, minimal = false) shouldBe "9223372036854.775807"
    }

    // ---------- L-01 hand-off ----------

    // DISABLED pending owner decision C15 (docs/owner-inputs.md), bug B-006 (Open). This encodes the "posting
    // succeeds" option; the placeholder refuses with ZERO_AMOUNT (next test). Re-enable or delete once C15 is decided.
    test("L-03 edge (B-006): every zero-amount cash activity that L-01 accepts can be posted to the ledger")
        .config(enabled = false, tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val types = listOf("DEPOSIT", "WITHDRAWAL", "FEE", "DIVIDEND", "RETURN_OF_CAPITAL")
            val rows =
                types.mapIndexed { i, t ->
                    val security = if (t in setOf("DIVIDEND", "RETURN_OF_CAPITAL")) "SYN-AAA" else ""
                    CsvRows.row(
                        "idempotency_key" to "EDGE-ZERO-$i",
                        "activity_type" to t,
                        "security_id" to security,
                        "quantity" to "",
                        "amount" to "0.00",
                    )
                }
            val accepted: List<Activity> = CanonicalCsv.parse(CsvRows.file(*rows.toTypedArray())).activities
            println("L-03 edge zero amounts: L-01 accepted ${accepted.map { it.activityType }} of $types")
            assertSoftly {
                accepted.forEach { a ->
                    withClue("${a.activityType} amount 0 accepted by L-01") { shouldNotThrowAny { repo.post(a) } }
                }
            }
        }

    // Placeholder behaviour (owner decision C15 pending, B-006): refused at posting with ZERO_AMOUNT, nothing written.
    test("L-03 edge (B-006, C15 placeholder): a zero-amount cash activity is refused with ZERO_AMOUNT and not stored")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val types = listOf("DEPOSIT", "WITHDRAWAL", "FEE", "DIVIDEND", "RETURN_OF_CAPITAL")
            val rows =
                types.mapIndexed { i, t ->
                    CsvRows.row(
                        "idempotency_key" to "EDGE-ZERO-P-$i",
                        "activity_type" to t,
                        "security_id" to if (t in setOf("DIVIDEND", "RETURN_OF_CAPITAL")) "SYN-AAA" else "",
                        "quantity" to "",
                        "amount" to "0.00",
                    )
                }
            val accepted: List<Activity> = CanonicalCsv.parse(CsvRows.file(*rows.toTypedArray())).activities
            assertSoftly {
                accepted.size shouldBe types.size
                accepted.forEach { a ->
                    withClue("${a.activityType} amount 0") {
                        shouldThrow<ZeroAmountException> { repo.post(a) }.code shouldBe "ZERO_AMOUNT"
                    }
                }
                repo.entries().size shouldBe 0
            }
        }

    // ---------- Ordering (C4 placeholder) ----------

    val canonical = compareBy<Activity>({ it.effectiveDate }, { it.sourceSeq }).thenBy { it.idempotencyKey }
    val sameDay = LocalDate.of(2025, 2, 5)
    // Same effective date; source_seq decides, then the key (code-unit order) breaks the tie on equal source_seq.
    val set =
        listOf(
            activity("K1", ActivityType.SELL, sameDay, 1_000, securityId = "SYN-AAA", quantity = SHARE, sourceSeq = 7),
            activity("K-2", ActivityType.BUY, sameDay, 1_000, securityId = "SYN-AAA", quantity = SHARE, sourceSeq = 3),
            activity("k1", ActivityType.FEE, sameDay, 10, sourceSeq = 3),
            activity("K.1", ActivityType.DEPOSIT, sameDay, 5_000, sourceSeq = 3),
            activity("K:1", ActivityType.DIVIDEND, sameDay, 12, securityId = "SYN-AAA", sourceSeq = 3),
            activity("A-9", ActivityType.DEPOSIT, sameDay.minusDays(1), 5_000, sourceSeq = 99),
            activity("Z-0", ActivityType.WITHDRAWAL, sameDay, 1, sourceSeq = -1),
            activity("LEAP", ActivityType.DEPOSIT, LocalDate.of(2024, 2, 29), 1, sourceSeq = Long.MIN_VALUE),
            activity("FAR", ActivityType.DEPOSIT, LocalDate.of(9999, 12, 31), 1, sourceSeq = Long.MAX_VALUE),
            activity("OLD", ActivityType.DEPOSIT, LocalDate.of(1, 1, 1), 1),
            activity("CUTOVER", ActivityType.DEPOSIT, LocalDate.of(1582, 10, 15), 1),
        )
    val expected = listOf("OLD", "CUTOVER", "LEAP", "A-9", "Z-0", "K-2", "K.1", "K:1", "k1", "K1", "FAR")

    test("L-03 edge: the C4 comparator orders same-day activities by source_seq, then key (code-unit order)") {
        set.sortedWith(canonical).map { it.idempotencyKey } shouldContainExactly expected
    }

    test("L-03 edge: stored (effective_date, source_seq, idempotency_key) gives the C4 order for any insert order")
        .config(tags = setOf(Integration)) {
            val inserts = listOf(set, set.reversed(), set.shuffled(kotlin.random.Random(3)), set.sortedWith(canonical))
            assertSoftly {
                inserts.forEachIndexed { n, order ->
                    val db = pg.freshDatabase()
                    val repo = LedgerRepository(db.app)
                    order.forEach { repo.post(it) }
                    val collation =
                        db.query(db.owner, "SELECT datcollate FROM pg_database WHERE datname = current_database()")
                    val sqlOrder =
                        db.query(
                            db.owner,
                            "SELECT idempotency_key FROM ledger.entry " +
                                "ORDER BY effective_date, source_seq, idempotency_key",
                        ).map { it[0]!! }
                    val stored = repo.entries()
                    withClue("insert order #$n ${order.map { it.idempotencyKey }}, collation $collation") {
                        sqlOrder shouldContainExactly expected
                        stored.sortedWith(compareBy({ it.effectiveDate }, { it.sourceSeq }, { it.idempotencyKey }))
                            .map { it.idempotencyKey } shouldContainExactly expected
                        stored.associate { it.idempotencyKey to (it.effectiveDate to it.sourceSeq) } shouldBe
                            set.associate { it.idempotencyKey to (it.effectiveDate to it.sourceSeq) }
                        // The ledger sequence is arrival order only; C4 order must not depend on it.
                        stored.map { it.idempotencyKey } shouldContainExactly order.map { it.idempotencyKey }
                    }
                }
            }
        }
})
