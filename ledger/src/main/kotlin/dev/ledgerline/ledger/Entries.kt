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
    /** The sequence of the entry this one reverses, or null if it is not a reversal. */
    val reversesSequence: Long? = null,
)

/** [REVERSED] is derived from a reversal's link row; the original entry is never updated. */
enum class EntryState { POSTED, REVERSED }

data class EntryStatus(val state: EntryState, val reversalSequence: Long?)

data class CorrectionResult(val reversal: PostedEntry, val replacement: PostedEntry)

/**
 * A reversal or correction was refused and nothing was written. [code] is one of [UNKNOWN_ENTRY], [ALREADY_REVERSED]
 * (then [existingReversalSequence] names the first reversal), [REVERSAL_OF_REVERSAL] or [NEGATIVE_SHARES].
 */
class ReversalRefusedException(
    val code: String,
    val entrySequence: Long,
    val existingReversalSequence: Long? = null,
) : RuntimeException("$code: entry $entrySequence" + (existingReversalSequence?.let { " (reversed by $it)" } ?: "")) {
    companion object {
        const val UNKNOWN_ENTRY = "UNKNOWN_ENTRY"
        const val ALREADY_REVERSED = "ALREADY_REVERSED"

        // OWNER-INPUT: C16 reversing a reversal is refused (docs/owner-inputs.md).
        const val REVERSAL_OF_REVERSAL = "REVERSAL_OF_REVERSAL"

        // Invariant 7: a reversal may not leave any account with fewer than 0 units of a security.
        const val NEGATIVE_SHARES = "NEGATIVE_SHARES"
    }
}

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
