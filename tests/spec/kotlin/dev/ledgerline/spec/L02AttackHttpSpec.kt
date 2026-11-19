package dev.ledgerline.spec

import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Json
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.flag
import dev.ledgerline.testsupport.postJson
import dev.ledgerline.testsupport.postRaw
import dev.ledgerline.testsupport.refusalFailures
import dev.ledgerline.testsupport.shouldBeConsistent
import dev.ledgerline.testsupport.str
import dev.ledgerline.testsupport.withL02Api
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * L-02 phase B attacks on the HTTP surface: lookalike and over-long keys, malformed JSON, hostile text, number edge
 * cases. Common rule: a bad request is a 4xx with a machine-readable body and writes nothing; it is never a 5xx.
 * (Crash, privilege, concurrency and bulk attacks are in `L02AttackLedgerSpec`.)
 */
class L02AttackHttpSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val key = "TP-0001-2025-0002"

    fun u(s: String) = s

    test(
        "L-02 attack: lookalike keys (Cyrillic, fullwidth, NFD, Kelvin, zero-width) are refused, never collide",
    )
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                http.postJson(L02Json.body()).first shouldBe HttpStatusCode.Created
                val before = e.db.snapshot()
                val lookalikes =
                    mapOf(
                        "Cyrillic Te for T" to u("ТP-0001-2025-0002"),
                        "fullwidth zero" to u("TP-0001-2025-０002"),
                        "hyphen U+2010" to u("TP‐0001-2025-0002"),
                        "minus U+2212" to u("TP−0001-2025-0002"),
                        "zero-width space inside" to u("TP-0001-​2025-0002"),
                        "zero-width space after" to u("TP-0001-2025-0002​"),
                        "Kelvin sign for K" to u("K-0001"),
                        "e + combining acute (NFD)" to u("K-é"),
                        "e acute (NFC)" to u("K-é"),
                        "dotted capital I" to u("İ-1"),
                        "newline inside" to "TP-0001\\n2025-0002",
                        "NUL inside" to "TP-0001\\u00002025-0002",
                    )
                lookalikes.forEach { (name, k) ->
                    withClue("lookalike: $name") {
                        val (status, body) = http.postJson(L02Json.body("idempotencyKey" to k))
                        status shouldBe HttpStatusCode.UnprocessableEntity
                        body.str("code") shouldBe "INVALID_IDEMPOTENCY_KEY"
                    }
                }
                e.db.snapshot() shouldBe before
                // Padding is trimmed by L-01, so it names the same key: a duplicate of the original, never a new row.
                listOf(" $key", "$key ", "$key\\n", "$key\\t").forEach { k ->
                    withClue("padded key '$k'") {
                        val (status, body) = http.postJson(L02Json.body("idempotencyKey" to k))
                        status shouldBe HttpStatusCode.OK
                        body.flag() shouldBe true
                    }
                }
                e.db.snapshot() shouldBe before
                http.get("/v1/activities/${u("ТP-0001-2025-0002")}").status shouldBe HttpStatusCode.NotFound
                e.db.shouldBeConsistent()
            }
        }

    test("L-02 attack: key length and charset limits: 64 characters post, 65 and hostile characters are 422")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val k64 = "K" + "a".repeat(63)
                http.postJson(L02Json.body("idempotencyKey" to k64)).first shouldBe HttpStatusCode.Created
                http.get("/v1/activities/$k64").status shouldBe HttpStatusCode.OK
                http.postJson(L02Json.body("idempotencyKey" to "A._:-9")).first shouldBe HttpStatusCode.Created
                val before = e.db.snapshot()
                val refused =
                    mapOf(
                        "65 characters" to k64 + "a",
                        "10000 characters" to "K".repeat(10_000),
                        "slash" to "A/B", "space inside" to "A B", "percent" to "A%2FB", "quote" to "A'--",
                        "semicolon" to "A;DROP TABLE ledger.entry", "backslash" to "A\\\\B", "unicode digit" to "A٣",
                    )
                refused.forEach { (name, k) ->
                    withClue("key $name") {
                        val (status, body) = http.postJson(L02Json.body("idempotencyKey" to k))
                        status shouldBe HttpStatusCode.UnprocessableEntity
                        body.str("code") shouldBe "INVALID_IDEMPOTENCY_KEY"
                    }
                }
                http.postJson(L02Json.body("idempotencyKey" to "")).second.str("code") shouldBe "MISSING_FIELD"
                http.postJson(L02Json.body("idempotencyKey" to null)).second.str("code") shouldBe "MISSING_FIELD"
                e.db.snapshot() shouldBe before
                // lookups with hostile path segments are a 404, never a 500
                listOf("A%2FB", "%E2%80%8B", "K".repeat(5000), "..%2F..%2Fetc", "A%27--").forEach { k ->
                    withClue("GET $k") { http.get("/v1/activities/$k").status shouldBe HttpStatusCode.NotFound }
                }
                e.db.shouldBeConsistent()
            }
        }

    test("L-02 attack: malformed JSON and wrong shapes are 4xx with a code, write nothing and never 500")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val empty = e.db.snapshot()
                val good = L02Json.body()
                val bodies: Map<String, ByteArray> =
                    mapOf(
                        "empty" to ByteArray(0),
                        "whitespace" to "  \n".toByteArray(),
                        "brace" to "{".toByteArray(),
                        "truncated" to good.dropLast(5).toByteArray(),
                        "trailing garbage" to (good + "xyz").toByteArray(),
                        "two documents" to (good + good).toByteArray(),
                        "null" to "null".toByteArray(),
                        "array" to "[]".toByteArray(),
                        "string" to "\"x\"".toByteArray(),
                        "number" to "123".toByteArray(),
                        "NaN value" to "{\"amount\": NaN}".toByteArray(),
                        "single quotes" to "{'a':'b'}".toByteArray(),
                        "trailing comma" to good.replace("}", ",}").toByteArray(),
                        "invalid UTF-8" to byteArrayOf(0x7B, -1, -2, 0x7D),
                        "binary" to ByteArray(2048) { (it * 31).toByte() },
                        "BOM + json" to byteArrayOf(-17, -69, -65) + good.toByteArray(),
                        "object for amount" to good.replace("\"3012.50\"", "{\"x\":1}").toByteArray(),
                        "array for key" to good.replace("\"TP-0001-2025-0002\"", "[1]").toByteArray(),
                    )
                http.refusalFailures(bodies) shouldBe emptyList()
                e.db.snapshot() shouldBe empty
                http.postJson(good).first shouldBe HttpStatusCode.Created
            }
        }

    test("L-02 attack (B-014): extremely deep JSON nesting is a 4xx, not an escaped StackOverflowError")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val deep =
                    mapOf(
                        "200k nested arrays" to "[".repeat(200_000).toByteArray(),
                        "100k nested objects" to "{\"a\":".repeat(100_000).toByteArray(),
                        "10k nested arrays in a field" to
                            L02Json.body("amount" to "@@").replace("\"@@\"", "[".repeat(10_000)).toByteArray(),
                    )
                http.refusalFailures(deep) shouldBe emptyList()
                e.db.shouldBeConsistent()
            }
        }

    test("L-02 attack (B-013): GET with an encoded NUL in the key is a 404, not a 500")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { _, http ->
                listOf("A%00B", "%00", "$key%00").forEach { k ->
                    withClue(
                        "GET $k",
                    ) {
                        val status = runCatching { http.get("/v1/activities/$k").status }.getOrNull()
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

    test("L-02 attack (B-012): NUL in a free-text field is a 4xx with a code and writes nothing")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val empty = e.db.snapshot()
                val cases =
                    listOf("accountId", "taxpayerId", "securityId", "description").associate { f ->
                        "$f with NUL" to L02Json.body("idempotencyKey" to "H-NUL-$f", f to "X\\u0000Y").toByteArray()
                    }
                http.refusalFailures(cases) shouldBe emptyList()
                e.db.snapshot() shouldBe empty
            }
        }

    test("L-02 attack: lone surrogates, control characters and a 1 MB description are not a 500")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                val failures = mutableListOf<String>()
                listOf("accountId", "taxpayerId", "securityId", "description").forEach { f ->
                    mapOf("lone surrogate" to "X\\ud800Y", "control chars" to "X\\u0001\\u001fY").forEach { (name, v) ->
                        val k = "H-$f-${name.take(3)}"
                        val r = runCatching { http.postRaw(L02Json.body("idempotencyKey" to k, f to v)) }
                        if (r.getOrNull()?.first?.value?.let { it < 500 } != true) failures += "$f with $name -> $r"
                    }
                }
                val big = L02Json.body("idempotencyKey" to "H-BIG", "description" to "d".repeat(1_000_000))
                val r = runCatching { http.postRaw(big) }
                if (r.getOrNull()?.first?.value?.let { it < 500 } != true) failures += "1 MB description -> $r"
                failures shouldBe emptyList()
                e.db.shouldBeConsistent()
            }
        }

    test("L-02 attack: number edge cases (huge, tiny, JSON numbers, non-ASCII digits) are exact or 422, never rounded")
        .config(tags = setOf(Integration)) {
            pg.withL02Api { e, http ->
                http.postJson(L02Json.body()).first shouldBe HttpStatusCode.Created
                val before = e.db.snapshot()

                fun raw(
                    field: String,
                    json: String,
                ) = L02Json.body(field to "@@").replace("\"@@\"", json)
                val refused =
                    mapOf(
                        "amount 2^63-1 cents" to raw("amount", "\"92233720368547758.07\""),
                        "amount 19 digits" to raw("amount", "\"92233720368547758.08\""),
                        "amount 100k digits" to raw("amount", "\"" + "9".repeat(100_000) + "\""),
                        "amount 0.001" to raw("amount", "\"0.001\""),
                        "amount exponent" to raw("amount", "\"1e3\""),
                        "amount JSON exponent" to raw("amount", "1E3"),
                        "amount hex" to raw("amount", "\"0x10\""),
                        "amount Arabic-Indic digits" to raw("amount", "\"٣٠١٢.٥٠\""),
                        "amount fullwidth digits" to raw("amount", "\"３０１２.５０\""),
                        "amount JSON number needing rounding" to raw("amount", "3012.5000000000000000001"),
                        "quantity 1e-7 share" to raw("quantity", "0.0000001"),
                        "quantity true" to raw("quantity", "true"),
                        "commission negative" to raw("commission", "\"-0.01\""),
                    )
                refused.forEach { (name, body) ->
                    withClue("number case: $name") {
                        val (status, text) = http.postRaw(body)
                        withClue(text) { (status.value in 400..499) shouldBe true }
                        text.contains("\"code\"") shouldBe true
                    }
                }
                e.db.snapshot() shouldBe before
                // Exact JSON numbers are the same decimal, so they are the same payload (no float round trip).
                http.postRaw(raw("amount", "3012.50")).first shouldBe HttpStatusCode.OK
                http.postRaw(raw("amount", "3012.5")).first shouldBe HttpStatusCode.OK
                http.postRaw(raw("amount", "3012.51")).first shouldBe HttpStatusCode.Conflict
                // The largest amount that fits (18 digits of cents) with the largest commission posts without overflow.
                val max = "9999999999999999.99"
                val (status, text) =
                    http.postRaw(L02Json.body("idempotencyKey" to "MAX-1", "amount" to max, "commission" to max))
                withClue(text) { (status.value < 500) shouldBe true }
                e.db.shouldBeConsistent()
                http.get("/v1/activities/$key").bodyAsText().contains("3012.50") shouldBe true
            }
        }
})
