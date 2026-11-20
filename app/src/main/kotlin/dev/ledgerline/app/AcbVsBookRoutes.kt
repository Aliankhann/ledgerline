package dev.ledgerline.app

import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.BookValueCsv
import dev.ledgerline.calc.HoldingDiff
import dev.ledgerline.calc.PoolKey
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /v1/holdings/{taxpayer}/acb-vs-book` (L-11). Amounts are cents; the comparison itself is in `calc`. */
fun Application.acbVsBookModule(
    pools: Map<PoolKey, AcbPool>,
    bookValueCsv: String,
) {
    install(ContentNegotiation) { json() }
    routing {
        get("/v1/holdings/{taxpayer}/acb-vs-book") {
            val taxpayer = call.parameters["taxpayer"].orEmpty()
            // The CSV names no taxpayer, so it is applied only to a taxpayer the ledger knows (an unknown one gets none).
            val known = pools.keys.any { it.taxpayerId == taxpayer }
            val diffs =
                if (known) {
                    BookValueComparison.compare(
                        pools,
                        BookValueCsv.parse(taxpayer, bookValueCsv),
                    )
                } else {
                    emptyList()
                }
            val body =
                buildJsonObject {
                    put("taxpayerId", taxpayer)
                    put("currency", "CAD")
                    put("holdings", buildJsonArray { diffs.forEach { add(holdingJson(it)) } })
                }
            call.respondText(body.toString(), ContentType.Application.Json)
        }
    }
}

private fun holdingJson(d: HoldingDiff): JsonObject =
    buildJsonObject {
        put("securityId", d.key.securityId)
        put("bookValueCents", d.bookValueCents)
        put("acbCents", d.acbCents)
        put("differenceCents", d.differenceCents)
        put(
            "reasons",
            buildJsonArray {
                d.reasons.forEach { r ->
                    add(
                        buildJsonObject {
                            put("ruleId", r.ruleId)
                            put("text", r.text)
                            put("entryIds", buildJsonArray { r.entryIds.forEach { add(JsonPrimitive(it)) } })
                            put("amountMinor", r.amountMinor)
                        },
                    )
                }
            },
        )
    }
