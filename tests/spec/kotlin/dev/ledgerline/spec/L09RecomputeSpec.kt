package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.Correction
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolRecomputer
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.RejectionCode
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L09F1
import dev.ledgerline.testsupport.L09F1.AAA
import dev.ledgerline.testsupport.L09F1.BBB
import dev.ledgerline.testsupport.L09F1.TP1
import dev.ledgerline.testsupport.L09F1.TP2
import dev.ledgerline.testsupport.L09F1.d
import dev.ledgerline.testsupport.L09F1.id
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Story L-09 (docs/stories/L-09-late-correction-recompute.md), pure: no database, rule set `CA-ITA-2026.1`.
 * No tax value is asserted: the oracle is equality with a from-scratch `AcbEngine.replay` of the corrected activity
 * set (story notes) plus share counts.
 *
 * API contract (package `dev.ledgerline.calc`; PROPOSED by QE, Dev may add members but not change these):
 *
 * - `class PoolRecomputer(engine: AcbEngine)` with
 *   `fun recomputeFrom(pools: Map<PoolKey, AcbPool>, history: Collection<PostedActivity>, correction: Correction): RecomputeResult`.
 *   `pools` is the live state after replaying `history`; `history` is every posted activity before the correction,
 *   including the originals the correction reverses. The function is pure and must not need arrival order.
 * - `data class Correction(val reversedActivityIds: Set<String>, val added: List<PostedActivity>)`: activities
 *   removed from the effective set (a reversed original, C19: replay skips reversed pairs) and activities added
 *   (a replacement, or a late new activity). A correction is a reversal plus a replacement; a late activity has an
 *   empty reversal set.
 * - `data class RecomputeResult(changedPools: Map<PoolKey, AcbPool>, unchangedPools: Map<PoolKey, AcbPool>,
 *   fromDate: LocalDate?, rejections: List<Rejection>, outputHash: String)`:
 *   - Only pools of (taxpayer, security) pairs touched by the correction are recomputed, from the earliest touched
 *     tax date (`fromDate`) forward; rows of a recomputed pool dated before `fromDate` are kept as they were.
 *   - `changedPools` plus `unchangedPools` is every pool after the correction and equals `engine.replay(effective set).pools`;
 *     the two key sets are disjoint. A pool touched by the correction that ends up different is in `changedPools`.
 *   - `unchangedPools` are returned exactly as passed in (same data, same history).
 *   - `rejections` are those of a full replay of the corrected set (for example a later sell that now exceeds the
 *     shares held: `INSUFFICIENT_SHARES`, the engine's code behind the funnel's `NEGATIVE_SHARES`).
 *   - `outputHash` equals `engine.replay(effective set).outputHash`.
 *   - Redelivery: if every id in `reversedActivityIds` is already absent from `history` and every `added` id is already
 *     present, nothing is recomputed: `changedPools` is empty, `fromDate` is null and `unchangedPools` is all of `pools`.
 *
 * Not covered here (need the database and consumer, owner decisions pending): the funnel refusal of AC6 (no rows
 * written), the `ledgerline_pool_recompute_total` counter of AC2 and AC7, and the AC8 seed-42 integration run.
 */
