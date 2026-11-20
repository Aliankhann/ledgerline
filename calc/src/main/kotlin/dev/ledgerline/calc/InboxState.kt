package dev.ledgerline.calc

/**
 * Applies one activity to its pool, as the replay does. Returns the key and the new pool, or null if the activity
 * belongs in no pool. A rejected sell leaves the pool as it was (invariant 7).
 */
internal fun applyToPool(
    engine: AcbEngine,
    pools: Map<PoolKey, AcbPool>,
    posted: PostedActivity,
): Pair<PoolKey, AcbPool>? {
    if (!posted.inPool()) return null
    val key = PoolKey(posted.activity.taxpayerId, requireNotNull(posted.activity.securityId))
    val before = pools[key] ?: AcbPool.empty(key.taxpayerId, key.securityId)
    return key to engine.apply(before, posted).pool
}

/** In-memory model of the consumer's inbox: an event id is applied once, however often it is delivered. */
class InboxState(private val engine: AcbEngine) {
    private val live = mutableMapOf<PoolKey, AcbPool>()
    private val seenIds = mutableSetOf<Long>()

    val pools: Map<PoolKey, AcbPool> get() = live.toMap()
    val seen: Set<Long> get() = seenIds.toSet()

    /** True if the event was applied, false if [eventId] was already seen (nothing changes). */
    fun deliver(
        eventId: Long,
        activity: PostedActivity,
    ): Boolean {
        if (!seenIds.add(eventId)) return false
        applyToPool(engine, live, activity)?.let { (key, pool) -> live[key] = pool }
        return true
    }
}
