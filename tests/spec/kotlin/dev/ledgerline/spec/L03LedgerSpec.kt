package dev.ledgerline.spec

import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Validation
import dev.ledgerline.ledger.EntryRequest
import dev.ledgerline.ledger.LedgerLine
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.ledger.Side
import dev.ledgerline.ledger.UnbalancedEntryException
import dev.ledgerline.testsupport.ActivityCountPlugin
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.LineMath.securityUnit
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * Spec for story L-03 (docs/stories/L-03-balanced-immutable-ledger.md), written provisionally while the story is
 * Draft, on the owner's instruction.
 *
 * PLACEHOLDERS (OWNER-INPUT, docs/owner-inputs.md):
 * - C3: share scale 10^-6 share (`100` shares = `100000000` units).
 * - C4: calculation order is (effective_date, source_seq, idempotency_key). `sourceSeq` is a field on the activity
 *   (source row order), not arrival order. The ledger sequence (AC11) is storage order only.
 * - Immutability (ADR 0003) is enforced in Postgres by BOTH revoking UPDATE/DELETE/TRUNCATE from the app role AND a
 *   trigger that raises on UPDATE/DELETE (so the table owner is blocked too). Both are tested.
 * - Share holdings ARE ledger lines: security units are posted as their own commodity `SEC:<securityId>` (for example
 *   `SEC:SYN-AAA`) and balance per commodity exactly like currencies. Currency units are `CAD` and `USD`.
 * - The DomainPlugin contract (AC10) stays in this story.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, the owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.core
 * // Activity gains ONE new LAST constructor parameter, defaulted so L-01 code keeps compiling:
 * //     val sourceSeq: Long = 0L          // C4: position in the source; not arrival order
 * sealed interface Validation {
 *     data object Accepted : Validation
 *     data class Rejected(val reason: String) : Validation
 * }
 * interface DomainPlugin<S, E> {
 *     fun validate(activity: Activity): Validation
 *     fun apply(state: S, activity: Activity): S     // pure: no I/O, clock or randomness
 *     fun explain(state: S): E
 * }
 *
 * package dev.ledgerline.ledger
 * enum class Side { DEBIT, CREDIT }
 * data class LedgerLine(val account: String, val unit: String, val side: Side, val amount: Long) // amount > 0
 * object Posting {
 *     fun linesFor(activity: Activity): List<LedgerLine>   // pure; no database
 * }
 * data class EntryRequest(val idempotencyKey: String, val effectiveDate: java.time.LocalDate, val sourceSeq: Long,
 *                         val lines: List<LedgerLine>)
 * data class PostedEntry(val sequence: Long, val idempotencyKey: String, val effectiveDate: java.time.LocalDate,
 *                        val sourceSeq: Long, val lines: List<LedgerLine>)
 * class UnbalancedEntryException(val unit: String, val difference: Long) : RuntimeException() {
 *     val code: String = "UNBALANCED"   // difference = debits - credits in [unit]; checked before any write
 * }
 * object LedgerMigrations { fun migrate(dataSource: javax.sql.DataSource) }  // Flyway, classpath:db/migration, V2+
 * class LedgerRepository(dataSource: javax.sql.DataSource) {
 *     fun append(request: EntryRequest): PostedEntry  // direct posting API: one transaction, balance-checked
 *     fun post(activity: Activity): PostedEntry       // = append(EntryRequest(key, effectiveDate, sourceSeq, linesFor(a)))
 *     fun entries(): List<PostedEntry>                // all entries, ascending sequence
 * }
 * ```
 *
 * Line conventions: lines that belong to the investor's account carry `account == activity.accountId`; every other
 * line (counterparty, commission expense, FX clearing and so on) uses a different account name of the Dev's choosing
 * (draft ADR). An account's balance in a unit is debits minus credits. Tests assert balance per unit and the
 * investor account's movement, never other account names.
 *
 * Schema (names the tests use; other columns are the Dev's): `ledger.entry(entry_id bigint PK, idempotency_key text)`,
 * `ledger.line(line_id bigint PK, entry_id bigint FK -> ledger.entry, account text, unit text, side text
 * 'DEBIT'|'CREDIT', amount bigint)`. The migrations create role `ledgerline_app` (not superuser, owns nothing) with
 * only USAGE on schema `ledger`, SELECT and INSERT on its tables, and USAGE on its sequences. Role creation must be
 * idempotent: tests create one database per test in a single cluster. The repository must work when every
 * connection runs as `ledgerline_app` (tests use `SET ROLE ledgerline_app`). The trigger's error message contains
 * "append-only". The Flyway history table is not in schema `ledger`.
 */
class L03LedgerSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val nr1 = "TP-0001-NR1"
    val aaa = securityUnit("SYN-AAA")
    val usx = securityUnit("SYN-USX")
    val bbb = securityUnit("SYN-BBB")

    val ac1 = L03Activities.ac1
    val ac2 = L03Activities.ac2
    val ac3 = L03Activities.ac3
    val ac4 = L03Activities.ac4

    fun d(
        m: Int,
        day: Int,
    ): LocalDate = LocalDate.of(2025, m, day)

    fun shouldBeBalancedPerUnit(
        label: String,
        lines: List<LedgerLine>,
    ) = withClue("$label lines=$lines") {
        lines.size shouldBeGreaterThanOrEqual 2
        lines.forEach { it.amount shouldBeGreaterThan 0L }
        LineMath.imbalances(lines).shouldBeEmpty()
    }

    // ---------- Pure posting logic (no database) ----------

    test("L-03 AC1: CAD buy posts lines balanced in CAD and in SEC:SYN-AAA") {
        val lines = Posting.linesFor(ac1)
        shouldBeBalancedPerUnit("AC1", lines)
        lines.map { it.unit }.toSet() shouldBe setOf("CAD", aaa)
    }

    test("L-03 AC1: CAD buy decreases TP-0001-NR1 CAD by 301745 and increases its SYN-AAA holding by 100000000 units") {
        val lines = Posting.linesFor(ac1)
        assertSoftly {
            LineMath.delta(lines, nr1, "CAD") shouldBe -301_745L
            LineMath.delta(lines, nr1, aaa) shouldBe 100_000_000L
        }
    }

    test("L-03 AC2: USD buy balances in USD and in SEC:SYN-USX and has no CAD line") {
        val lines = Posting.linesFor(ac2)
        shouldBeBalancedPerUnit("AC2", lines)
        lines.map { it.unit } shouldNotContain "CAD"
        lines.map { it.unit }.toSet() shouldBe setOf("USD", usx)
        LineMath.delta(lines, nr1, usx) shouldBe 25_000_000L
    }

    test("L-03 AC3: FX conversion balances in CAD and USD separately; account CAD -138000, USD +100000") {
        val lines = Posting.linesFor(ac3)
        shouldBeBalancedPerUnit("AC3", lines)
        lines.map { it.unit }.toSet() shouldBe setOf("CAD", "USD")
        assertSoftly {
            LineMath.delta(lines, nr1, "CAD") shouldBe -138_000L
            LineMath.delta(lines, nr1, "USD") shouldBe 100_000L
        }
    }

    test("L-03 AC4: all nine activity types post lines balanced per unit") {
        ac4.map { it.activityType }.toSet() shouldBe ActivityType.entries.toSet()
        ac4.forEach { a ->
            val lines = Posting.linesFor(a)
            shouldBeBalancedPerUnit("${a.activityType} ${a.idempotencyKey}", lines)
            withClue("${a.activityType} must touch the investor account") { lines.map { it.account } shouldContain nr1 }
        }
    }

    test("L-03 AC4: share units move only for BUY, SELL and TRANSFER_IN, by the activity's quantity") {
        val expectedShares =
            mapOf(
                ActivityType.BUY to (aaa to 100_000_000L),
                ActivityType.SELL to (aaa to -40_000_000L),
                ActivityType.TRANSFER_IN to (bbb to 10_000_000L),
            )
        ac4.forEach { a ->
            val lines = Posting.linesFor(a)
            withClue("${a.activityType}") {
                for (unit in listOf(aaa, bbb)) {
                    val expected = expectedShares[a.activityType]?.takeIf { it.first == unit }?.second ?: 0L
                    LineMath.delta(lines, nr1, unit) shouldBe expected
                }
            }
        }
    }

    // ---------- DomainPlugin contract (AC10), pure ----------

    test("L-03 AC10: validate rejects an activity with accountId \"\" and gives a reason") {
        val result = ActivityCountPlugin.validate(ac1.copy(accountId = ""))
        result.shouldBeInstanceOf<Validation.Rejected>()
        result.reason.isNotBlank() shouldBe true
        ActivityCountPlugin.validate(ac1) shouldBe Validation.Accepted
    }

    test("L-03 AC10: applying AC4's nine activities in order counts 9 for TP-0001-NR1") {
        val state = ac4.fold(ActivityCountPlugin.empty(), ActivityCountPlugin::apply)
        state.countsByAccount[nr1] shouldBe 9
    }

    test("L-03 AC10: applying the nine activities to two fresh states yields equal states") {
        val first = ac4.fold(ActivityCountPlugin.empty(), ActivityCountPlugin::apply)
        val second = ac4.fold(ActivityCountPlugin.empty(), ActivityCountPlugin::apply)
        first shouldBe second
    }

    test("L-03 AC10: explain lists the nine keys in apply order") {
        val state = ac4.fold(ActivityCountPlugin.empty(), ActivityCountPlugin::apply)
        ActivityCountPlugin.explain(state) shouldContainExactly ac4.map { it.idempotencyKey }
    }

    // ---------- Database (Testcontainers postgres:16.4-alpine) ----------

    test(
        "L-03 AC1: posting the CAD buy stores exactly one balanced entry for its key",
    ).config(tags = setOf(Integration)) {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        repo.post(ac1)
        val stored = repo.entries().filter { it.idempotencyKey == ac1.idempotencyKey }
        stored shouldHaveSize 1
        LineMath.canonical(stored.single().lines) shouldBe LineMath.canonical(Posting.linesFor(ac1))
        LineMath.imbalances(stored.single().lines).shouldBeEmpty()
        val sql = "SELECT count(*) FROM ledger.entry WHERE idempotency_key = '${ac1.idempotencyKey}'"
        db.query(db.owner, sql) shouldBe listOf(listOf("1"))
    }

    test(
        "L-03 AC4: posting all nine activities stores nine entries, each balanced per unit",
    ).config(tags = setOf(Integration)) {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        ac4.forEach { repo.post(it) }
        val stored = repo.entries()
        stored.map { it.idempotencyKey } shouldContainExactly ac4.map { it.idempotencyKey }
        stored.forEach { shouldBeBalancedPerUnit(it.idempotencyKey, it.lines) }
        // The same check, computed by the database over the stored rows.
        db.query(
            db.owner,
            "SELECT entry_id, unit FROM ledger.line GROUP BY entry_id, unit " +
                "HAVING sum(CASE side WHEN 'DEBIT' THEN amount WHEN 'CREDIT' THEN -amount END) <> 0",
        ).shouldBeEmpty()
    }

    test("L-03 AC5: unbalanced lines are refused with UNBALANCED, unit CAD, difference 1, and nothing is written")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            repo.post(ac1)
            val before = db.rowCounts()
            val e =
                shouldThrow<UnbalancedEntryException> {
                    repo.append(
                        EntryRequest(
                            idempotencyKey = "L03-AC5-UNBALANCED",
                            effectiveDate = d(1, 7),
                            sourceSeq = 1,
                            lines =
                                listOf(
                                    LedgerLine(nr1, "CAD", Side.DEBIT, 301_250L),
                                    LedgerLine("L03-AC5-OTHER", "CAD", Side.CREDIT, 301_249L),
                                ),
                        ),
                    )
                }
            assertSoftly {
                e.code shouldBe "UNBALANCED"
                e.unit shouldBe "CAD"
                e.difference shouldBe 1L
                db.rowCounts() shouldBe before
                repo.entries().map { it.idempotencyKey } shouldContainExactly listOf(ac1.idempotencyKey)
            }
        }

    test("L-03 AC6: as ledgerline_app, UPDATE, DELETE and TRUNCATE of ledger rows all fail; table hash unchanged")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            val lineId = db.firstLineIdOf(ac1.idempotencyKey)
            val hashBefore = db.sha256()

            val statements =
                listOf(
                    "UPDATE ledger.line SET amount = 1 WHERE line_id = $lineId",
                    "DELETE FROM ledger.line WHERE line_id = $lineId",
                    "DELETE FROM ledger.entry WHERE idempotency_key = '${ac1.idempotencyKey}'",
                    "TRUNCATE ledger.line",
                ) +
                    // Every table in schema ledger, so tables added later stay covered. The UPDATE targets a column
                    // that is not GENERATED ALWAYS, so Postgres reaches the privilege check (else it fails 428C9).
                    db.tables().flatMap { t ->
                        val col =
                            db.query(
                                db.owner,
                                "SELECT column_name FROM information_schema.columns " +
                                    "WHERE table_schema = 'ledger' AND table_name = '$t' " +
                                    "AND is_identity = 'NO' AND is_generated = 'NEVER' " +
                                    "ORDER BY ordinal_position LIMIT 1",
                            ).firstOrNull()?.get(0)
                        listOfNotNull(
                            col?.let { "UPDATE ledger.$t SET $it = $it" },
                            "DELETE FROM ledger.$t",
                            "TRUNCATE ledger.$t",
                        )
                    }
            assertSoftly {
                statements.forEach { sql ->
                    withClue("as ledgerline_app: $sql") {
                        // 42501 = insufficient_privilege: the grant is missing, not merely a trigger firing.
                        db.failureOf(db.app, sql).shouldNotBeNull().sqlState shouldBe "42501"
                    }
                }
                db.sha256() shouldBe hashBefore
                db.query(db.owner, "SELECT rolsuper::text FROM pg_roles WHERE rolname = 'ledgerline_app'") shouldBe
                    listOf(listOf("false"))
                db.query(
                    db.owner,
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'ledger' AND tableowner = 'ledgerline_app'",
                )
                    .shouldBeEmpty()
            }
        }

    test(
        "L-03 AC6: the append-only trigger makes UPDATE and DELETE fail even for the table owner; table hash unchanged",
    )
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            val lineId = db.firstLineIdOf(ac1.idempotencyKey)
            val hashBefore = db.sha256()
            assertSoftly {
                listOf(
                    "UPDATE ledger.line SET amount = 1 WHERE line_id = $lineId",
                    "DELETE FROM ledger.line WHERE line_id = $lineId",
                    "UPDATE ledger.entry SET idempotency_key = 'X' WHERE idempotency_key = '${ac1.idempotencyKey}'",
                    "DELETE FROM ledger.entry WHERE idempotency_key = '${ac1.idempotencyKey}'",
                ).forEach { sql ->
                    withClue("as table owner: $sql") {
                        db.ownerError(sql) shouldContainIgnoringCase "append-only"
                    }
                }
                db.sha256() shouldBe hashBefore
            }
        }

    test(
        "L-03 AC7: ledger schema has no real, double or numeric column; amount and quantity columns are bigint",
    )
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val columns =
                db.query(
                    db.owner,
                    "SELECT table_name, column_name, data_type FROM information_schema.columns " +
                        "WHERE table_schema = 'ledger'",
                )
            // `money` is not named by the story but is also a non-integer type; invariant 8 bans it too.
            columns.filter { it[2] in setOf("real", "double precision", "numeric", "money") }.shouldBeEmpty()
            val amountLike = columns.filter { c -> listOf("amount", "quantity").any { c[1]!!.contains(it) } }
            amountLike.map { it[0] to it[1] } shouldContain ("line" to "amount")
            amountLike.filter { it[2] != "bigint" }.shouldBeEmpty()
        }

    test("L-03 AC11: AC1, then AC3, then AC2 get three distinct, strictly increasing sequences in commit order")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            // C4: sourceSeq deliberately disagrees with commit order; the ledger sequence must follow commit order.
            val posted =
                listOf(
                    ac1.copy(sourceSeq = 30),
                    ac3.copy(sourceSeq = 10),
                    ac2.copy(sourceSeq = 20),
                ).map { repo.post(it) }
            val seqs = posted.map { it.sequence }
            seqs.toSet() shouldHaveSize 3
            seqs.shouldBeStrictlyIncreasing()
            val stored = repo.entries()
            stored.map { it.idempotencyKey } shouldContainExactly listOf(ac1, ac3, ac2).map { it.idempotencyKey }
            stored.map { it.sequence } shouldContainExactly seqs
            stored.map { it.sourceSeq } shouldContainExactly listOf(30L, 10L, 20L)
        }
})
