package dev.ledgerline.calc

/** In-memory model of the consumer's inbox: an event id is applied once, however often it is delivered. */
class InboxState(private val engine: AcbEngine) {
    private val live = mutableMapOf<PoolKey, AcbPool>()
    private val history = mutableMapOf<PoolKey, MutableList<PostedActivity>>()
    private val seenIds = mutableSetOf<Long>()

    val pools: Map<PoolKey, AcbPool> get() = live.toMap()
    val seen: Set<Long> get() = seenIds.toSet()

    /** True if the event was applied, false if [eventId] was already seen (nothing changes). */
    fun deliver(
        eventId: Long,
        activity: PostedActivity,
    ): Boolean {
        if (!seenIds.add(eventId)) return false
        if (!activity.inPool()) return true
        // Same as the consumer (B-025): rebuild the touched pool in canonical order, not in arrival order.
        val key = PoolKey(activity.activity.taxpayerId, requireNotNull(activity.activity.securityId))
        val all = history.getOrPut(key) { mutableListOf() }.also { it += activity }
        engine.replay(all).pools[key]?.let { live[key] = it }
        return true
    }
}
