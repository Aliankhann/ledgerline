package dev.ledgerline.spec

import dev.ledgerline.app.SubmitResult
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Activities
import dev.ledgerline.testsupport.L02Env
import dev.ledgerline.testsupport.L02Json
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.flag
import dev.ledgerline.testsupport.l02Env
import dev.ledgerline.testsupport.postJson
import dev.ledgerline.testsupport.str
import dev.ledgerline.testsupport.withL02Api
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spec for story L-02 (docs/stories/L-02-duplicate-safe-ingestion.md), written provisionally while the story is
 * Draft, on the owner's instruction. Invariant 2 (Idempotency); the property test is
 * `dev.ledgerline.property.IdempotencyInvariantPropertyTest` (AC11).
 *
 * PLACEHOLDERS (OWNER-INPUT, Dev adds them to docs/owner-inputs.md). Where they differ from the story's interface
 * proposal, the placeholder wins:
 * - C20: the payload hash is SHA-256 over a canonical serialization of every Activity field except `recorded_at` and
 *   `description`. `sourceSeq` IS therefore in the hash, so `importCsv` must not derive `sourceSeq` from row position
 *   in a way that makes two identical rows in one file differ (C24).
 * - C21: an invalid submission does not consume its key.
 * - C22: a duplicate (same key, same hash) returns the original ids with `alreadyExisted=true`. HTTP: 201 new, 200
 *   duplicate, 409 mismatch with code `IDEMPOTENCY_KEY_MISMATCH` (the story says `IDEMPOTENCY_KEY_REUSED`; the story's
 *   `status` field is replaced by the `alreadyExisted` flag).
 * - C23: a CSV import is partial: good rows post, bad rows and key mismatches are reported per line.
 * - C24: a duplicate key inside one file is a mismatch if the payloads differ, a duplicate if they are identical.
 * - C15 (existing): a zero-amount cash activity is refused at posting with `ZERO_AMOUNT`. Used here as the
 *   service-level invalid submission, because an `Activity` object cannot hold an unsupported currency.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, the owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.ledger
 * object PayloadHash { fun of(activity: Activity): String }   // 64 lowercase hex characters (SHA-256), C20
 *
 * package dev.ledgerline.app
 * sealed interface SubmitResult {
 *     // New: alreadyExisted=false. Duplicate: alreadyExisted=true and the ORIGINAL activityId and entryId.
 *     data class Accepted(val activityId: String, val entryId: Long, val alreadyExisted: Boolean) : SubmitResult
 *     data class KeyMismatch(val idempotencyKey: String) : SubmitResult        // code IDEMPOTENCY_KEY_MISMATCH
 *     data class Invalid(val code: String, val field: String?) : SubmitResult  // key not consumed (C21)
 * }
 * data class StoredActivity(val activityId: String, val entryId: Long, val activity: Activity, val recordedAt: Instant)
 * data class ImportReport(
 *     val posted: Int, val duplicates: Int,
 *     val rejected: List<dev.ledgerline.ingest.Rejection>,   // parse rejections and key mismatches (line = physical line)
 *     val fileErrors: List<dev.ledgerline.ingest.FileError>,
 * )
 * class IngestionService(ledger: LedgerRepository, clock: java.time.Clock = Clock.systemUTC()) {
 *     fun submit(activity: Activity): SubmitResult    // thread-safe; one entry and one ledger.outbox row per new key
 *     fun importCsv(text: String): ImportReport       // CanonicalCsv.parse, then submit each good row in file order
 *     fun find(idempotencyKey: String): StoredActivity?   // recordedAt = clock instant of the FIRST accepted submit
 * }
 * fun io.ktor.server.application.Application.ingestionModule(service: IngestionService)
 * ```
 *
 * HTTP (installed by `ingestionModule`; bodies are read as raw text, so tests send JSON as `application/json`):
 * - `POST /v1/activities`, body = canonical activity JSON (fields of [L02Json.a1]; amounts, quantities as decimal
 *   strings; `description` optional). 201 `{"activityId","entryId","alreadyExisted":false}`; 200 same with
 *   `alreadyExisted:true` and the original ids; 409 `{"code":"IDEMPOTENCY_KEY_MISMATCH","idempotencyKey"}`;
 *   422 `{"code","field"}` with the L-01 codes (`UNSUPPORTED_CURRENCY`, field `currency`).
 * - `GET /v1/activities/{idempotencyKey}`: 200 `{"idempotencyKey","activityId","entryId","amount":"3012.50",
 *   "recordedAt":"<ISO-8601 instant>"}`, 404 if unknown. `amount` is rendered with exactly two decimals.
 * - `POST /v1/imports`, body = canonical CSV text (`text/csv`): 200 `{"posted":n,"duplicates":n,
 *   "rejected":[{"line":n,"code":"..."}]}`; extra keys in a rejected object (`field`, `message`) are allowed.
 *
 * Schema: the migrations add `ledger.outbox` (one row per newly posted entry; none for a duplicate, mismatch or
 * invalid submission; L-05 extends it). Tests count `ledger.entry`, `ledger.line`, `ledger.outbox` and compare
 * whole-schema snapshots before and after.
 *
 * Line numbers are physical with the header as line 1 (L-01): the BUY row AC10 changes is line 3.
 */
class L02IngestionSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    val key = L02Activities.KEY
    val a1 = L02Activities.a1
    val t1 = L02Env.T1
    val t2 = L02Env.T2

    fun env() = pg.l02Env()

    fun accepted(r: SubmitResult): SubmitResult.Accepted =
        (r as? SubmitResult.Accepted) ?: throw AssertionError("expected Accepted but was $r")

    suspend fun withApi(block: suspend ApplicationTestBuilder.(L02Env, HttpClient) -> Unit) = pg.withL02Api(block)

    // ---------- AC1 - AC3: the service ----------

    test("L-02 AC1: first submission posts one entry and one outbox row")
        .config(tags = setOf(Integration)) {
            val e = env()
            val r = accepted(e.service.submit(a1))
            r.alreadyExisted shouldBe false
            e.count("entry") shouldBe 1
            e.count("outbox") shouldBe 1
            e.repo.entries().map { it.idempotencyKey } shouldContainExactly listOf(key)
            e.count("line") shouldBe e.repo.entries().single().lines.size.toLong()
        }

    test(
        "L-02 AC2: identical resubmission is a no-op returning the original ids; nothing changes",
    )
        .config(tags = setOf(Integration)) {
            val e = env()
            val first = accepted(e.service.submit(a1))
            val before = e.db.snapshot()
            val cash = e.cadBalance()
            val again = accepted(e.service.submit(a1))
            again shouldBe first.copy(alreadyExisted = true)
            e.db.snapshot() shouldBe before
            e.cadBalance() shouldBe cash
            cash shouldBe -301_745L
        }

    test(
        "L-02 AC3: same key with amount 3021.50 is rejected with the key; nothing written",
    )
        .config(tags = setOf(Integration)) {
            val e = env()
            e.service.submit(a1)
            val before = e.db.snapshot()
            e.service.submit(a1.copy(amountMinor = 302_150L)) shouldBe SubmitResult.KeyMismatch(key)
            e.db.snapshot() shouldBe before
            e.service.find(key)!!.activity.amountMinor shouldBe 301_250L
        }

    test(
        "L-02 AC3 (C20): any hashed field changed is a mismatch; description alone is a duplicate",
    )
        .config(tags = setOf(Integration)) {
            val e = env()
            val first = accepted(e.service.submit(a1))
            val before = e.db.snapshot()
            val changed: Map<String, Activity> =
                mapOf(
                    "taxpayerId" to a1.copy(taxpayerId = "TP-0002"),
                    "accountId" to a1.copy(accountId = "TP-0001-NR2"),
                    "accountType" to a1.copy(accountType = AccountType.TFSA),
                    "effectiveDate" to a1.copy(effectiveDate = a1.effectiveDate.plusDays(1)),
                    "settlementDate" to a1.copy(settlementDate = null),
                    "securityId" to a1.copy(securityId = "SYN-BBB"),
                    "quantity (1 unit)" to a1.copy(quantity = a1.quantity!! + 1),
                    "currency" to a1.copy(currency = Currency.USD),
                    "amount (1 cent)" to a1.copy(amountMinor = a1.amountMinor + 1),
                    "commission (1 cent)" to a1.copy(commissionMinor = a1.commissionMinor + 1),
                    "sourceSeq" to a1.copy(sourceSeq = a1.sourceSeq + 1),
                )
            changed.forEach { (field, a) ->
                withClue("changed $field") { e.service.submit(a) shouldBe SubmitResult.KeyMismatch(key) }
            }
            e.db.snapshot() shouldBe before
            accepted(e.service.submit(a1.copy(description = "typo fixed"))) shouldBe first.copy(alreadyExisted = true)
            e.db.snapshot() shouldBe before
        }

    test("L-02 AC5: the second submission at a later time is a duplicate and recorded_at stays the first call's time")
        .config(tags = setOf(Integration)) {
            val e = env()
            e.clock.now = t1
            e.service.submit(a1)
            e.clock.now = t2
            accepted(e.service.submit(a1)).alreadyExisted shouldBe true
            e.service.find(key)!!.recordedAt shouldBe t1
        }

    test("L-02 AC6: 32 threads submitting the same key through the service give exactly one entry and one outbox row")
        .config(tags = setOf(Integration)) {
            val e = env()
            val threads = 32
            val start = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val results =
                    (1..threads).map {
                        pool.submit<SubmitResult> {
                            start.await()
                            e.service.submit(a1)
                        }
                    }
                        .map { it.get(60, TimeUnit.SECONDS) }
                results.map { accepted(it).alreadyExisted }.count { !it } shouldBe 1
                results.map { accepted(it).entryId }.toSet() shouldHaveSize 1
                e.count("entry") shouldBe 1
                e.count("outbox") shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

    test("L-02 AC7: 16 threads submitting 16 different payloads under one key: one wins, 15 are mismatches")
        .config(tags = setOf(Integration)) {
            val e = env()
            val threads = 16
            val start = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                val variants = (0 until threads).map { a1.copy(amountMinor = 301_250L + it) }
                val results =
                    variants.map { v ->
                        pool.submit<SubmitResult> {
                            start.await()
                            e.service.submit(v)
                        }
                    }
                        .map { it.get(60, TimeUnit.SECONDS) }
                val winners = variants.zip(results).filter { (_, r) -> r is SubmitResult.Accepted }
                winners shouldHaveSize 1
                results.count { it == SubmitResult.KeyMismatch(key) } shouldBe threads - 1
                e.service.find(key)!!.activity.amountMinor shouldBe winners.single().first.amountMinor
                e.count("entry") shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }

    test("L-02 AC8 (C21): a refused zero-amount deposit writes nothing and leaves its key free for a valid one")
        .config(tags = setOf(Integration)) {
            val e = env()
            val empty = e.db.snapshot()
            val bad = a1.copy(activityType = ActivityType.DEPOSIT, securityId = null, quantity = null, amountMinor = 0L)
            val refused = e.service.submit(bad)
            (refused as SubmitResult.Invalid).code shouldBe "ZERO_AMOUNT"
            e.db.snapshot() shouldBe empty
            accepted(e.service.submit(bad.copy(amountMinor = 100L))).alreadyExisted shouldBe false
            e.count("entry") shouldBe 1
        }

    test("L-02 AC1: POST /v1/activities with A1 on an empty ledger is 201 with one entry and one outbox row")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val (status, body) = http.postJson(L02Json.body())
                status shouldBe HttpStatusCode.Created
                body.flag() shouldBe false
                body.str("activityId") shouldNotBe null
                body.str("entryId") shouldMatch Regex("\\d+")
                e.count("entry") shouldBe 1
                e.count("outbox") shouldBe 1
            }
        }

    test("L-02 AC2: the same body again is 200 with alreadyExisted=true, the same ids, and nothing changed")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val (_, first) = http.postJson(L02Json.body())
                val before = e.db.snapshot()
                val cash = e.cadBalance()
                val (status, again) = http.postJson(L02Json.body())
                status shouldBe HttpStatusCode.OK
                again.flag() shouldBe true
                again.str("activityId") shouldBe first.str("activityId")
                again.str("entryId") shouldBe first.str("entryId")
                e.db.snapshot() shouldBe before
                e.cadBalance() shouldBe cash
            }
        }

    test("L-02 AC3: amount 3021.50 under the used key is 409 IDEMPOTENCY_KEY_MISMATCH; GET still shows 3012.50")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                http.postJson(L02Json.body())
                val before = e.db.snapshot()
                val (status, body) = http.postJson(L02Json.body("amount" to "3021.50"))
                status shouldBe HttpStatusCode.Conflict
                body.str("code") shouldBe "IDEMPOTENCY_KEY_MISMATCH"
                body.str("idempotencyKey") shouldBe key
                e.db.snapshot() shouldBe before
                val stored = Json.parseToJsonElement(http.get("/v1/activities/$key").bodyAsText()).jsonObject
                stored.str("amount") shouldBe "3012.50"
            }
        }

    test("L-02 AC4: reversed field order, extra whitespace, amount 3012.5 and quantity 100.000000 are all duplicates")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                http.postJson(L02Json.body())
                val before = e.db.snapshot()
                val allFour =
                    L02Json.body("amount" to "3012.5", "quantity" to "100.0", reversed = true, spaced = true)
                val variants =
                    mapOf(
                        "reversed" to L02Json.body(reversed = true),
                        "whitespace" to L02Json.body(spaced = true),
                        "amount 3012.5" to L02Json.body("amount" to "3012.5"),
                        "quantity 100.000000" to L02Json.body("quantity" to "100.000000"),
                        "all four" to allFour,
                    )
                variants.forEach { (name, body) ->
                    withClue(name) {
                        val (status, resp) = http.postJson(body)
                        status shouldBe HttpStatusCode.OK
                        resp.flag() shouldBe true
                    }
                }
                e.db.snapshot() shouldBe before
                // One unit of the 10^-6 share scale is a real change, not an encoding.
                http.postJson(L02Json.body("quantity" to "100.000001")).first shouldBe HttpStatusCode.Conflict
            }
        }

    test(
        "L-02 AC5: A1 resubmitted a day later is a duplicate and recorded_at is the first call's time",
    )
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                e.clock.now = t1
                http.postJson(L02Json.body())
                e.clock.now = t2
                http.postJson(L02Json.body()).first shouldBe HttpStatusCode.OK
                val stored = Json.parseToJsonElement(http.get("/v1/activities/$key").bodyAsText()).jsonObject
                Instant.parse(stored.str("recordedAt")!!) shouldBe t1
            }
        }

    test("L-02 AC6: 20 concurrent POSTs of A1 give one 201, nineteen 200 duplicates, one entry, one outbox row")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val results =
                    coroutineScope {
                        (1..20).map { async(Dispatchers.IO) { http.postJson(L02Json.body()) } }.awaitAll()
                    }
                results.count { it.first == HttpStatusCode.Created } shouldBe 1
                results.count { it.first == HttpStatusCode.OK && it.second.flag() } shouldBe 19
                results.map { it.second.str("entryId") }.toSet() shouldHaveSize 1
                e.count("entry") shouldBe 1
                e.count("outbox") shouldBe 1
            }
        }

    test(
        "L-02 AC7: 10 concurrent POSTs, amounts 3012.50 .. 3012.59: one 201, nine 409, winner stored",
    )
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val amounts = (0..9).map { "3012.5$it" }
                val results =
                    coroutineScope {
                        amounts.map {
                                a ->
                            async(Dispatchers.IO) { a to http.postJson(L02Json.body("amount" to a)) }
                        }.awaitAll()
                    }
                val winners = results.filter { it.second.first == HttpStatusCode.Created }
                winners shouldHaveSize 1
                results.filter { it.second.first == HttpStatusCode.Conflict }.forEach {
                    it.second.second.str("code") shouldBe "IDEMPOTENCY_KEY_MISMATCH"
                }
                results.count { it.second.first == HttpStatusCode.Conflict } shouldBe 9
                val stored = Json.parseToJsonElement(http.get("/v1/activities/$key").bodyAsText()).jsonObject
                stored.str("amount") shouldBe winners.single().first
                e.count("entry") shouldBe 1
            }
        }

    test("L-02 AC8: currency EUR is 422 UNSUPPORTED_CURRENCY on field currency with no rows; A1 as given is then 201")
        .config(tags = setOf(Integration)) {
            withApi { e, http ->
                val empty = e.db.snapshot()
                val (status, body) = http.postJson(L02Json.body("currency" to "EUR"))
                status shouldBe HttpStatusCode.UnprocessableEntity
                body.str("code") shouldBe "UNSUPPORTED_CURRENCY"
                body.str("field") shouldBe "currency"
                e.db.snapshot() shouldBe empty
                http.postJson(L02Json.body()).first shouldBe HttpStatusCode.Created
            }
        }
})
