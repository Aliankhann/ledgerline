package dev.ledgerline.calc

import dev.ledgerline.core.Activity
import java.time.LocalDate

/** An activity and the ledger entry it was posted as (AC9). */
data class PostedActivity(val activityId: String, val entryId: Long, val activity: Activity)

/** One pool per taxpayer per security, across all non-registered accounts (`POOL-IDENTICAL`). */
data class PoolKey(val taxpayerId: String, val securityId: String)

/** One row per activity that changed shares or ACB; `acbDelta` is in internal units. */
data class PoolChange(
    val activityId: String,
    val entryId: Long,
    val taxDate: LocalDate,
    val sharesDelta: Long,
    val acbDelta: Long,
    val ruleIds: Set<String>,
    /** Internal-unit ACB amount each rule contributed to [acbDelta], where a rule has its own amount. */
    val ruleAmounts: Map<String, Long> = emptyMap(),
)

/** Shares in 10^-6 share units; [totalAcb] in internal units, never negative. */
data class AcbPool(val key: PoolKey, val shares: Long, val totalAcb: Long, val history: List<PoolChange>) {
    companion object {
        fun empty(
            taxpayerId: String,
            securityId: String,
        ): AcbPool = AcbPool(PoolKey(taxpayerId, securityId), 0L, 0L, emptyList())
    }
}

enum class GainKind { DISPOSITION, DEEMED_GAIN_ROC }

/** A reported capital gain (negative = loss). `gain` and `acbSold` are internal units. */
data class GainLine(
    val kind: GainKind,
    val activityId: String,
    val taxDate: LocalDate,
    val gain: Long,
    val gainCents: Long,
    val acbSold: Long,
    val ruleIds: Set<String>,
)

data class StepExplanation(val ruleSetId: String, val ruleIds: Set<String>, val text: String)

enum class RejectionCode { INSUFFICIENT_SHARES }

/** `held` and `requested` are in 10^-6 share units. */
data class Rejection(
    val idempotencyKey: String,
    val code: RejectionCode,
    val held: Long,
    val requested: Long,
    val message: String,
    /** Which pool the sell belonged to; defaulted so older callers still compile (B-028). */
    val taxpayerId: String = "",
    val securityId: String = "",
)

sealed interface PoolStep {
    val pool: AcbPool

    data class Applied(
        override val pool: AcbPool,
        val gains: List<GainLine>,
        val explanation: StepExplanation,
    ) : PoolStep

    data class Rejected(override val pool: AcbPool, val rejection: Rejection) : PoolStep
}
