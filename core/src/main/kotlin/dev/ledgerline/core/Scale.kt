package dev.ledgerline.core

/**
 * Fixed-point scales. Each scale lives in exactly one constant so it can be changed in one place.
 */
object Scale {
    // OWNER-INPUT: C3 share scale. Placeholder 10^-6 share (ADR 0002 proposal). One unit = 0.000001 share.
    const val SHARE_DECIMALS: Int = 6

    // Cash minor units are cents for both supported currencies (CAD, USD).
    const val MONEY_DECIMALS: Int = 2
}
