package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType

/** L-16: warnings for holdings whose file history cannot support their ACB. Pure; carries amount 0; never edits a pool. */
object DataGapWarnings {
    // OWNER-INPUT: L-16 placeholder sub-rule ids and texts.
    const val TRANSFER_NO_COST = "DATA-GAP-TRANSFER-NO-COST"
    const val OVERSELL = "DATA-GAP-OVERSELL"
    const val FX_RATE_MISSING = "FX-RATE-MISSING"

    private const val TRANSFER_TEXT = "shares arrived with no cost basis in the file"
    private const val FX_TEXT = "no Bank of Canada USD/CAD rate exists for the trade date; the activity was rejected"
    private const val OVERSELL_TEXT = "a sale exceeds the shares the file shows as held"

    /** At most one reason per rule id per key, entry ids ascending, so the answer ignores the order of [activities]. */
    fun detect(
        activities: List<PostedActivity>,
        replay: Replay,
    ): Map<PoolKey, List<Reason>> {
        val transfers =
            activities.filter {
                it.activity.securityId != null &&
                    it.activity.accountType == AccountType.NON_REGISTERED &&
                    it.activity.activityType == ActivityType.TRANSFER_IN
            }

        fun rejectedFor(code: RejectionCode) =
            replay.rejections.filter { it.code == code }
                .map { Triple(it.taxpayerId, it.securityId, it.idempotencyKey) }.toSet()

        fun rejected(code: RejectionCode): List<PostedActivity> {
            val ids = rejectedFor(code)
            return activities.filter {
                it.activity.securityId != null &&
                    Triple(it.activity.taxpayerId, it.activity.securityId, it.activity.idempotencyKey) in ids
            }
        }
        val oversells = rejected(RejectionCode.INSUFFICIENT_SHARES)
        val fxMissing = rejected(RejectionCode.FX_RATE_MISSING)
        val byRule =
            listOf(TRANSFER_NO_COST to transfers, OVERSELL to oversells, FX_RATE_MISSING to fxMissing)
                .flatMap {
                        (rule, found) ->
                    found.groupBy { PoolKey(it.activity.taxpayerId, requireNotNull(it.activity.securityId)) }
                        .map { (key, list) -> Triple(key, rule, list.map { it.entryId }.sorted()) }
                }
        return byRule.groupBy({ it.first }) { (_, rule, ids) ->
            Reason(rule, textOf(rule), ids, 0L)
        }
    }

    private fun textOf(rule: String): String =
        when (rule) {
            OVERSELL -> OVERSELL_TEXT
            FX_RATE_MISSING -> FX_TEXT
            else -> TRANSFER_TEXT
        }
}
