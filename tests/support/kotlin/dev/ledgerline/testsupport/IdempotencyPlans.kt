package dev.ledgerline.testsupport

import dev.ledgerline.core.Activity
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary

/** How one submission relates to the first submission of its key (L-02 AC11). */
enum class SubmissionKind {
    /** The activity itself: the first one posts, later ones must be duplicates. */
    ORIGINAL,

    /** Same payload but a different `description`: ignored by the hash (placeholder C20), so a duplicate. */
    DESCRIPTION_CHANGED,

    /** A hashed field changed (amount or effective date): must be refused, and always arrives after an ORIGINAL. */
    MUTATED,

    /** Only `sourceSeq` changed: not hashed (amended C20), so a duplicate; the first stored value stays. */
    SEQ_CHANGED,
}

data class Submission(val activity: Activity, val kind: SubmissionKind)

/** [distinct] activities (unique keys), and [submissions]: each one 1 to 5 times plus mutated copies, in random order. */
data class IdempotencyPlan(val distinct: List<Activity>, val submissions: List<Submission>)

object IdempotencyPlans {
    private var case = 0L

    /** Plans over streams of 1..[maxLength] valid activities from [ActivityStreams]; keys are unique per case. */
    fun arb(maxLength: Int = 8): Arb<IdempotencyPlan> =
        arbitrary { rs ->
            val random = rs.random
            val distinct = ActivityStreams.generate(random, "IDEM-${++case}", random.nextInt(1, maxLength + 1))
            val list =
                distinct.flatMap { a -> List(random.nextInt(1, 6)) { Submission(a, SubmissionKind.ORIGINAL) } }
                    .shuffled(random).toMutableList()
            distinct.forEach { a ->
                if (random.nextInt(10) < 4) {
                    val mutated =
                        when (random.nextInt(2)) {
                            0 -> a.copy(amountMinor = a.amountMinor + 1)
                            else -> a.copy(effectiveDate = a.effectiveDate.plusDays(1))
                        }
                    list.addAfterFirstOriginal(a, Submission(mutated, SubmissionKind.MUTATED), random)
                }
                if (random.nextInt(10) < 3) {
                    val moved = a.copy(sourceSeq = a.sourceSeq + 1000)
                    list.addAfterFirstOriginal(a, Submission(moved, SubmissionKind.SEQ_CHANGED), random)
                }
                if (random.nextInt(10) < 3) {
                    val noted = a.copy(description = "edited ${random.nextInt(1000)}")
                    list.addAfterFirstOriginal(a, Submission(noted, SubmissionKind.DESCRIPTION_CHANGED), random)
                }
            }
            IdempotencyPlan(distinct, list)
        }

    private fun MutableList<Submission>.addAfterFirstOriginal(
        a: Activity,
        s: Submission,
        random: kotlin.random.Random,
    ) {
        val first =
            indexOfFirst { it.kind == SubmissionKind.ORIGINAL && it.activity.idempotencyKey == a.idempotencyKey }
        add(random.nextInt(first + 1, size + 1), s)
    }
}
