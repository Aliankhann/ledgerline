package dev.ledgerline.ledger

import dev.ledgerline.core.Activity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * The idempotency payload hash (ADR 0001): SHA-256, as 64 lowercase hex characters, over a canonical serialization of
 * the parsed activity, never over request bytes. Two encodings of one activity (field order, whitespace, `3012.5` or
 * `3012.50`) hash the same because the hash only sees parsed values.
 *
 * OWNER-INPUT: C20 hash fields. Placeholder: every [Activity] field EXCEPT `description` and `sourceSeq` (and
 * `recorded_at`, which is not an activity field). `sourceSeq` is ordering metadata from row position, not payload;
 * hashing it would turn identical rows in one file (C24) and re-uploaded files into mismatches.
 *
 * Serialization: fields in the fixed order below; text as a 4-byte length plus UTF-8; integers as 8 bytes big-endian;
 * dates as ISO-8601 text; an absent optional value as a single 0 byte and a present one as 1 followed by the value.
 * Lengths make it injective, so no separator can be forged by field content. A new [Activity] field must be
 * added here or consciously left out.
 */
object PayloadHash {
    fun of(activity: Activity): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(v: String) {
                val b = v.toByteArray(Charsets.UTF_8)
                out.writeInt(b.size)
                out.write(b)
            }

            fun optText(v: String?) {
                out.writeByte(if (v == null) 0 else 1)
                if (v != null) text(v)
            }

            fun optLong(v: Long?) {
                out.writeByte(if (v == null) 0 else 1)
                if (v != null) out.writeLong(v)
            }
            text("ledgerline.payload.v1")
            text(activity.idempotencyKey)
            text(activity.taxpayerId)
            text(activity.accountId)
            text(activity.accountType.name)
            text(activity.activityType.name)
            text(activity.effectiveDate.toString())
            optText(activity.settlementDate?.toString())
            optText(activity.securityId)
            optLong(activity.quantity)
            text(activity.currency.name)
            out.writeLong(activity.amountMinor)
            out.writeLong(activity.commissionMinor)
            optText(activity.counterCurrency?.name)
            optLong(activity.counterAmountMinor)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
