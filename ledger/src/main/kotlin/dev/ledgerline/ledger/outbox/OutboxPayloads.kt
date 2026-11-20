package dev.ledgerline.ledger.outbox

import dev.ledgerline.core.Activity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * The JSON bodies stored in `ledger.outbox.payload` and the message values built from them. Every number is an integer
 * (minor units, fixed-point share units, ids); there is no floating point. The stored body has no `eventId`, because
 * the id is assigned by the insert; [messageValue] puts it first. Encoding a stored body is deterministic, so every
 * send of one event carries the same bytes.
 */
internal object OutboxPayloads {
    private val json = Json

    /** An entry posted for [activity] (a submission or a correction's replacement); [activityId] null if none. */
    fun activityPosted(
        entryId: Long,
        activityId: String?,
        activity: Activity,
    ): String =
        encode(
            buildJsonObject {
                common(OutboxEventType.ACTIVITY_POSTED, activity.taxpayerId, activity.idempotencyKey, entryId)
                put("effectiveDate", activity.effectiveDate.toString())
                put("sourceSeq", activity.sourceSeq)
                put("activityId", activityId)
                put("activity", activityFields(activity))
            },
        )

    /** Reversal entry [entryId] (keyed [idempotencyKey]) of entry [reversesEntryId], which belongs to [taxpayerId]. */
    fun entryReversed(
        entryId: Long,
        idempotencyKey: String,
        taxpayerId: String,
        effectiveDate: LocalDate,
        sourceSeq: Long,
        reversesEntryId: Long,
        reason: String,
    ): String =
        encode(
            buildJsonObject {
                common(OutboxEventType.ENTRY_REVERSED, taxpayerId, idempotencyKey, entryId)
                put("effectiveDate", effectiveDate.toString())
                put("sourceSeq", sourceSeq)
                put("reversesEntryId", reversesEntryId)
                put("reason", reason)
            },
        )

    /** The message value: `eventId` followed by the stored body's fields, as UTF-8 JSON. */
    fun messageValue(
        eventId: Long,
        payload: String,
    ): ByteArray {
        val body = json.parseToJsonElement(payload) as JsonObject
        val value =
            buildJsonObject {
                put("eventId", eventId)
                body.forEach { (k, v) -> put(k, v) }
            }
        return encode(value).toByteArray(Charsets.UTF_8)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.common(
        type: OutboxEventType,
        taxpayerId: String,
        idempotencyKey: String,
        entryId: Long,
    ) {
        put("eventType", type.name)
        put("taxpayerId", taxpayerId)
        put("idempotencyKey", idempotencyKey)
        put("entryId", entryId)
        // The ledger sequence is the entry id (ADR 0005); both names are part of the published shape.
        put("sequence", entryId)
    }

    private fun activityFields(a: Activity): JsonObject =
        buildJsonObject {
            put("idempotencyKey", a.idempotencyKey)
            put("taxpayerId", a.taxpayerId)
            put("accountId", a.accountId)
            put("accountType", a.accountType.name)
            put("activityType", a.activityType.name)
            put("effectiveDate", a.effectiveDate.toString())
            put("settlementDate", a.settlementDate?.toString())
            put("securityId", a.securityId)
            put("quantity", a.quantity)
            put("currency", a.currency.name)
            put("amountMinor", a.amountMinor)
            put("commissionMinor", a.commissionMinor)
            put("counterCurrency", a.counterCurrency?.name)
            put("counterAmountMinor", a.counterAmountMinor)
            put("description", a.description)
            put("sourceSeq", a.sourceSeq)
        }

    private fun encode(o: JsonObject): String = json.encodeToString(JsonObject.serializer(), o)
}
