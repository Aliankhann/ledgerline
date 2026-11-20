package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import java.time.LocalDate

/** `NONE` when nothing was denied; otherwise where the denied amount went (`SL-REGISTERED`, `SL-ADDBACK-TARGET`). */
enum class SlOutcome { NONE, ADDED_TO_ACB, PERMANENTLY_LOST }

/** One substituted acquisition counted for a sale; [shares] in 10^-6 units. */
data class SlTrigger(val activityId: String, val accountType: AccountType, val shares: Long)

/** All amounts in internal ACB units, negative = loss. `reportedGain = gainBeforeRule + denied`. */
data class SaleResult(
    val activityId: String,
    val taxDate: LocalDate,
    val gainBeforeRule: Long,
    val denied: Long,
    val reportedGain: Long,
    val outcome: SlOutcome,
    val triggers: List<SlTrigger>,
    val reason: String,
    val ruleIds: Set<String>,
)

data class SlReplay(
    val ruleSetId: String,
    val pools: Map<PoolKey, AcbPool>,
    val sales: List<SaleResult>,
    val rejections: List<Rejection>,
    val outputHash: String,
)
