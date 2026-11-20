package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DriftKind
import dev.ledgerline.calc.PoolDiff
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.calc.ReplayHash
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.checkAll
import kotlin.random.Random

/**
 * Invariants 5 and 6 for reconciliation (story L-10). API contract: KDoc of `dev.ledgerline.spec.L10ReconciliationSpec`.
 * Oracle: equality only; no tax value is computed here.
 */
class L10ReconciliationPropertyTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val reconciler = Reconciler(engine)

    test("Invariant 5 (L-10): reconcile(replay(h), h) has zero drift and equal hashes (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 30, big = true)) { stream ->
            val live = engine.replay(stream).pools
            val report = reconciler.reconcile(live, stream)
            report.drift.shouldBeEmpty()
            report.ok shouldBe true
            report.liveHash shouldBe report.replayHash
            report.replayHash shouldBe ReplayHash.of(live)
        }
    }

    test("Invariant 6 (L-10): the report does not depend on the arrival order of the history (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 30)) { stream ->
            val live = engine.replay(stream).pools
            val base = reconciler.reconcile(live, stream)
            reconciler.reconcile(live, stream.shuffled(Random(stream.size))) shouldBe base
        }
    }

    test("L-10: mutating one field of one live pool gives exactly one PoolDiff for that key (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 30)) { stream ->
            val live = engine.replay(stream).pools
            if (live.isNotEmpty()) {
                val r = Random(stream.size * 17)
                val key = live.keys.toList()[r.nextInt(live.size)]
                val pool = live.getValue(key)
                val onShares = r.nextBoolean()
                val delta = r.nextLong(1, 1_000_000)
                val mutated =
                    if (onShares) {
                        pool.copy(
                            shares = pool.shares + delta,
                        )
                    } else {
                        pool.copy(totalAcb = pool.totalAcb + delta)
                    }
                val report = reconciler.reconcile(live + (key to mutated), stream)
                val expected =
                    if (onShares) {
                        PoolDiff(key, DriftKind.FIELD_MISMATCH, "shares", pool.shares + delta, pool.shares)
                    } else {
                        PoolDiff(key, DriftKind.FIELD_MISMATCH, "totalAcb", pool.totalAcb + delta, pool.totalAcb)
                    }
                report.drift shouldBe listOf(expected)
                report.ok shouldBe false
                report.liveHash shouldNotBe report.replayHash
            }
        }
    }

    test("L-10: removing one live pool gives exactly one MISSING_IN_LIVE for that key (300 cases)") {
        checkAll(300, L06Streams.stream(maxLength = 30)) { stream ->
            val live = engine.replay(stream).pools
            if (live.isNotEmpty()) {
                val key = live.keys.toList()[Random(stream.size).nextInt(live.size)]
                reconciler.reconcile(live - key, stream).drift shouldBe
                    listOf(PoolDiff(key, DriftKind.MISSING_IN_LIVE, null, null, null))
            }
        }
    }
})
