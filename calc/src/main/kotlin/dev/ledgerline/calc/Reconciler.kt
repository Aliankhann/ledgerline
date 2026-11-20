package dev.ledgerline.calc

enum class DriftKind { FIELD_MISMATCH, MISSING_IN_LIVE, EXTRA_IN_LIVE }

/** One difference between live and replayed pools. Values are set only for [DriftKind.FIELD_MISMATCH]. */
data class PoolDiff(val key: PoolKey, val kind: DriftKind, val field: String?, val live: Long?, val replay: Long?)

data class ReconReport(
    val poolsCompared: Int,
    val drift: List<PoolDiff>,
    val liveHash: String,
    val replayHash: String,
    val ok: Boolean,
)

/**
 * SHA-256 (lower-case hex) over one line per pool in ascending (taxpayerId, securityId) order:
 * `pool|<taxpayerId>|<securityId>|<shares>|<totalAcb>\n`. Integers only, LF only, UTF-8, no locale use.
 */
object ReplayHash {
    // OWNER-INPUT: C28 the hash covers shares and totalAcb only (proposed: also adjustments, per the story).
    fun of(pools: Map<PoolKey, AcbPool>): String =
        sha256Hex(
            buildString {
                pools.toSortedMap(KEY_ORDER).forEach { (k, p) ->
                    append("pool|").append(k.taxpayerId).append('|').append(k.securityId).append('|')
                    append(p.shares.toString()).append('|').append(p.totalAcb.toString()).append('\n')
                }
            },
        )
}

private val KEY_ORDER = compareBy<PoolKey>({ it.taxpayerId }, { it.securityId })

/** Replays the ledger history in canonical order and diffs the live pools against it. Pure; no I/O. */
class Reconciler(val engine: AcbEngine) {
    fun reconcile(
        livePools: Map<PoolKey, AcbPool>,
        ledgerHistory: Collection<PostedActivity>,
    ): ReconReport {
        val replayPools = engine.replay(ledgerHistory).pools
        val keys = (livePools.keys + replayPools.keys).toSortedSet(KEY_ORDER)
        val drift = mutableListOf<PoolDiff>()
        for (key in keys) {
            val live = livePools[key]
            val replay = replayPools[key]
            when {
                live == null -> drift += PoolDiff(key, DriftKind.MISSING_IN_LIVE, null, null, null)
                replay == null -> drift += PoolDiff(key, DriftKind.EXTRA_IN_LIVE, null, null, null)
                else -> {
                    if (live.shares != replay.shares) {
                        drift += PoolDiff(key, DriftKind.FIELD_MISMATCH, "shares", live.shares, replay.shares)
                    }
                    if (live.totalAcb != replay.totalAcb) {
                        drift += PoolDiff(key, DriftKind.FIELD_MISMATCH, "totalAcb", live.totalAcb, replay.totalAcb)
                    }
                }
            }
        }
        return ReconReport(keys.size, drift, ReplayHash.of(livePools), ReplayHash.of(replayPools), drift.isEmpty())
    }
}
