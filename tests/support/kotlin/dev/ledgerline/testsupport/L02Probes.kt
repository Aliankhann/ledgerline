package dev.ledgerline.testsupport

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.File
import java.util.concurrent.TimeUnit

/** Raw POST that never parses the response, so a 500 with a non-JSON body is still observable. */
suspend fun HttpClient.postRaw(
    body: ByteArray,
    path: String = "/v1/activities",
    type: ContentType = ContentType.Application.Json,
): Pair<HttpStatusCode, String> =
    post(path) {
        contentType(type)
        setBody(body)
    }.let { it.status to it.bodyAsText() }

suspend fun HttpClient.postRaw(
    body: String,
    path: String = "/v1/activities",
    type: ContentType = ContentType.Application.Json,
) = postRaw(body.toByteArray(Charsets.UTF_8), path, type)

/**
 * POSTs every case and returns one line per case that was not a 4xx with a JSON `code`. An exception that escapes the
 * route (the test host rethrows it; the real server turns it into a 500) counts as a failure, so one bad case does not
 * hide the others.
 */
suspend fun HttpClient.refusalFailures(
    cases: Map<String, ByteArray>,
    path: String = "/v1/activities",
): List<String> =
    cases.mapNotNull { (name, bytes) ->
        val r = runCatching { postRaw(bytes, path) }
        val ok = r.getOrNull()?.let { it.first.value in 400..499 && it.second.contains("\"code\"") } ?: false
        if (ok) {
            null
        } else {
            val shown =
                r.fold(
                    { "${it.first} ${it.second.take(80)}" },
                    { "${it::class.simpleName}: ${it.message?.take(100)}" },
                )
            "$name -> $shown"
        }
    }

/** Ledger tables stay consistent: one activity row and one outbox row per entry, and at least two lines per entry. */
fun LedgerDb.shouldBeConsistent() {
    val c = rowCounts()
    c.getValue("activity") shouldBe c.getValue("entry")
    c.getValue("outbox") shouldBe c.getValue("entry")
    (c.getValue("line") >= 2 * c.getValue("entry")) shouldBe true
}

/** Runs [HashProbe] in a fresh JVM with extra `-D` options and returns its stdout lines. */
fun runHashProbe(vararg jvmOptions: String): List<String> {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val classpath = System.getProperty("java.class.path")
    val cmd = listOf(java) + jvmOptions + listOf("-cp", classpath, "dev.ledgerline.testsupport.HashProbe")
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(120, TimeUnit.SECONDS)) { "probe timed out" }
    check(p.exitValue() == 0) { "probe failed with ${p.exitValue()}: $out" }
    return out.lines().filter { it.matches(Regex("[0-9a-f]{64}")) }
}
