package dev.ledgerline.calc

import dev.ledgerline.core.ActivityType
import java.time.LocalDate

/** L-17: how a holding's ACB was built and how its difference to the book value is explained. */
enum class ExplainStatus { MATCHES, EXPLAINED, DISCREPANCY, DATA_GAP, NO_BOOK_VALUE }

/** Superficial-loss detail on the add-back step; amounts are reported cents. */
data class SuperficialLossStep(
    val saleEntryId: Long,
    val targetEntryIds: List<Long>,
    val lossCents: Long,
    val deniedCents: Long,
    val addBackCents: Long,
)

data class ExplainStep(
    val entryId: Long,
    val activityId: String,
    val idempotencyKey: String,
    val taxDate: LocalDate,
    val type: ActivityType,
    val ruleIds: List<String>,
    val sharesDeltaUnits: Long,
    val costDeltaCents: Long,
    val runningSharesUnits: Long,
    val runningAcbCents: Long,
    val superficialLoss: SuperficialLossStep?,
)

data class Explanation(
    val key: PoolKey,
    val bookValueCents: Long?,
    val acbCents: Long,
    val differenceCents: Long?,
    val steps: List<ExplainStep>,
    val reasons: List<Reason>,
    val status: ExplainStatus,
    val fullyExplained: Boolean,
    val adjustmentCount: Int,
    val unexplainedCents: Long,
)

/** The honest summary of a difference: "fully explained" only when the cents add up and nothing is UNEXPLAINED. */
object ExplanationFooter {
    data class Footer(
        val status: ExplainStatus,
        val fullyExplained: Boolean,
        val adjustmentCount: Int,
        val unexplainedCents: Long,
    )

    fun of(
        differenceCents: Long,
        reasons: List<Reason>,
    ): Footer {
        val explained = reasons.filter { it.ruleId != BookValueComparison.UNEXPLAINED }.sumOf { it.amountMinor }
        val unexplained = Math.subtractExact(differenceCents, explained)
        val flagged =
            reasons.any { it.ruleId == BookValueComparison.UNEXPLAINED && it.amountMinor != 0L }
        val full = unexplained == 0L && !flagged
        val status =
            when {
                !full -> ExplainStatus.DISCREPANCY
                differenceCents == 0L -> ExplainStatus.MATCHES
                else -> ExplainStatus.EXPLAINED
            }
        return Footer(status, full, reasons.count { it.amountMinor != 0L }, unexplained)
    }
}

object HoldingExplanation {
    private val rules = CaIta2026v1
    private val engine = AcbEngine(rules)

    /** Null only for an unknown holding: no pool and no L-16 warning for [key]. */
    fun explain(
        activities: List<PostedActivity>,
        bookValueCents: Long?,
        key: PoolKey,
    ): Explanation? {
        val replay = engine.replaySuperficial(activities)
        val pool = replay.pools[key]
        val warnings = DataGapWarnings.detect(activities, engine.replay(activities))[key].orEmpty()
        if (pool == null && warnings.isEmpty() && bookValueCents == null) return null
        val row =
            BookValueComparison.compareAll(
                listOfNotNull(pool).associateBy { it.key },
                listOfNotNull(bookValueCents).associate { key to it },
                mapOf(key to warnings).filterValues { it.isNotEmpty() },
            ).single()
        val steps = pool?.let { steps(it, activities, replay) }.orEmpty()
        val diff = row.differenceCents
        val footer = diff?.let { ExplanationFooter.of(it, row.reasons) }
        val status =
            when {
                (warnings.isNotEmpty() || pool == null) && steps.isEmpty() -> ExplainStatus.DATA_GAP
                footer == null -> ExplainStatus.NO_BOOK_VALUE
                else -> footer.status
            }
        return Explanation(
            key,
            row.bookValueCents,
            row.acbCents,
            diff,
            steps,
            row.reasons,
            status,
            // A difference only "explained" by reasons that cite no entry (cannot be traced) is not fully explained.
            (footer?.fullyExplained ?: false) && row.reasons.none { it.amountMinor != 0L && it.entryIds.isEmpty() },
            footer?.adjustmentCount ?: 0,
            footer?.unexplainedCents ?: 0L,
        )
    }

    private fun steps(
        pool: AcbPool,
        activities: List<PostedActivity>,
        replay: SlReplay,
    ): List<ExplainStep> {
        val byActivity = activities.associateBy { it.activityId }
        var shares = 0L
        var acb = 0L
        var previousCents = 0L
        val stepEntryIds = pool.history.map { it.entryId }.toSet()
        // Tax-date order (AC5); sortedBy is stable, so ties keep history (sequence) order. Running totals follow it.
        return pool.history.sortedBy { it.taxDate }.map { change ->
            shares = Math.addExact(shares, change.sharesDelta)
            acb = Math.addExact(acb, change.acbDelta)
            val runningCents = rules.toReportedCents(acb)
            val posted = byActivity.getValue(change.activityId)
            val addBack = if ("SL-ADDBACK-TARGET" in change.ruleIds) change else null
            val step =
                ExplainStep(
                    change.entryId,
                    change.activityId,
                    posted.activity.idempotencyKey,
                    change.taxDate,
                    posted.activity.activityType,
                    change.ruleIds.sorted(),
                    change.sharesDelta,
                    Math.subtractExact(runningCents, previousCents),
                    shares,
                    runningCents,
                    addBack?.let { superficial(it, byActivity, replay, stepEntryIds) },
                )
            previousCents = runningCents
            step
        }
    }

    private fun superficial(
        change: PoolChange,
        byActivity: Map<String, PostedActivity>,
        replay: SlReplay,
        stepEntryIds: Set<Long>,
    ): SuperficialLossStep? {
        val sale = replay.sales.firstOrNull { it.activityId == change.activityId } ?: return null
        return SuperficialLossStep(
            change.entryId,
            sale.triggers.map { byActivity.getValue(it.activityId).entryId }.filter { it in stepEntryIds },
            rules.toReportedCents(-sale.gainBeforeRule),
            rules.toReportedCents(sale.denied),
            rules.toReportedCents(change.acbDelta),
        )
    }
}
