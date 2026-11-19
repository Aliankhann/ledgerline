package dev.ledgerline.property

import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.testsupport.ActivityStreams
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.property.checkAll

/**
 * Invariant 3 (Immutability), story L-03 AC9: no ledger row is ever updated or deleted; rows are only appended.
 *
 * Owner placeholder (ADR 0003): Postgres enforces this by BOTH revoked UPDATE/DELETE/TRUNCATE on role
 * `ledgerline_app` AND an append-only trigger on UPDATE/DELETE. Each step also tries a random UPDATE or DELETE as
 * the app role and as the table owner and expects both to fail. Share scale C3 (10^-6) and ordering C4 placeholders
 * apply to the generated activities. The API contract is in the KDoc of `dev.ledgerline.spec.L03LedgerSpec`.
 */
class ImmutabilityInvariantPropertyTest : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    /** Every row of [before] (as a multiset of row texts, per table) is still present, byte-identical, in [after]. */
    fun shouldOnlyHaveGrown(
        before: Map<String, List<String>>,
        after: Map<String, List<String>>,
    ) {
        before.forEach { (table, rows) ->
            val now = (after[table] ?: emptyList()).groupingBy { it }.eachCount()
            rows.groupingBy { it }.eachCount().forEach { (row, count) ->
                withClue(
                    "table ledger.$table lost or changed row $row",
                ) { (now[row] ?: 0) shouldBeGreaterThanOrEqual count }
            }
        }
        after.values.sumOf { it.size } shouldBeGreaterThan before.values.sumOf { it.size }
    }

    test("Invariant 3 (L-03 AC9): after every posting, every earlier row is present and byte-identical (200 cases)")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            var snapshot = db.snapshot()
            var postings = 0
            val ctx =
                checkAll(200, ActivityStreams.stream(maxLength = 5, keyPrefix = "AC9")) { stream ->
                    stream.forEach { a ->
                        repo.post(a)
                        postings += 1
                        val ids =
                            db.query(
                                db.owner,
                                "SELECT line_id, entry_id FROM ledger.line ORDER BY random() LIMIT 1",
                            ).single()
                        val (lineId, entryId) = ids[0] to ids[1]
                        val attacks =
                            listOf(
                                "UPDATE ledger.line SET amount = amount + 1 WHERE line_id = $lineId",
                                "DELETE FROM ledger.line WHERE line_id = $lineId",
                                "DELETE FROM ledger.entry WHERE entry_id = $entryId",
                            )
                        val attack = attacks[postings % attacks.size]
                        withClue(
                            "as ledgerline_app: $attack",
                        ) { db.failureOf(db.app, attack).shouldNotBeNull().sqlState shouldBe "42501" }
                        withClue("as table owner: $attack") {
                            db.ownerError(attack) shouldContainIgnoringCase "append-only"
                        }
                        val next = db.snapshot()
                        shouldOnlyHaveGrown(snapshot, next)
                        snapshot = next
                    }
                }
            println("L-03 AC9 / invariant 3: ran ${ctx.attempts()} generated cases, $postings postings")
            ctx.attempts() shouldBeGreaterThanOrEqual 200
        }
})
