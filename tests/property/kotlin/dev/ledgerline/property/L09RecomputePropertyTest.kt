package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.Correction
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolRecomputer
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll

/**
 * L-09 / invariants 5 and 6: for a random history plus a random back-dated correction (reverse one activity and
 * replace it, or add a late activity, or both), the incremental recompute equals a from-scratch replay of the
 * corrected set (pools and output hash), pools the correction did not touch are returned exactly as before, and the
 * changed and unchanged pools partition the result. The API contract is in the KDoc of
 * `dev.ledgerline.spec.L09RecomputeSpec`. No tax value is computed here.
 */
class L09RecomputePropertyTest : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val recomputer = PoolRecomputer(engine)

    fun key(p: PostedActivity) = PoolKey(p.activity.taxpayerId, requireNotNull(p.activity.securityId))

    data class Case(val history: List<PostedActivity>, val correction: Correction)

    val cases: Arb<Case> =
        arbitrary { rs ->
            val r = rs.random
            val history = L06Streams.stream(maxLength = 30).sample(rs).value
            val poolable =
                history.filter { !L06Streams.isRegistered(it) && it.activity.securityId != null }
            val reversed =
                if (poolable.isNotEmpty() && r.nextInt(100) < 70) {
                    poolable[
                        r.nextInt(
                            poolable.size,
                        ),
                    ]
                } else {
                    null
                }
            val added = mutableListOf<PostedActivity>()
            val template = reversed ?: poolable.firstOrNull() ?: history.first()
            if (reversed != null && r.nextBoolean()) {
                added +=
                    reversed.copy(
                        activityId = "${reversed.activityId}-C1",
                        entryId = 900_000L,
                        activity =
                            reversed.activity.copy(
                                idempotencyKey = "${reversed.activity.idempotencyKey}-C1",
                                quantity = reversed.activity.quantity?.let { it + r.nextLong(0, 5 * Activities.SHARE) },
                                amountMinor = reversed.activity.amountMinor + r.nextLong(0, 10_000L),
                            ),
                    )
            }
            if (reversed == null || r.nextBoolean()) {
                val account = L06Streams.accounts.filter { it.type == template.activity.accountType }.random(r)
                val type = if (r.nextBoolean()) ActivityType.BUY else ActivityType.SELL
                val date = history.map { it.activity.effectiveDate }.minOrNull()!!.plusDays(r.nextLong(-5, 60))
                added +=
                    PostedActivity(
                        activityId = "act-LATE-1",
                        entryId = 900_001L,
                        activity =
                            Activities.activity(
                                key = "LATE-1",
                                type = type,
                                date = date,
                                amountMinor = r.nextLong(1, 1_000_000L),
                                securityId = L06Streams.securities.random(r),
                                quantity = r.nextLong(1, 20 * Activities.SHARE),
                                commissionMinor = r.nextLong(0, 10_000L),
                                accountId = account.accountId,
                                accountType = account.type,
                                taxpayerId = account.taxpayerId,
                                sourceSeq = r.nextLong(0, 40),
                            ),
                    )
            }
            Case(history, Correction(setOfNotNull(reversed?.activityId), added))
        }

    test(
        "L-09 invariant: incremental recompute equals a from-scratch replay; untouched pools are identical (500 cases)",
    ) {
        val ctx =
            checkAll(500, cases) { (history, correction) ->
                val before = engine.replay(history).pools
                val result = recomputer.recomputeFrom(before, history, correction)
                val corrected = history.filterNot { it.activityId in correction.reversedActivityIds } + correction.added
                val clean = engine.replay(corrected)

                (result.changedPools + result.unchangedPools) shouldBe clean.pools
                result.outputHash shouldBe clean.outputHash
                result.rejections shouldBe clean.rejections
                result.changedPools.keys.intersect(result.unchangedPools.keys) shouldBe emptySet()

                val touched =
                    (history.filter { it.activityId in correction.reversedActivityIds } + correction.added)
                        .filter { !L06Streams.isRegistered(it) && it.activity.securityId != null }
                        .map(::key)
                        .toSet()
                result.changedPools.keys.all { it in touched } shouldBe true
                result.unchangedPools.forEach { (k, pool) -> if (k in before) pool shouldBe before.getValue(k) }
                before.filterKeys { it !in touched }.forEach { (k, pool) -> result.unchangedPools[k] shouldBe pool }
            }
        ctx.attempts() shouldBe 500
    }
})