class L09RecomputeSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val recomputer = PoolRecomputer(engine)
    val aaa1 = PoolKey(TP1, AAA)
    val bbb1 = PoolKey(TP1, BBB)
    val aaa2 = PoolKey(TP2, AAA)

    fun buyKey(key: String) = id(key)

    fun effective(
        history: List<PostedActivity>,
        c: Correction,
    ): List<PostedActivity> = history.filterNot { it.activityId in c.reversedActivityIds } + c.added

    fun correct0002(
        shares: Long,
        amount: Long,
    ) = Correction(setOf(buyKey("TP-0001-2025-0002")), listOf(L09F1.replacement(shares, amount)))

    test("L-09 AC1: a back-dated correction gives the same pool as a clean replay") {
        val history = L09F1.f1()
        val before = engine.replay(history).pools
        val c = correct0002(110, 331_375)
        val r = recomputer.recomputeFrom(before, history, c)
        val clean = engine.replay(effective(history, c))
        val pool = r.changedPools.getValue(aaa1)
        pool.shares shouldBe 70L * SHARE
        pool shouldBe clean.pools.getValue(aaa1)
        r.fromDate shouldBe d(3, 10)
        r.outputHash shouldBe clean.outputHash
        (r.changedPools + r.unchangedPools) shouldBe clean.pools
    }

    test("L-09 AC2: unaffected pools are not touched") {
        val history = L09F1.f1()
        val before = engine.replay(history).pools
        val r = recomputer.recomputeFrom(before, history, correct0002(110, 331_375))
        r.changedPools.keys shouldBe setOf(aaa1)
        r.unchangedPools.keys shouldBe setOf(bbb1, aaa2)
        r.unchangedPools.getValue(bbb1) shouldBe before.getValue(bbb1)
        r.unchangedPools.getValue(aaa2) shouldBe before.getValue(aaa2)
        r.changedPools.keys.intersect(r.unchangedPools.keys).shouldBeEmpty()
    }

    test("L-09 AC3: recompute starts at the earliest changed date") {
        val history = L09F1.f1() + L09F1.earlyBuy()
        val before = engine.replay(history).pools
        val c = correct0002(110, 331_375)
        val r = recomputer.recomputeFrom(before, history, c)
        val clean = engine.replay(effective(history, c)).pools.getValue(aaa1)
        val old = before.getValue(aaa1)
        r.fromDate shouldBe d(3, 10)
        val after = r.changedPools.getValue(aaa1)
        after.history.first().taxDate shouldBe d(2, 3)
        after.history.first() shouldBe old.history.first()
        after.history.filter { it.taxDate >= d(3, 10) } shouldBe clean.history.filter { it.taxDate >= d(3, 10) }
        after shouldBe clean
    }

    test("L-09 AC4: a late new activity, not a correction, also recomputes") {
        val history = L09F1.f1()
        val before = engine.replay(history).pools
        val late = L09F1.trade("TP-0001-2025-0050", ActivityType.BUY, d(5, 1), AAA, 5, 15_500, entryId = 101, seq = 50)
        val c = Correction(emptySet(), listOf(late))
        val r = recomputer.recomputeFrom(before, history, c)
        val clean = engine.replay(effective(history, c))
        val after = r.changedPools.getValue(aaa1)
        after.shares shouldBe 65L * SHARE
        after shouldBe clean.pools.getValue(aaa1)
        r.fromDate shouldBe d(5, 1)
        val kept = before.getValue(aaa1).history.filter { it.taxDate < d(5, 1) }
        after.history.filter { it.taxDate < d(5, 1) } shouldBe kept
        r.outputHash shouldBe clean.outputHash
    }

    test("L-09 AC5: a correction dated before every existing activity recomputes the whole pool") {
        val history = L09F1.f1()
        val before = engine.replay(history).pools
        val first =
            L09F1.trade(
                "TP-0001-2025-0060",
                ActivityType.BUY,
                d(1, 2),
                AAA,
                10,
                29_000,
                entryId = 102,
                seq = 60,
            )
        val c = Correction(emptySet(), listOf(first))
        val r = recomputer.recomputeFrom(before, history, c)
        val clean = engine.replay(effective(history, c))
        val after = r.changedPools.getValue(aaa1)
        r.fromDate shouldBe d(1, 2)
        after shouldBe clean.pools.getValue(aaa1)
        after.shares shouldBe 70L * SHARE
        after.history shouldHaveSize before.getValue(aaa1).history.size + 1
        r.outputHash shouldBe clean.outputHash
    }

    test("L-09 AC6: a correction that makes a later sell exceed the shares held reports that sell") {
        val history = L09F1.f1()
        val before = engine.replay(history).pools
        val r = recomputer.recomputeFrom(before, history, correct0002(30, 90_375))
        val rejection = r.rejections.single()
        rejection.idempotencyKey shouldBe "TP-0001-2025-0004"
        rejection.code shouldBe RejectionCode.INSUFFICIENT_SHARES
        rejection.held shouldBe 30L * SHARE
        rejection.requested shouldBe 60L * SHARE
    }

    test("L-09 AC7: redelivery of the correction changes nothing") {
        val history = L09F1.f1()
        val c = correct0002(110, 331_375)
        val first = recomputer.recomputeFrom(engine.replay(history).pools, history, c)
        val settledPools = first.changedPools + first.unchangedPools
        val again = recomputer.recomputeFrom(settledPools, effective(history, c), c)
        again.changedPools.shouldBeEmpty()
        again.fromDate.shouldBeNull()
        again.unchangedPools shouldBe settledPools
        again.outputHash shouldBe first.outputHash
    }
})
