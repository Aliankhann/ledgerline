package dev.ledgerline.testsupport

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import java.time.LocalDate

/**
 * Named-argument builder for canonical activities. Defaults are the L-03 story's account `TP-0001-NR1`.
 *
 * OWNER-INPUT C4 (placeholder): `sourceSeq` is a field on the activity (source row order), not arrival order.
 */
object Activities {
    const val TAXPAYER = "TP-0001"
    const val ACCOUNT = "TP-0001-NR1"

    /** One share at the C3 placeholder scale of 10^-6 share. */
    const val SHARE: Long = 1_000_000L

    fun activity(
        key: String,
        type: ActivityType,
        date: LocalDate,
        amountMinor: Long,
        currency: Currency = Currency.CAD,
        securityId: String? = null,
        quantity: Long? = null,
        commissionMinor: Long = 0L,
        counterCurrency: Currency? = null,
        counterAmountMinor: Long? = null,
        accountId: String = ACCOUNT,
        accountType: AccountType = AccountType.NON_REGISTERED,
        taxpayerId: String = TAXPAYER,
        sourceSeq: Long = 0L,
    ): Activity =
        Activity(
            idempotencyKey = key,
            taxpayerId = taxpayerId,
            accountId = accountId,
            accountType = accountType,
            activityType = type,
            effectiveDate = date,
            settlementDate = null,
            securityId = securityId,
            quantity = quantity,
            currency = currency,
            amountMinor = amountMinor,
            commissionMinor = commissionMinor,
            counterCurrency = counterCurrency,
            counterAmountMinor = counterAmountMinor,
            description = null,
            sourceSeq = sourceSeq,
        )
}
