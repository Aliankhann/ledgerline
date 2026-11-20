package dev.ledgerline.calc

import dev.ledgerline.core.Activity
import dev.ledgerline.core.DomainPlugin
import java.security.MessageDigest

/**
 * Domain-neutral replay helpers for any [DomainPlugin]; no ACB types.
 * NOTE for the owner: this arguably belongs in `core/` (it needs only `core` types); it sits in `calc/` for now.
 */
object PluginReplay {
    /** Effective date, then source sequence, then idempotency key. Never arrival order. */
    fun canonicalOrder(activities: Collection<Activity>): List<Activity> =
        activities.sortedWith(compareBy({ it.effectiveDate }, { it.sourceSeq }, { it.idempotencyKey }))

    /** Folds [plugin].apply over the canonical order; a repeated idempotency key is skipped. */
    fun <S, E> replay(
        plugin: DomainPlugin<S, E>,
        initial: S,
        activities: Collection<Activity>,
    ): S {
        val seen = HashSet<String>()
        return canonicalOrder(activities).fold(initial) { state, a ->
            if (seen.add(a.idempotencyKey)) plugin.apply(state, a) else state
        }
    }

    /** Lower-case hex SHA-256 over the UTF-8 bytes of [text]. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
