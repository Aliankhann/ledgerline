package dev.ledgerline.testsupport

import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.Activities.activity
import java.time.LocalDate

/**
 * The activities named in story L-03's acceptance criteria (docs/stories/L-03-balanced-immutable-ledger.md), all for
 * `TP-0001-NR1`. Quantities use the C3 placeholder scale (10^-6 share); `sourceSeq` (C4 placeholder) is the key's
 * numeric suffix. Keys other than AC1, AC2 and AC3 are QE-chosen, since the story gives none.
 */
object L03Activities {
    private fun d(
        m: Int,
        day: Int,
    ): LocalDate = LocalDate.of(2025, m, day)

    // AC1: canonical activity TP-0001-2025-0002.
    val ac1 =
        activity(
            "TP-0001-2025-0002",
            ActivityType.BUY,
            d(1, 7),
            amountMinor = 301_250L,
            securityId = "SYN-AAA",
            quantity = 100 * SHARE,
            commissionMinor = 495L,
            sourceSeq = 2,
        )

    // AC2: TP-0001-2025-0020.
    val ac2 =
        activity(
            "TP-0001-2025-0020", ActivityType.BUY, d(3, 3), amountMinor = 123_400L, currency = Currency.USD,
            securityId = "SYN-USX", quantity = 25 * SHARE, commissionMinor = 495L, sourceSeq = 20,
        )

    // AC3: TP-0001-2025-0003.
    val ac3 =
        activity(
            "TP-0001-2025-0003",
            ActivityType.FX_CONVERSION,
            d(1, 9),
            amountMinor = 138_000L,
            counterCurrency = Currency.USD,
            counterAmountMinor = 100_000L,
            sourceSeq = 3,
        )

    // AC4: one of each type, in effective-date order (also AC10's "in order"). Keys other than AC1/AC3 are QE's.
    val ac4 =
        listOf(
            activity("TP-0001-2025-0001", ActivityType.DEPOSIT, d(1, 6), amountMinor = 1_000_000L, sourceSeq = 1),
            ac1,
            ac3,
            activity(
                "TP-0001-2025-0004",
                ActivityType.SELL,
                d(2, 3),
                amountMinor = 128_000L,
                securityId = "SYN-AAA",
                quantity = 40 * SHARE,
                commissionMinor = 495L,
                sourceSeq = 4,
            ),
            activity(
                "TP-0001-2025-0005",
                ActivityType.DIVIDEND,
                d(2, 14),
                amountMinor = 1_234L,
                securityId = "SYN-AAA",
                sourceSeq = 5,
            ),
            activity(
                "TP-0001-2025-0006",
                ActivityType.RETURN_OF_CAPITAL,
                d(2, 28),
                amountMinor = 2_000L,
                securityId = "SYN-AAA",
                sourceSeq = 6,
            ),
            activity("TP-0001-2025-0007", ActivityType.FEE, d(3, 3), amountMinor = 995L, sourceSeq = 7),
            activity("TP-0001-2025-0008", ActivityType.WITHDRAWAL, d(3, 10), amountMinor = 50_000L, sourceSeq = 8),
            activity(
                "TP-0001-2025-0009",
                ActivityType.TRANSFER_IN,
                d(3, 14),
                amountMinor = 45_000L,
                securityId = "SYN-BBB",
                quantity = 10 * SHARE,
                sourceSeq = 9,
            ),
        )
}
