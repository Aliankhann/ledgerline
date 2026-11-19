package dev.ledgerline.core

import java.time.LocalDate

enum class AccountType { NON_REGISTERED, TFSA, RRSP }

enum class Currency { CAD, USD }

enum class ActivityType {
    BUY,
    SELL,
    DIVIDEND,
    RETURN_OF_CAPITAL,
    FEE,
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_IN,
    FX_CONVERSION,
}

/** A validated canonical activity. Shares are fixed-point units of [Scale.SHARE_DECIMALS]; cash is minor units. */
data class Activity(
    val idempotencyKey: String,
    val taxpayerId: String,
    val accountId: String,
    val accountType: AccountType,
    val activityType: ActivityType,
    val effectiveDate: LocalDate,
    val settlementDate: LocalDate?,
    val securityId: String?,
    val quantity: Long?,
    val currency: Currency,
    val amountMinor: Long,
    val commissionMinor: Long,
    val counterCurrency: Currency?,
    val counterAmountMinor: Long?,
    val description: String?,
)
