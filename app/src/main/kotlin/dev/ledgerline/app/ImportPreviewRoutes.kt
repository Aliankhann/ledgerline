package dev.ledgerline.app

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP routes for story L-20. Install next to [ingestionModule]; neither changes the other's routes.
 *
 * - `POST /v1/imports/preview`: raw CSV in, the classification of every row out. Writes nothing.
 * - `POST /v1/imports/confirm?fileSha256=...[&previewNew=2,3]`: the same file again plus the hash the preview gave.
 *   OWNER-INPUT: L-20 stateless confirm: the server keeps no preview, it re-evaluates the file against the current
 *   ledger through [IngestionService.confirm]. `previewNew` (the lines the client saw as NEW) only drives the
 *   `changedSincePreview` flag and `changedRows`; it never decides what is posted.
 */
fun Application.importPreviewModule(service: IngestionService) {
    routing {
        post("/v1/imports/preview") {
            val text = call.receiveLimitedText(MAX_IMPORT_BYTES) ?: return@post call.respondTooLarge()
            call.respondJson(HttpStatusCode.OK, reportJson(service.preview(text)))
        }

        post("/v1/imports/confirm") {
            val sha = call.request.queryParameters["fileSha256"]
            if (sha.isNullOrBlank()) return@post call.respondCode(HttpStatusCode.BadRequest, "MISSING_FILE_SHA256")
            val previewNew =
                call.request.queryParameters["previewNew"]?.let { raw ->
                    raw.split(',').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() }
                        .takeIf { list -> list.none { it == null } }?.filterNotNull()
                        ?: return@post call.respondCode(HttpStatusCode.BadRequest, "INVALID_PREVIEW_NEW")
                }
            val text = call.receiveLimitedText(MAX_IMPORT_BYTES) ?: return@post call.respondTooLarge()
            if (!sha.equals(ImportPreview.sha256(text), ignoreCase = true)) {
                return@post call.respondCode(HttpStatusCode.Conflict, "PREVIEW_MISMATCH")
            }
            val report = service.confirm(text)
            call.respondJson(
                HttpStatusCode.OK,
                JsonObject(reportJson(report) + confirmJson(report, previewNew)),
            )
        }
    }
}

private suspend fun ApplicationCall.respondCode(
    status: HttpStatusCode,
    code: String,
) = respondJson(status, buildJsonObject { put("code", code) })

/** `posted`, `changedSincePreview` and `changedRows`; without [previewNew] nothing is known to have changed. */
private fun confirmJson(
    report: PreviewReport,
    previewNew: List<Int>?,
): JsonObject {
    val nowNew = report.rows.filter { it.status == PreviewStatus.NEW }.map { it.line }.toSet()
    val byLine = report.rows.associateBy { it.line }
    val changed =
        previewNew.orEmpty().distinct().sorted().filter { it !in nowNew }
    return buildJsonObject {
        put("posted", report.new)
        put("changedSincePreview", previewNew != null && previewNew.toSet() != nowNew)
        put(
            "changedRows",
            buildJsonArray {
                changed.forEach { line ->
                    add(
                        buildJsonObject {
                            put("line", line)
                            put("previewStatus", PreviewStatus.NEW.name)
                            put("status", byLine[line]?.status?.name ?: PreviewStatus.REJECTED.name)
                        },
                    )
                }
            },
        )
    }
}

private fun reportJson(r: PreviewReport): JsonObject =
    buildJsonObject {
        put("fileSha256", r.fileSha256)
        put("rowsRead", r.rowsRead)
        put("new", r.new)
        put("duplicates", r.duplicates)
        put("conflicts", r.conflicts)
        put("rejected", r.rejected)
        put("rows", JsonArray(r.rows.map { rowJson(it) }))
        put(
            "warnings",
            buildJsonArray {
                r.warnings.forEach { w ->
                    add(
                        buildJsonObject {
                            put("ruleId", w.ruleId)
                            put("securityId", w.securityId)
                            put("text", w.text)
                            put("lines", JsonArray(w.lines.map { JsonPrimitive(it) }))
                            put("amountMinor", w.amountMinor)
                        },
                    )
                }
            },
        )
        put(
            "fileErrors",
            buildJsonArray {
                r.fileErrors.forEach {
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
    }

private fun rowJson(r: PreviewRow): JsonObject =
    buildJsonObject {
        put("line", r.line)
        put("key", r.key)
        put("type", r.type)
        put("date", r.date)
        put("status", r.status.name)
        put("code", r.code)
        put("field", r.field)
        put("message", r.message)
    }
