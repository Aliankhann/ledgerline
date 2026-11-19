package dev.ledgerline.spec

import dev.ledgerline.core.Activity
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.PostedEntry
import dev.ledgerline.testsupport.Balances
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.LedgerDb
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RawLedger
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.MessageDigest
import kotlin.random.Random

/**
 * L-04 phase B attack tests, round 1: the database-side guarantees of V4 (privileges, no dangling links, no chains)
 * attacked directly as the app role, and replay determinism after reversals. All need Docker.
 */
class L04ReversalDatabaseEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val nr1 = "TP-0001-NR1"
    val aaa = LineMath.securityUnit("SYN-AAA")
    val buy = L03Activities.ac1

    fun withReversal(): Triple<LedgerDb, LedgerRepository, Pair<PostedEntry, PostedEntry>> {
        val db = pg.freshDatabase()
        val repo = LedgerRepository(db.app)
        repo.post(L03Activities.ac4[0])
        val original = repo.post(buy)
        return Triple(db, repo, original to repo.reverse(original.sequence, "REV-X", "x"))
    }

    fun LedgerDb.danglingLinks() =
        query(
            owner,
            "SELECT r.reversal_entry_id, r.original_entry_id FROM ledger.reversal r " +
                "WHERE NOT EXISTS (SELECT 1 FROM ledger.entry e WHERE e.entry_id = r.reversal_entry_id) " +
                "OR NOT EXISTS (SELECT 1 FROM ledger.entry e WHERE e.entry_id = r.original_entry_id)",
        )

    // ---------- Privileges ----------

    test(
        "L-04 edge: the app role cannot UPDATE, DELETE or TRUNCATE ledger.reversal (42501), and the table is unchanged",
    )
        .config(tags = setOf(Integration)) {
            val (db, _, _) = withReversal()
            val before = db.sha256()
            val statements =
                listOf(
                    "UPDATE ledger.reversal SET reason = 'edited'",
                    "UPDATE ledger.reversal SET original_entry_id = 1",
                    "DELETE FROM ledger.reversal",
                    "TRUNCATE ledger.reversal",
                )
            assertSoftly {
                statements.forEach { sql ->
                    withClue(sql) { db.failureOf(db.app, sql)?.sqlState shouldBe "42501" }
                }
                db.sha256() shouldBe before
                db.query(
                    db.owner,
                    "SELECT has_table_privilege('ledgerline_app', 'ledger.reversal', 'UPDATE'), " +
                        "has_table_privilege('ledgerline_app', 'ledger.reversal', 'DELETE'), " +
                        "has_table_privilege('ledgerline_app', 'ledger.reversal', 'TRUNCATE')",
                ) shouldBe listOf(listOf("f", "f", "f"))
            }
        }

    test("L-04 edge: even the table owner cannot UPDATE, DELETE or TRUNCATE ledger.reversal (append-only trigger)")
        .config(tags = setOf(Integration)) {
            val (db, _, _) = withReversal()
            val before = db.sha256()
            assertSoftly {
                listOf(
                    "UPDATE ledger.reversal SET reason = 'edited'",
                    "DELETE FROM ledger.reversal",
                    "TRUNCATE ledger.reversal",
                    "TRUNCATE ledger.reversal, ledger.entry, ledger.line",
                ).forEach { sql -> withClue(sql) { db.ownerError(sql) shouldContain "append-only" } }
                db.sha256() shouldBe before
            }
        }

    // ---------- Links never dangle ----------

    test("L-04 edge: through the app role, a reversal row can never point at a missing or unmirrored entry")
        .config(tags = setOf(Integration)) {
            val (db, repo, pair) = withReversal()
            val (original, _) = pair
            val before = db.sha256()
            val mirror = RawLedger.mirrorLines(original)
            val attacks: Map<String, (java.sql.Connection) -> Unit> =
                mapOf(
                    "original id does not exist" to { c ->
                        val r = RawLedger.entry(c, "RAW-1")
                        RawLedger.lines(c, r, mirror)
                        RawLedger.link(c, r, 999_999L)
                    },
                    "reversal id does not exist" to { c -> RawLedger.link(c, 999_999L, original.sequence) },
                    "both ids missing" to { c -> RawLedger.link(c, 999_999L, 999_998L) },
                    "reversal entry is an old committed entry" to { c ->
                        RawLedger.link(c, repo.entries().first().sequence + 1, original.sequence - 1)
                    },
                    "entry reverses itself" to { c ->
                        val r = RawLedger.entry(c, "RAW-2")
                        RawLedger.lines(c, r, mirror)
                        RawLedger.link(c, r, r)
                    },
                    "second reversal of an already reversed entry" to { c ->
                        val r = RawLedger.entry(c, "RAW-3")
                        RawLedger.lines(c, r, mirror)
                        RawLedger.link(c, r, original.sequence)
                    },
                    "lines do not mirror the original (caught at commit)" to { c ->
                        val r = RawLedger.entry(c, "RAW-4")
                        RawLedger.lines(c, r, mirror)
                        RawLedger.link(c, r, repo.entries().first().sequence)
                    },
                    "blank reason" to { c ->
                        val r = RawLedger.entry(c, "RAW-5")
                        RawLedger.lines(c, r, mirror)
                        RawLedger.link(c, r, repo.entries().first().sequence, reason = "  ")
                    },
                )
            assertSoftly {
                attacks.forEach { (name, attack) ->
                    withClue(name) { RawLedger.attempt(db.app, attack).shouldNotBeNull() }
                }
                db.sha256() shouldBe before
                db.danglingLinks() shouldBe emptyList()
            }
        }

    test("L-04 edge (C16): through the app role, a chain A <- R <- R2 cannot be built even by linking in reverse order")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val repo = LedgerRepository(db.app)
            val a = repo.post(buy)
            val mirror = RawLedger.mirrorLines(a)
            val before = db.sha256()
            // R mirrors A, R2 mirrors R (= A's lines). Link R2 -> R first (R is not yet a reversal), then R -> A.
            val failure =
                RawLedger.attempt(db.app) { c ->
                    val r = RawLedger.entry(c, "RAW-R")
                    RawLedger.lines(c, r, mirror)
                    val r2 = RawLedger.entry(c, "RAW-R2")
                    RawLedger.lines(c, r2, a.lines)
                    RawLedger.link(c, r2, r)
                    RawLedger.link(c, r, a.sequence)
                }
            withClue("a reversal of a reversal was committed (chain)") { failure.shouldNotBeNull() }
            db.sha256() shouldBe before
        }

    // ---------- Replay determinism after reversals ----------

    fun canonicalHash(entries: List<PostedEntry>): String {
        val md = MessageDigest.getInstance("SHA-256")
        entries.sortedWith(compareBy({ it.effectiveDate }, { it.sourceSeq }, { it.idempotencyKey })).forEach { e ->
            md.update("${e.idempotencyKey}|${e.effectiveDate}|${e.sourceSeq}|${e.lines}\n".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Posts [activities] in [arrival] order, then reverses those whose key is in [reversed]. */
    fun run(
        activities: List<Activity>,
        arrival: List<Activity>,
        reversed: Set<String>,
    ): List<PostedEntry> {
        val repo = LedgerRepository(pg.freshDatabase().app)
        val posted = arrival.associate { it.idempotencyKey to repo.post(it) }
        activities.filter { it.idempotencyKey in reversed }.forEach {
            repo.reverse(posted.getValue(it.idempotencyKey).sequence, "REV-${it.idempotencyKey}", "replay test")
        }
        return repo.entries()
    }

    test(
        "L-04 edge (invariants 5, 6): any arrival order gives the same canonical hash and balances after reversals",
    )
        .config(tags = setOf(Integration)) {
            val reversible = L03Activities.ac4.filter { it.activityType.name in setOf("FEE", "DIVIDEND", "WITHDRAWAL") }
            val all = L03Activities.ac4
            val reversed = reversible.map { it.idempotencyKey }.toSet()
            val reference = run(all, all, reversed)
            val refHash = canonicalHash(reference)
            val refBalances = Balances.ofEntries(reference)
            val sells = all.filter { it.activityType.name == "SELL" }
            repeat(4) { seed ->
                // Sells need their buy first (invariant 7); everything else arrives shuffled.
                val shuffled =
                    all.filterNot { it in sells }.shuffled(
                        Random(seed),
                    ).sortedBy { it.activityType.name == "BUY" }
                val entries = run(all, shuffled + sells, reversed)
                withClue("seed $seed") {
                    assertSoftly {
                        canonicalHash(entries) shouldBe refHash
                        Balances.ofEntries(entries) shouldBe refBalances
                    }
                }
            }
        }

    test("L-04 edge (invariant 7, replay): in canonical order a reversal never comes before its original")
        .config(tags = setOf(Integration)) {
            val (_, repo, pair) = withReversal()
            val (original, reversal) = pair
            // The story's own keys: original TP-0001-2025-0002, reversal REV-TP-0001-2025-0002. A replay that walks
            // (effective date, source sequence, key) and does not pair entries up must never see negative holdings.
            var held = 0L
            val lowest =
                repo.entries().sortedWith(compareBy({ it.effectiveDate }, { it.sourceSeq }, { it.idempotencyKey }))
                    .filter { it.sequence == original.sequence || it.sequence == reversal.sequence }
                    .map { e ->
                        held += LineMath.delta(e.lines, nr1, aaa)
                        held
                    }.min()
            withClue("holdings of $aaa during canonical replay of the AC1 buy and its reversal") { lowest shouldBe 0L }
        }
})
