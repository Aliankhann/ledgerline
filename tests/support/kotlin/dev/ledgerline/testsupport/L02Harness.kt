package dev.ledgerline.testsupport

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.ingestionModule
import dev.ledgerline.ledger.LedgerRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** One migrated database with an [IngestionService] on it, for L-02 specs. Needs Docker. */
class L02Env(val db: LedgerDb, val clock: MutableClock, val service: IngestionService) {
    val repo = LedgerRepository(db.app)

    fun count(table: String): Long = db.rowCounts().getValue(table)

    /** CAD movement of TP-0001-NR1, from the ledger lines. */
    fun cadBalance(): Long = LineMath.delta(repo.entries().flatMap { it.lines }, "TP-0001-NR1", "CAD")

    companion object {
        val T1: Instant = Instant.parse("2025-01-07T15:00:00Z")
        val T2: Instant = Instant.parse("2025-01-08T09:30:00Z")
    }
}

fun PostgresLedger.l02Env(): L02Env {
    val db = freshDatabase()
    val clock = MutableClock(L02Env.T1)
    return L02Env(db, clock, IngestionService(LedgerRepository(db.app), clock))
}

/** Runs [block] against the HTTP routes of a fresh ledger. */
suspend fun PostgresLedger.withL02Api(block: suspend ApplicationTestBuilder.(L02Env, HttpClient) -> Unit) {
    val e = l02Env()
    testApplication {
        application { ingestionModule(e.service) }
        block(e, client)
    }
}

suspend fun HttpClient.postJson(
    body: String,
    path: String = "/v1/activities",
): Pair<HttpStatusCode, JsonObject> =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }.let { it.status to Json.parseToJsonElement(it.bodyAsText()).jsonObject }

suspend fun HttpClient.postCsv(text: String): Pair<HttpStatusCode, JsonObject> =
    post("/v1/imports") {
        contentType(ContentType.Text.CSV)
        setBody(text)
    }.let { it.status to Json.parseToJsonElement(it.bodyAsText()).jsonObject }

fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

fun JsonObject.flag(): Boolean = getValue("alreadyExisted").jsonPrimitive.boolean

fun JsonObject.rejections(): List<Pair<Int, String>> =
    getValue(
        "rejected",
    ).jsonArray.map { it.jsonObject.getValue("line").jsonPrimitive.int to it.jsonObject.str("code")!! }
