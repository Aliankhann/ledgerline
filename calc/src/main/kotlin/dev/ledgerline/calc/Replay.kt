package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import java.security.MessageDigest

/** Result of replaying activities in canonical order. The hash covers the rule set ID, pools and gains. */
data class Replay(
    val ruleSetId: String,
    val pools: Map<PoolKey, AcbPool>,
    val gains: List<GainLine>,
    val rejections: List<Rejection>,
    val outputHash: String,
)

private val POOL_TYPES = setOf(ActivityType.BUY, ActivityType.SELL, ActivityType.RETURN_OF_CAPITAL)

/** Canonical order: tax date, then sourceSeq, then idempotency key. Never arrival order (invariant 6). */
internal fun replayAll(
    engine: AcbEngine,
    activities: Collection<PostedActivity>,
): Replay {
    val ordered =
        activities
            .filter { it.activity.securityId != null }
            .filter { it.activity.accountType == AccountType.NON_REGISTERED }
            .filter { it.activity.activityType in POOL_TYPES }
            .sortedWith(
                compareBy<PostedActivity>(
                    { engine.ruleSet.taxDate(it.activity) },
                    { it.activity.sourceSeq },
                    { it.activity.idempotencyKey },
                ),
            )
    val pools = mutableMapOf<PoolKey, AcbPool>()
    val gains = mutableListOf<GainLine>()
    val rejections = mutableListOf<Rejection>()
    for (posted in ordered) {
        val key = PoolKey(posted.activity.taxpayerId, requireNotNull(posted.activity.securityId))
        val before = pools[key] ?: AcbPool.empty(key.taxpayerId, key.securityId)
        when (val step = engine.apply(before, posted)) {
            is PoolStep.Applied -> {
                pools[key] = step.pool
                gains += step.gains
            }
            is PoolStep.Rejected -> {
                pools[key] = step.pool
                rejections += step.rejection
            }
        }
    }
    val sorted = pools.toSortedMap(compareBy({ it.taxpayerId }, { it.securityId }))
    return Replay(engine.ruleSet.id, sorted, gains, rejections, outputHash(engine.ruleSet.id, sorted, gains))
}

private fun outputHash(
    ruleSetId: String,
    pools: Map<PoolKey, AcbPool>,
    gains: List<GainLine>,
): String {
    val text =
        buildString {
            append("rules=").append(ruleSetId).append('\n')
            pools.forEach { (k, p) ->
                append("pool|${k.taxpayerId}|${k.securityId}|${p.shares}|${p.totalAcb}\n")
                p.history.forEach { c ->
                    append("chg|${c.activityId}|${c.entryId}|${c.taxDate}|${c.sharesDelta}|${c.acbDelta}|")
                    append(c.ruleIds.sorted().joinToString(",")).append('\n')
                }
            }
            gains.forEach { g ->
                append("gain|${g.kind}|${g.activityId}|${g.taxDate}|${g.gain}|${g.gainCents}|${g.acbSold}|")
                append(g.ruleIds.sorted().joinToString(",")).append('\n')
            }
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
