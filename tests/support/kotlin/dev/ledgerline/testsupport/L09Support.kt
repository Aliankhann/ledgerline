package dev.ledgerline.testsupport

import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.ActivityType
import java.time.LocalDate

/** Fixture F1 of docs/stories/L-09-late-correction-recompute.md and builders for corrections. All CAD, NON_REGISTERED. */
object L09F1 {
    const val TP1 = "TP-0001"
    const val TP2 = "TP-0002"
    const val AAA = "SYN-AAA"
    const val BBB = "SYN-BBB"

    fun d(
        month: Int,
        day: Int,
    ): LocalDate = LocalDate.of(2025, month, day)

    /** One posted trade; [seq] doubles as the source sequence, [entryId] is the ledger entry (posting order). */
    fun trade(
        key: String,
        type: ActivityType,
        date: LocalDate,
        security: String,
        shares: Long,
        amount: Long,
        entryId: Long,
        taxpayer: String = TP1,
        seq: Long = entryId,
    ): PostedActivity =
        L09Posted.of(
            L06Activities.trade(
                key = key,
                type = type,
                date = date,
                security = security,
                shares = shares * Activities.SHARE,
                grossCents = amount,
                commissionCents = 495L,
                seq = seq,
                accountId = "$taxpayer-NR1",
                taxpayerId = taxpayer,
            ),
            entryId,
        )

    private fun deposit(
        taxpayer: String,
        entryId: Long,
    ): PostedActivity =
        L09Posted.of(
            L06Activities.posted(
                Activities.activity(
                    key = "$taxpayer-2025-0001",
                    type = ActivityType.DEPOSIT,
                    date = d(1, 6),
                    amountMinor = 5_000_000L,
                    accountId = "$taxpayer-NR1",
                    taxpayerId = taxpayer,
                    sourceSeq = 1L,
                ),
                entryId,
            ),
            entryId,
        )

    /** F1 in posting order. Entry ids 1 to 7. */
    fun f1(): List<PostedActivity> =
        listOf(
            deposit(TP1, 1),
            trade("TP-0001-2025-0002", ActivityType.BUY, d(3, 10), AAA, 100, 301_250, 2, seq = 2),
            trade("TP-0001-2025-0003", ActivityType.BUY, d(4, 14), BBB, 50, 225_000, 3, seq = 3),
            trade("TP-0001-2025-0004", ActivityType.SELL, d(6, 2), AAA, 60, 196_800, 4, seq = 4),
            trade("TP-0001-2025-0005", ActivityType.BUY, d(9, 15), AAA, 20, 63_000, 5, seq = 5),
            deposit(TP2, 6),
            trade("TP-0002-2025-0002", ActivityType.BUY, d(3, 10), AAA, 100, 301_250, 7, TP2, seq = 2),
        )

    /** AC3 extension: a BUY of 10 AAA on 2025-02-03, posted before the correction. */
    fun earlyBuy(): PostedActivity = trade("TP-0001-2025-0000", ActivityType.BUY, d(2, 3), AAA, 10, 30_000, 8, seq = 0)

    /** Activity id of [key] as the engine tests build it. */
    fun id(key: String): String = "act-$key"

    /** AC1 replacement: `TP-0001-2025-0002-C1`, same date and sequence as the original, shares and amount changed. */
    fun replacement(
        shares: Long,
        amount: Long,
    ): PostedActivity =
        trade("TP-0001-2025-0002-C1", ActivityType.BUY, d(3, 10), AAA, shares, amount, entryId = 100, seq = 2)
}

object L09Posted {
    fun of(
        posted: PostedActivity,
        entryId: Long,
    ): PostedActivity = posted.copy(entryId = entryId)
}
