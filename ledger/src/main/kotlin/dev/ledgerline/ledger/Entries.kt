package dev.ledgerline.ledger

import dev.ledgerline.core.ActivityType
import java.time.LocalDate

enum class Side { DEBIT, CREDIT }

/**
 * One ledger line. [unit] is a currency code (`CAD`, `USD`) or a security commodity `SEC:<securityId>`; [amount] is a
 * positive integer in that unit's smallest step (cash minor units, or share units at the [dev.ledgerline.core.Scale]
 * share scale). An account's balance in a unit is its debits minus its credits.
 */
data class LedgerLine(val account: String, val unit: String, val side: Side, val amount: Long)

/** A request to append one entry. The ledger checks balance per unit before anything is written. */
data class EntryRequest(
    val idempotencyKey: String,
    val effectiveDate: LocalDate,
    val sourceSeq: Long,
    val lines: List<LedgerLine>,
)

// OWNER-INPUT: C4 ordering. sequence is storage order; calculation uses (effectiveDate, sourceSeq, idempotencyKey).

/**
 * A stored entry. [sequence] is assigned by the ledger and is strictly increasing in commit order; it is storage
 * order only. Calculation orders by (effectiveDate, sourceSeq, idempotencyKey) instead.
 */
data class PostedEntry(
    val sequence: Long,
    val idempotencyKey: String,
    val effectiveDate: LocalDate,
    val sourceSeq: Long,
    val lines: List<LedgerLine>,
)

/** The lines of an entry do not balance in [unit]; [difference] is debits minus credits. Nothing was written. */
class UnbalancedEntryException(val unit: String, val difference: Long) :
    RuntimeException("$CODE: unit $unit is off by $difference (debits minus credits)") {
    val code: String = CODE

    companion object {
        const val CODE = "UNBALANCED"
    }
}

// OWNER-INPUT: C15 zero amounts. Placeholder: refused at posting with ZERO_AMOUNT (docs/owner-inputs.md).

/** A cash-only activity with amount 0 would post no lines, so it is refused. Nothing was written. */
class ZeroAmountException(val idempotencyKey: String, val activityType: ActivityType) :
    RuntimeException("$CODE: $idempotencyKey is a $activityType with amount 0, which posts nothing") {
    val code: String = CODE

    companion object {
        const val CODE = "ZERO_AMOUNT"
    }
}
