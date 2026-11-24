package dev.ledgerline.app

import dev.ledgerline.calc.BookValueCsv
import dev.ledgerline.calc.ExplainStep
import dev.ledgerline.calc.Explanation
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /v1/holdings/{taxpayer}/{security}/explain` (L-17). Amounts are cents; the explanation is built in `calc`. */
fun Application.explainFromActivities(
    activities: (taxpayer: String) -> List<PostedActivity>,
    bookValueCsv: String,
) {
    routing {
        get("/v1/holdings/{taxpayer}/{security}/explain") {
            val taxpayer = call.parameters["taxpayer"].orEmpty()
            val security = call.parameters["security"].orEmpty()
            val key = PoolKey(taxpayer, security)
            val book =
                runCatching { BookValueCsv.parse(taxpayer, bookValueCsv)[key] }
                    .getOrElse {
                        val msg = "Bad book value file: ${it.message}"
                        return@get call.respondText(msg, ContentType.Text.Plain, HttpStatusCode.BadRequest)
                    }
            val explanation = HoldingExplanation.explain(activities(taxpayer), book, key)
            if (explanation == null) {
                call.respondText(
                    "No holding $security for taxpayer $taxpayer",
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound,
                )
            } else {
                call.respondText(explanationJson(explanation).toString(), ContentType.Application.Json)
            }
        }
    }
}

private fun explanationJson(e: Explanation): JsonElement =
    buildJsonObject {
        put("taxpayerId", e.key.taxpayerId)
        put("securityId", e.key.securityId)
        put("currency", "CAD")
        put("bookValueCents", e.bookValueCents.json())
        put("acbCents", e.acbCents)
        put("differenceCents", e.differenceCents.json())
        put("status", e.status.name)
        put("fullyExplained", e.fullyExplained)
        put("adjustmentCount", e.adjustmentCount)
        put("unexplainedCents", e.unexplainedCents)
        put("steps", buildJsonArray { e.steps.forEach { add(stepJson(it)) } })
        put(
            "reasons",
            buildJsonArray {
                e.reasons.forEach { r ->
                    add(
                        buildJsonObject {
                            put("ruleId", r.ruleId)
                            put("text", r.text)
                            put("entryIds", r.entryIds.jsonIds())
                            put("amountMinor", r.amountMinor)
                        },
                    )
                }
            },
        )
    }

private fun stepJson(s: ExplainStep): JsonElement =
    buildJsonObject {
        put("entryId", s.entryId)
        put("activityId", s.activityId)
        put("idempotencyKey", s.idempotencyKey)
        put("taxDate", s.taxDate.toString())
        put("type", s.type.name)
        put("ruleIds", buildJsonArray { s.ruleIds.forEach { add(JsonPrimitive(it)) } })
        put("sharesDeltaUnits", s.sharesDeltaUnits)
        put("costDeltaCents", s.costDeltaCents)
        put("runningSharesUnits", s.runningSharesUnits)
        put("runningAcbCents", s.runningAcbCents)
        val sl = s.superficialLoss
        put(
            "superficialLoss",
            if (sl == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("saleEntryId", sl.saleEntryId)
                    put("targetEntryIds", sl.targetEntryIds.jsonIds())
                    put("lossCents", sl.lossCents)
                    put("deniedCents", sl.deniedCents)
                    put("addBackCents", sl.addBackCents)
                }
            },
        )
    }

private fun Long?.json(): JsonElement = JsonPrimitive(this)

private fun List<Long>.jsonIds(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
