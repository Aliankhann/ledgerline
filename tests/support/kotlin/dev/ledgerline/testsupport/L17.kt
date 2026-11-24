package dev.ledgerline.testsupport

import dev.ledgerline.app.explainFromActivities
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ExplainStep
import dev.ledgerline.calc.Explanation
import dev.ledgerline.calc.HoldingExplanation
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * L-17 explain a holding (docs/stories/L-17-explain-a-holding.md). PROVISIONAL: the story is a Draft and OWNER-INPUT.
 * Provisional decisions the tests follow: the portfolio-total card is OUT (UI only, later); the add-back step's target
 * buy may be derived (only what AC3 states is tested); FX and missing-commission reasons are out of scope.
 *
 * Tax numbers come only from `tests/fixtures/cra/cra-xyz-gain.properties` (CRA XYZ worked example) and the share
 * ratio of `cra-ti-2004-0073011e5-partial-sl.properties` (CRA TI 2004-0073011E5). Everything else is a story input
 * (a commission of 495 cents, a book value 1 cent off) or is read from the engine's own public replay, as the L-11
 * follow-up spec does. The test never states a dollar amount that exists only by fixture assumption.
 *
 * API CONTRACT (PROPOSED by QE; Dev may add members, not change these). Package `dev.ledgerline.calc`:
 *
 * ```kotlin
 * enum class ExplainStatus { MATCHES, EXPLAINED, DISCREPANCY, DATA_GAP, NO_BOOK_VALUE }
 * data class SuperficialLossStep(
 *     val saleEntryId: Long, val targetEntryIds: List<Long>,
 *     val lossCents: Long, val deniedCents: Long, val addBackCents: Long,
 * )
 * data class ExplainStep(
 *     val entryId: Long, val activityId: String, val idempotencyKey: String, val taxDate: LocalDate,
 *     val type: ActivityType, val ruleIds: List<String>,
 *     val sharesDeltaUnits: Long, val costDeltaCents: Long,
 *     val runningSharesUnits: Long, val runningAcbCents: Long,
 *     val superficialLoss: SuperficialLossStep?,   // non-null only on the SL-ADDBACK-TARGET step
 * )
 * data class Explanation(
 *     val key: PoolKey, val bookValueCents: Long?, val acbCents: Long, val differenceCents: Long?,
 *     val steps: List<ExplainStep>, val reasons: List<Reason>,
 *     val status: ExplainStatus, val fullyExplained: Boolean, val adjustmentCount: Int, val unexplainedCents: Long,
 * )
 * data class ExplanationFooter.Footer(
 *     val status: ExplainStatus, val fullyExplained: Boolean, val adjustmentCount: Int, val unexplainedCents: Long,
 * )
 * object ExplanationFooter { fun of(differenceCents: Long, reasons: List<Reason>): Footer }
 * object HoldingExplanation {
 *     fun explain(activities: List<PostedActivity>, bookValueCents: Long?, key: PoolKey): Explanation?
 * }
 * ```
 *
 * Meaning (pure, no I/O, no Double/Float/BigDecimal):
 * - `explain` replays [activities] (any order; every taxpayer and security may be in the list) with
 *   `AcbEngine(CaIta2026v1).replaySuperficial`, picks [key], and builds the reasons exactly as the L-11 route does for
 *   that key (`compareAll` with the L-16 `DataGapWarnings`). It returns null only when the key has neither a pool nor an
 *   L-16 warning (an unknown holding). A holding with only warnings has `steps` empty and the warnings in `reasons`.
 * - `steps` are the key's pool history in canonical order (tax date, then sequence). `type` is the ActivityType of
 *   the activity the entry belongs to; the add-back step reports its sale's type (SELL) and shares delta 0.
 *   `costDeltaCents` = rounded running ACB minus the previous step's rounded running ACB (the first step's previous
 *   is 0), so deltas sum to `acbCents`. `runningSharesUnits` is the pool's shares after the step, in 10^-6 units.
 *   `ruleIds` is the PoolChange's rule ids, all in `CaIta2026v1.ruleIds`.
 * - Exactly one `SL-ADDBACK-TARGET` step per superficially denied sale: `entryId` = the sale's entry id, `taxDate` =
 *   a target's tax date, `sharesDeltaUnits` = 0, `superficialLoss` non-null with `saleEntryId` = the sale's entry id,
 *   `targetEntryIds` = the entry ids of the sale's `SlTrigger` buys, `lossCents` = the whole loss on the sale,
 *   `deniedCents` = the denied part, `addBackCents` = what is added to the pool ACB (equal to `deniedCents`).
 * - `status`: `NO_BOOK_VALUE` when the book value is null (and no warning); `DATA_GAP` when the holding has L-16
 *   warnings and no pool; otherwise from [ExplanationFooter.of]. (Status when a pool AND a warning coexist is not
 *   tested: the story's badge order is undefined for that case.)
 * - `ExplanationFooter.of(difference, reasons)`: `unexplainedCents` = difference minus the sum of the reasons whose
 *   ruleId is not `UNEXPLAINED`; `fullyExplained` = `unexplainedCents == 0` and no `UNEXPLAINED` reason with a non-zero
 *   amount; `adjustmentCount` = reasons with `amountMinor != 0` (warnings carry 0, so are never adjustments);
 *   `status` = MATCHES when `fullyExplained` and difference 0, EXPLAINED when `fullyExplained` and difference != 0,
 *   DISCREPANCY otherwise. There is no "Unreconciled" status.
 *   `Explanation.status/fullyExplained/adjustmentCount/unexplainedCents` are the same four values over its own
 *   `differenceCents` and `reasons` (null book value: difference unknown, status NO_BOOK_VALUE,
 *   `unexplainedCents` 0, `fullyExplained` false, `adjustmentCount` 0).
 *
 * Package `dev.ledgerline.app`: `fun Application.explainFromActivities(activities: (taxpayer: String) ->
 * List<PostedActivity>, bookValueCsv: String)` registers `GET /v1/holdings/{taxpayer}/{security}/explain` (same
 * parameter names as the acb-vs-book route; the URL is the story's). `Main.kt` calls it. The book value is the CSV row
 * `security_id,book_value` (dollars) for {security}, as the acb-vs-book route reads it; none means null.
 * 200 JSON: `{"taxpayerId","securityId","currency":"CAD","bookValueCents":n|null,"acbCents","differenceCents":n|null,
 * "status":"<ExplainStatus name>","fullyExplained":bool,"adjustmentCount","unexplainedCents","steps":[{"entryId",
 * "activityId","idempotencyKey","taxDate":"yyyy-mm-dd","type":"BUY","ruleIds":[..],"sharesDeltaUnits","costDeltaCents",
 * "runningSharesUnits","runningAcbCents","superficialLoss":null|{"saleEntryId","targetEntryIds":[..],"lossCents",
 * "deniedCents","addBackCents"}}],"reasons":[{"ruleId","text","entryIds":[..],"amountMinor"}]}`.
 * 404 (no activities for the taxpayer, or for that security, and no warning): body contains the taxpayer id and the
 * security id.
 *
 * UI contract (source-text guards, AC6/AC7/AC8; `ui/` has no component-test tool, see the report): `ui/src` fetches
 * `/api/v1/holdings/${encodeURIComponent(..)}/${encodeURIComponent(..)}/explain`; the holding row has an `onClick`;
 * the panel renders each step with `data-entry-id` and `data-highlighted`, each reason chip is a button with
 * `data-entry-id` over `entryIds.map(`; money goes through `formatMoney(` with `costDeltaCents`, `runningAcbCents` and
 * `unexplainedCents`; the footer texts are "Difference fully explained by" and "unexplained"; badges "Matches",
 * "Explained", "Discrepancy", "Data gap"; no "Unreconciled"; no filter that drops an `UNEXPLAINED` reason.
 */
object L17 {
    private val engine = AcbEngine(CaIta2026v1)

    /** ACB in cents after the superficial-loss replay (the route's pool source). */
    fun cents(
        rows: List<PostedActivity>,
        key: PoolKey,
    ): Long = CaIta2026v1.toReportedCents(engine.replaySuperficial(rows).pools.getValue(key).totalAcb)

    /** ACB in cents after the plain replay: what a broker that ignores the add-back would show. */
    fun plainCents(
        rows: List<PostedActivity>,
        key: PoolKey,
    ): Long = CaIta2026v1.toReportedCents(engine.replay(rows).pools.getValue(key).totalAcb)

    fun dollars(c: Long): String = "${c / 100}.${"%02d".format(c % 100)}"

    /** The book value CSV the route reads: a header, and one row when [bookCents] is given. */
    fun csvFor(
        security: String,
        bookCents: Long?,
    ): String = "security_id,book_value\n" + (bookCents?.let { "$security,${dollars(it)}\n" } ?: "")

    fun explain(
        rows: List<PostedActivity>,
        book: Long?,
        key: PoolKey,
    ): Explanation = requireNotNull(HoldingExplanation.explain(rows, book, key)) { "no explanation for $key" }

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    fun JsonObject.stepList(): List<JsonObject> = getValue("steps").jsonArray.map { it.jsonObject }

    fun JsonObject.reasonList(): List<JsonObject> = getValue("reasons").jsonArray.map { it.jsonObject }

    fun JsonObject.ids(name: String): List<Long> = getValue(name).jsonArray.map { it.jsonPrimitive.content.toLong() }

    fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    /** GET the explain route; [rows] are what the activity source returns for the taxpayer [known] only. */
    suspend fun get(
        rows: List<PostedActivity>,
        csv: String,
        taxpayer: String,
        security: String,
        known: String = L06Activities.TAXPAYER,
    ): Pair<HttpStatusCode, String> {
        var out: Pair<HttpStatusCode, String>? = null
        testApplication {
            application { explainFromActivities({ t -> if (t == known) rows else emptyList() }, csv) }
            val res = client.get("/v1/holdings/$taxpayer/$security/explain")
            out = res.status to res.bodyAsText()
        }
        return requireNotNull(out)
    }

    /** Running shares and ACB follow from the deltas, and each cost delta is the difference of rounded running ACBs. */
    fun List<ExplainStep>.checkRunning() {
        var shares = 0L
        var acb = 0L
        forEach {
            shares += it.sharesDeltaUnits
            it.runningSharesUnits shouldBe shares
            it.costDeltaCents shouldBe it.runningAcbCents - acb
            acb = it.runningAcbCents
        }
    }
}
