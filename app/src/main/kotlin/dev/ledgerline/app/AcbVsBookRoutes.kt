package dev.ledgerline.app

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.BookValueComparison
import dev.ledgerline.calc.BookValueCsv
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DataGapWarnings
import dev.ledgerline.calc.HoldingRow
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reason
import dev.ledgerline.calc.fx.FxRateCache
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
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
) = acbVsBookRoute({ _ -> pools }, { _ -> emptyMap() }, bookValueCsv)

/** Same route over stored activities: pools come from the superficial-loss replay, so those reasons show. */
fun Application.acbVsBookFromActivities(
    activities: (taxpayer: String) -> List<PostedActivity>,
    bookValueCsv: String,
    fx: FxRateCache? = null,
) {
    val engine = AcbEngine(CaIta2026v1, fx ?: ProductionFx.cache())
    acbVsBookRoute(
        { taxpayer -> engine.replaySuperficial(activities(taxpayer)).pools },
        { taxpayer ->
            val acts = activities(taxpayer)
            DataGapWarnings.detect(acts, engine.replay(acts))
        },
        bookValueCsv,
    )
}

/** Same route over a live pool source, called per request with the taxpayer (the service replays stored activities). */
fun Application.acbVsBookModule(
    poolSource: (taxpayer: String) -> Map<PoolKey, AcbPool>,
    bookValueCsv: String,
) = acbVsBookRoute(poolSource, { _ -> emptyMap() }, bookValueCsv)

private fun Application.acbVsBookRoute(
    poolSource: (taxpayer: String) -> Map<PoolKey, AcbPool>,
    warningSource: (taxpayer: String) -> Map<PoolKey, List<Reason>>,
    bookValueCsv: String,
) {
    // module() installs it too in the live service; a second install would fail.
    if (pluginOrNull(ContentNegotiation) == null) install(ContentNegotiation) { json() }
    routing {
        get("/v1/holdings/{taxpayer}/acb-vs-book") {
            val taxpayer = call.parameters["taxpayer"].orEmpty()
            val pools = poolSource(taxpayer).filterKeys { it.taxpayerId == taxpayer }
            // The CSV names no taxpayer, so it is applied only to a taxpayer the ledger knows (an unknown one gets none).
            val warnings = warningSource(taxpayer).filterKeys { it.taxpayerId == taxpayer }
            val known = pools.isNotEmpty() || warnings.isNotEmpty()
            val diffs =
                if (known) {
                    BookValueComparison.compareAll(
                        pools,
                        BookValueCsv.parse(taxpayer, bookValueCsv),
                        warnings,
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

private fun holdingJson(d: HoldingRow): JsonObject =
    buildJsonObject {
        put("securityId", d.key.securityId)
        put("bookValueCents", JsonPrimitive(d.bookValueCents))
        put("acbCents", d.acbCents)
        put("differenceCents", JsonPrimitive(d.differenceCents))
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
