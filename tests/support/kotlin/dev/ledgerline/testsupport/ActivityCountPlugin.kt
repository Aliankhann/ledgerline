package dev.ledgerline.testsupport

import dev.ledgerline.core.Activity
import dev.ledgerline.core.DomainPlugin
import dev.ledgerline.core.Validation

/**
 * Test-only plugin for L-03 AC10: counts activities per account. Pure: no I/O, no clock, no randomness.
 * `validate` rejects a blank `accountId`; `apply` increments the count; `explain` lists the keys counted, in order.
 */
object ActivityCountPlugin : DomainPlugin<ActivityCountPlugin.State, List<String>> {
    data class State(val countsByAccount: Map<String, Int>, val keys: List<String>) {
        fun countOf(accountId: String): Int = countsByAccount[accountId] ?: 0
    }

    fun empty(): State = State(emptyMap(), emptyList())

    override fun validate(activity: Activity): Validation =
        if (activity.accountId.isBlank()) Validation.Rejected("accountId is blank") else Validation.Accepted

    override fun apply(
        state: State,
        activity: Activity,
    ): State =
        State(
            countsByAccount = state.countsByAccount + (activity.accountId to state.countOf(activity.accountId) + 1),
            keys = state.keys + activity.idempotencyKey,
        )

    override fun explain(state: State): List<String> = state.keys
}
