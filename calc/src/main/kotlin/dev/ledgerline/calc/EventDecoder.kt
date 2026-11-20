package dev.ledgerline.calc

import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/** Rebuilds the posted activity from an outbox message value. Null for events that carry none (reversals). */
internal object EventDecoder {
    fun decode(value: ByteArray): PostedActivity? {
        val body = Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject
        if (body.getValue("eventType").jsonPrimitive.content != "ACTIVITY_POSTED") return null
        val a = body.getValue("activity").jsonObject
        val activity =
            Activity(
                idempotencyKey = a.str("idempotencyKey"),
                taxpayerId = a.str("taxpayerId"),
                accountId = a.str("accountId"),
                accountType = AccountType.valueOf(a.str("accountType")),
                activityType = ActivityType.valueOf(a.str("activityType")),
                effectiveDate = LocalDate.parse(a.str("effectiveDate")),
                settlementDate = a.opt("settlementDate")?.let(LocalDate::parse),
                securityId = a.opt("securityId"),
                quantity = a.optLong("quantity"),
                currency = Currency.valueOf(a.str("currency")),
                amountMinor = a.getValue("amountMinor").jsonPrimitive.long,
                commissionMinor = a.getValue("commissionMinor").jsonPrimitive.long,
                counterCurrency = a.opt("counterCurrency")?.let(Currency::valueOf),
                counterAmountMinor = a.optLong("counterAmountMinor"),
                description = a.opt("description"),
                sourceSeq = a.getValue("sourceSeq").jsonPrimitive.long,
            )
        val activityId = body["activityId"]?.jsonPrimitive?.contentOrNull ?: "act-${activity.idempotencyKey}"
        return PostedActivity(activityId, body.getValue("entryId").jsonPrimitive.long, activity)
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun JsonObject.opt(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.optLong(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
}
