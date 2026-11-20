package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DriftKind
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.calc.ReplayHash
import dev.ledgerline.core.ActivityType.BUY
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L06Activities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.LocalDate

/** L-10 phase B attacks on the pure reconciler. Oracle: equality and the hash format in L10ReconciliationSpec. */
class L10ReconciliationAttackSpec : FunSpec({
    val engine = AcbEngine(CaIta2026v1)
    val reconciler = Reconciler(engine)
    val day = LocalDate.of(2025, 3, 1)

    fun trade(
        id: String,
        off: Long,
        sec: String,
        qty: Long,
        gross: Long,
        seq: Long,
        tp: String,
    ) = L06Activities.trade(id, BUY, day.plusDays(off), sec, qty * SHARE, gross, 0, seq, taxpayerId = tp)

    fun history(bump: Int = -1): List<PostedActivity> =
        listOf(
            trade("A-1", 0, "SYN-AAA", 60, 600_000 + if (bump == 0) 1L else 0L, 1, "TP-0001"),
            trade("A-2", 1, "SYN-BBB", 10, 250_000 + if (bump == 1) 1L else 0L, 2, "TP-0001"),
            trade("A-3", 2, "SYN-AAA", 5, 55_000 + if (bump == 2) 1L else 0L, 3, "TP-0002"),
        )
    val base = engine.replay(history()).pools

    test("L-10 attack: hash ignores map iteration order") {
        val forward = LinkedHashMap(base)
        val backward =
            LinkedHashMap<PoolKey, AcbPool>().also {
                    m ->
                base.entries.reversed().forEach { m[it.key] = it.value }
            }
        ReplayHash.of(forward) shouldBe ReplayHash.of(backward)
        reconciler.reconcile(backward, history()).ok shouldBe true
    }

    test("L-10 attack: an extra live pool with zero shares is reported as EXTRA_IN_LIVE and changes the hash") {
        val extra = PoolKey("TP-0009", "SYN-ZZZ")
        val live = base + (extra to AcbPool.empty("TP-0009", "SYN-ZZZ"))
        val report = reconciler.reconcile(live, history())
        report.ok shouldBe false
        report.drift.map { it.key to it.kind } shouldBe listOf(extra to DriftKind.EXTRA_IN_LIVE)
        report.liveHash shouldNotBe report.replayHash
    }

    test("L-10 attack: extreme Long values are compared and hashed exactly, with no overflow or sign loss") {
        val key = base.keys.first()
        val p = base.getValue(key)
        val big = base + (key to p.copy(shares = Long.MAX_VALUE, totalAcb = Long.MAX_VALUE))
        val report = reconciler.reconcile(big, history())
        report.drift.map { it.field } shouldBe listOf("shares", "totalAcb")
        report.drift.map { it.live } shouldBe listOf(Long.MAX_VALUE, Long.MAX_VALUE)
        val near = base + (key to p.copy(shares = Long.MAX_VALUE - 1, totalAcb = Long.MAX_VALUE))
        ReplayHash.of(big) shouldNotBe ReplayHash.of(near)
        ReplayHash.of(base + (key to p.copy(shares = 0, totalAcb = 0))).length shouldBe 64
    }

    test("L-10 attack: an ACB difference of one internal unit (10^-4 cent) is detected") {
        val key = base.keys.first()
        val p = base.getValue(key)
        val off = base + (key to p.copy(totalAcb = p.totalAcb + 1))
        val report = reconciler.reconcile(off, history())
        report.ok shouldBe false
        report.drift.single().field shouldBe "totalAcb"
        report.drift.single().live!! - report.drift.single().replay!! shouldBe 1L
        report.liveHash shouldNotBe report.replayHash
    }

    test(
        "L-10 attack: changing any single history row changes the replay hash and is drift against the old live pools",
    ) {
        val before = reconciler.reconcile(base, history()).replayHash
        for (row in 0..2) {
            val report = reconciler.reconcile(base, history(bump = row))
            report.replayHash shouldNotBe before
            report.ok shouldBe false
        }
    }
})
