package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import java.time.LocalDate

/**
 * A correction: activities removed from the effective set (a reversed original; C19 replay skips the reversed pair)
 * and activities added (a replacement, or a late new activity).
 */
data class Correction(val reversedActivityIds: Set<String>, val added: List<PostedActivity>)

data class RecomputeResult(
    val changedPools: Map<PoolKey, AcbPool>,
    val unchangedPools: Map<PoolKey, AcbPool>,
    val fromDate: LocalDate?,
    val rejections: List<Rejection>,
    val outputHash: String,
)

/**
 * Late-correction recompute (L-09). Only pools of (taxpayer, security) pairs the correction touches are re-applied,
 * from the earliest touched tax date; the pool state before that date is rebuilt from the kept history rows.
 *
 * Follow-up: gains are not stored per pool, so [RecomputeResult.outputHash] and rejections come from one full
 * [AcbEngine.replay] of the effective set. Persisting gains per pool would remove that.
 */
class PoolRecomputer(private val engine: AcbEngine) {
    fun recomputeFrom(
        pools: Map<PoolKey, AcbPool>,
        history: Collection<PostedActivity>,
        correction: Correction,
    ): RecomputeResult {
        val present = history.map { it.activityId }.toSet()
        val redelivered =
            correction.reversedActivityIds.none { it in present } && correction.added.all { it.activityId in present }
        if (redelivered) {
            return RecomputeResult(emptyMap(), pools, null, emptyList(), engine.replay(history).outputHash)
        }
        val effective = history.filterNot { it.activityId in correction.reversedActivityIds } + correction.added
        val full = engine.replay(effective)

        val touchedActivities =
            (history.filter { it.activityId in correction.reversedActivityIds } + correction.added)
                .filter { it.activity.accountType == AccountType.NON_REGISTERED && it.activity.securityId != null }
        val fromDate = touchedActivities.minOfOrNull { engine.ruleSet.taxDate(it.activity) }
        val touched = touchedActivities.map { it.key() }.toSet()

        val changed = mutableMapOf<PoolKey, AcbPool>()
        val unchanged = mutableMapOf<PoolKey, AcbPool>()
        for (key in full.pools.keys) {
            val old = pools[key]
            if (key !in touched || fromDate == null) {
                unchanged[key] = old ?: full.pools.getValue(key)
                continue
            }
            val recomputed = recomputePool(key, old, effective, fromDate)
            if (recomputed == old) unchanged[key] = old else changed[key] = recomputed
        }
        return RecomputeResult(changed, unchanged, fromDate, full.rejections, full.outputHash)
    }

    private fun recomputePool(
        key: PoolKey,
        old: AcbPool?,
        effective: List<PostedActivity>,
        fromDate: LocalDate,
    ): AcbPool {
        val kept = old?.history.orEmpty().takeWhile { it.taxDate < fromDate }
        var pool =
            AcbPool(
                key,
                kept.sumOf { it.sharesDelta },
                kept.sumOf { it.acbDelta },
                kept,
            )
        val rest =
            canonicalOrder(engine, effective)
                .filter { it.key() == key && it.activity.accountType == AccountType.NON_REGISTERED }
                .filter { engine.ruleSet.taxDate(it.activity) >= fromDate }
        for (posted in rest) pool = engine.apply(pool, posted).pool
        return pool
    }

    private fun PostedActivity.key() = PoolKey(activity.taxpayerId, requireNotNull(activity.securityId))
}
