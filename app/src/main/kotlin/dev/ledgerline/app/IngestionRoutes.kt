package dev.ledgerline.app

import dev.ledgerline.core.FixedPoint
import dev.ledgerline.core.Scale
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.FieldsResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP routes for story L-02. Bodies are read as raw text and parsed here, so a JSON number is never turned into a
 * floating-point value: amounts and quantities must be JSON strings.
 *
 * - `POST /v1/activities`: OWNER-INPUT: C22. 201 new, 200 duplicate with `alreadyExisted: true`, 409
 *   `IDEMPOTENCY_KEY_MISMATCH`, 422 `{code, field}` (L-01 codes plus ledger refusals).
 * - `GET /v1/activities/{idempotencyKey}`: the stored activity, 404 if unknown.
 * - `POST /v1/imports`: canonical CSV in, `{posted, duplicates, rejected, fileErrors}` out (OWNER-INPUT: C23).
 */
fun Application.ingestionModule(service: IngestionService) {
    routing {
        post("/v1/activities") {
            val text = call.receiveLimitedText(MAX_ACTIVITY_BYTES) ?: return@post call.respondTooLarge()
            val fields =
                parseBody(text) ?: return@post call.respondJson(
                    HttpStatusCode.BadRequest,
                    buildJsonObject {
                        put("code", "MALFORMED_JSON")
                        put("field", "")
                    },
                )
            when (val parsed = CanonicalCsv.parseFields(fields)) {
                is FieldsResult.Invalid -> call.respondInvalid(parsed.code, parsed.field)
                is FieldsResult.Valid ->
                    when (val result = service.submit(parsed.activity)) {
                        is SubmitResult.Accepted ->
                            call.respondJson(
                                if (result.alreadyExisted) HttpStatusCode.OK else HttpStatusCode.Created,
                                buildJsonObject {
                                    put("activityId", result.activityId)
                                    put("entryId", result.entryId)
                                    put("alreadyExisted", result.alreadyExisted)
                                },
                            )
                        is SubmitResult.KeyMismatch ->
                            call.respondJson(
                                HttpStatusCode.Conflict,
                                buildJsonObject {
                                    put("code", SubmitResult.KEY_MISMATCH)
                                    put("idempotencyKey", result.idempotencyKey)
                                },
                            )
                        is SubmitResult.Invalid -> call.respondInvalid(result.code, result.field.orEmpty())
                    }
            }
        }

        get("/v1/activities/{idempotencyKey}") {
            val key = requireNotNull(call.parameters["idempotencyKey"])
            // A key that breaks the L-01 pattern cannot exist; answer without touching the database.
            val stored = if (CanonicalCsv.isValidKey(key)) service.find(key) else null
            if (stored == null) {
                call.respondJson(HttpStatusCode.NotFound, buildJsonObject { put("code", "NOT_FOUND") })
            } else {
                call.respondJson(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put("idempotencyKey", stored.activity.idempotencyKey)
                        put("activityId", stored.activityId)
                        put("entryId", stored.entryId)
                        put(
                            "amount",
                            FixedPoint.format(stored.activity.amountMinor, Scale.MONEY_DECIMALS, minimal = false),
                        )
                        put("recordedAt", stored.recordedAt.toString())
                    },
                )
            }
        }

        post("/v1/imports") {
            val text = call.receiveLimitedText(MAX_IMPORT_BYTES) ?: return@post call.respondTooLarge()
            val report = service.importCsv(text)
            call.respondJson(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("posted", report.posted)
                    put("duplicates", report.duplicates)
                    put(
                        "rejected",
                        buildJsonArray {
                            report.rejected.forEach {
                                add(
                                    buildJsonObject {
                                        put("line", it.line)
                                        put("code", it.code)
                                        put("field", it.field)
                                        put("message", it.message)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "fileErrors",
                        buildJsonArray {
                            report.fileErrors.forEach {
                                add(
                                    buildJsonObject {
                                        put("code", it.code)
                                        put("message", it.message)
                                        put("column", it.column)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }
}

private const val MAX_ACTIVITY_BYTES = 4L * 1024 * 1024
internal const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
private const val MAX_JSON_DEPTH = 32

/** The request body as text, or null if it is larger than [limit] bytes (checked on the header and on the text). */
internal suspend fun ApplicationCall.receiveLimitedText(limit: Long): String? {
    if ((request.contentLength() ?: 0L) > limit) return null
    val text = receiveText()
    return if (text.length > limit) null else text
}

internal suspend fun ApplicationCall.respondTooLarge() =
    respondJson(
        HttpStatusCode.PayloadTooLarge,
        buildJsonObject {
            put("code", "PAYLOAD_TOO_LARGE")
            put("field", "")
        },
    )

/** True if brackets or braces outside strings nest deeper than [MAX_JSON_DEPTH]. */
private fun nestsTooDeep(body: String): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in body) {
        when {
            inString -> {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> if (++depth > MAX_JSON_DEPTH) return true
            c == '}' || c == ']' -> depth--
        }
    }
    return false
}

internal suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: JsonObject,
) = respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondInvalid(
    code: String,
    field: String,
) = respondJson(
    HttpStatusCode.UnprocessableEntity,
    buildJsonObject {
        put("code", code)
        put("field", field)
    },
)

/**
 * The canonical columns (snake_case) from a camelCase JSON object, as text. A value that is not a JSON string is kept
 * as its raw text, so even a JSON number is parsed by the exact decimal rules and never becomes a double; an object or
 * array fails those rules. Returns null if [body] is not a JSON object.
 */
private fun parseBody(body: String): Map<String, String>? {
    if (nestsTooDeep(body)) return null
    val root: JsonElement =
        try {
            Json.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {
            return null
        }
    val obj = root as? JsonObject ?: return null
    val fields = CanonicalCsv.COLUMNS.associateWith { "" }.toMutableMap()
    obj.forEach { (name, value) ->
        val column = name.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }
        if (column in fields && value !is JsonNull) {
            val primitive = value as? JsonPrimitive
            fields[column] = if (primitive != null && primitive.isString) primitive.content else value.toString()
        }
    }
    return fields
}
