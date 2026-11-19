package dev.ledgerline.core

/** Outcome of [DomainPlugin.validate]. */
sealed interface Validation {
    data object Accepted : Validation

    data class Rejected(val reason: String) : Validation
}

/**
 * A domain that derives state from the activity stream (for example the ACB engine).
 *
 * Implementations must be pure: no I/O, clock or randomness, so that replaying the same activities in the same order
 * always yields equal states (invariant 5). The caller owns ordering and persistence.
 *
 * @param S the plugin's state; treat it as immutable.
 * @param E what [explain] returns for a state.
 */
interface DomainPlugin<S, E> {
    /** Accepts or rejects [activity] for this domain, without changing any state. */
    fun validate(activity: Activity): Validation

    /** Returns the state after applying [activity] to [state]. */
    fun apply(
        state: S,
        activity: Activity,
    ): S

    /** Explains [state], for example how a derived number was reached. */
    fun explain(state: S): E
}
