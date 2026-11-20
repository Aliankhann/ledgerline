package dev.ledgerline.property

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
import dev.ledgerline.testsupport.L09F1.d
import dev.ledgerline.testsupport.L09F1.id
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** L-09 phase B attacks on `PoolRecomputer`. Oracle: equality with a from-scratch replay of the corrected set. */
class L09RecomputeAttackTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val recomputer = PoolRecomputer(engine)
    val aaa1 = PoolKey(TP1, AAA)

    fun effective(
        history: List<PostedActivity>,
        c: Correction,
    ) = history.filterNot { it.activityId in c.reversedActivityIds } + c.added

    fun check(
        history: List<PostedActivity>,
        c: Correction,
    ) = recomputer.recomputeFrom(engine.replay(history).pools, history, c).also { r ->
        val clean = engine.replay(effective(history, c))
        (r.changedPools + r.unchangedPools) shouldBe clean.pools
        r.outputHash shouldBe clean.outputHash
        r.rejections shouldBe clean.rejections
    }

    test("L-09 attack: correction dated before the pool's first activity") {
        val history = L09F1.f1()
        val early = L09F1.trade("TP-0001-2025-0100", ActivityType.BUY, d(1, 2), AAA, 5, 15_000, 101, seq = 100)
        val r = check(history, Correction(emptySet(), listOf(early)))
        r.fromDate shouldBe d(1, 2)
        r.changedPools.getValue(aaa1).shares shouldBe 65L * SHARE
    }

    test("L-09 attack: correction touching two securities") {
        val history = L09F1.f1()
        val a = L09F1.trade("TP-0001-2025-0002-C1", ActivityType.BUY, d(3, 10), AAA, 110, 331_375, 100, seq = 2)
        val b = L09F1.trade("TP-0001-2025-0003-C1", ActivityType.BUY, d(4, 14), BBB, 55, 247_500, 101, seq = 3)
        val c = Correction(setOf(id("TP-0001-2025-0002"), id("TP-0001-2025-0003")), listOf(a, b))
        val r = check(history, c)
        r.changedPools.keys shouldBe setOf(aaa1, PoolKey(TP1, BBB))
        r.fromDate shouldBe d(3, 10)
    }

    test("L-09 attack: correction taking shares negative at an intermediate date is rejected, not clipped") {
        val history = L09F1.f1()
        val small = L09F1.trade("TP-0001-2025-0002-C1", ActivityType.BUY, d(3, 10), AAA, 50, 150_000, 100, seq = 2)
        val r = check(history, Correction(setOf(id("TP-0001-2025-0002")), listOf(small)))
        r.rejections shouldHaveSize 1
        r.rejections.single().code shouldBe RejectionCode.INSUFFICIENT_SHARES
        val pool = (r.changedPools + r.unchangedPools).getValue(aaa1)
        pool.shares shouldBe (50L + 20L) * SHARE
        pool.history.all { it.sharesDelta != -60L * SHARE } shouldBe true
    }

    test("L-09 attack: correction reversing an already reversed entry") {
        val history = L09F1.f1()
        val c1 = Correction(setOf(id("TP-0001-2025-0002")), listOf(L09F1.replacement(110, 331_375)))
        val after = effective(history, c1)
        val again = L09F1.trade("TP-0001-2025-0002-C2", ActivityType.BUY, d(3, 10), AAA, 120, 361_500, 101, seq = 2)
        check(after, Correction(setOf(id("TP-0001-2025-0002")), listOf(again)))
        val noop = recomputer.recomputeFrom(engine.replay(after).pools, after, c1)
        noop.changedPools shouldBe emptyMap()
        noop.fromDate shouldBe null
    }

    test("L-09 attack: the same correction applied twice gives the same hash") {
        val history = L09F1.f1()
        val c = Correction(setOf(id("TP-0001-2025-0002")), listOf(L09F1.replacement(110, 331_375)))
        val pools = engine.replay(history).pools
        val first = recomputer.recomputeFrom(pools, history, c)
        recomputer.recomputeFrom(pools, history, c).outputHash shouldBe first.outputHash
        val after = effective(history, c)
        val second = recomputer.recomputeFrom(first.changedPools + first.unchangedPools, after, c)
        second.outputHash shouldBe first.outputHash
        (second.changedPools + second.unchangedPools) shouldBe (first.changedPools + first.unchangedPools)
    }
})
