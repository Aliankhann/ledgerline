package dev.ledgerline.spec

import dev.ledgerline.app.acbVsBookFromActivities
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.DataGapWarnings
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.Activities
import dev.ledgerline.testsupport.L06Activities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.util.Random

/** L-16 phase B attack tests (QE). Calc-level and route-level; no tax values, so no fixture citation is needed. */
class L16AttackSpec : FunSpec({

    val engine = AcbEngine(CaIta2026v1)
    val share = Activities.SHARE

    fun act(
        entry: Long,
        key: String,
        type: ActivityType,
        qtyShares: Long,
        day: Int,
        sec: String = "XYZ",
        taxpayer: String = Activities.TAXPAYER,
        accountType: AccountType = AccountType.NON_REGISTERED,
    ): PostedActivity =
        L06Activities.posted(
            Activities.activity(
                key = key,
                type = type,
                date = LocalDate.of(2025, 1, day),
                amountMinor = if (type == ActivityType.TRANSFER_IN) 0L else qtyShares * 100,
                securityId = sec,
                quantity = qtyShares * share,
                sourceSeq = entry,
                taxpayerId = taxpayer,
                accountType = accountType,
            ),
            entry,
        )

    fun ids(
        m: Map<PoolKey, List<dev.ledgerline.calc.Reason>>,
        rule: String,
    ): Map<PoolKey, List<Long>> =
        m.mapValues {
                (_, rs) ->
            rs.filter { it.ruleId == rule }.flatMap { it.entryIds }
        }.filterValues { it.isNotEmpty() }

    // 1 + 5: seed-42 year. The route builds pools with replaySuperficial but warnings from replay; they must agree.
    test(
        "L-16 attack: seed 42 - detect agrees with both replays' rejections, is deterministic, flags TP-0001-2025-0027",
    ) {
        val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(42, 5, 2025))
        parsed.fileErrors.shouldBeEmpty()
        val history = parsed.activities.mapIndexed { i, a -> PostedActivity("act-$i", i + 1L, a) }
        val replay = engine.replay(history)
        val sl = engine.replaySuperficial(history)
        sl.rejections.map { it.idempotencyKey }.toSet() shouldBe replay.rejections.map { it.idempotencyKey }.toSet()

        val detected = DataGapWarnings.detect(history, replay)
        val flagged = ids(detected, DataGapWarnings.OVERSELL).values.flatten().toSet()
        flagged shouldBe
            history.filter { it.activity.idempotencyKey in replay.rejections.map { r -> r.idempotencyKey } }
                .map { it.entryId }.toSet()
        val seeded = history.single { it.activity.idempotencyKey == "TP-0001-2025-0027" }
        seeded.activity.activityType shouldBe ActivityType.SELL
        flagged shouldContain seeded.entryId

        DataGapWarnings.detect(history, replay) shouldBe detected
        val shuffled = history.shuffled(Random(7))
        DataGapWarnings.detect(shuffled, engine.replay(shuffled)) shouldBe detected

        // Route level: the row for the seeded sell's security carries the warning with the same entry id.
        val sec = checkNotNull(seeded.activity.securityId)
        var body = ""
        testApplication {
            application {
                acbVsBookFromActivities(
                    { tp -> history.filter { it.activity.taxpayerId == tp } },
                    "security_id,book_value\n",
                )
            }
            body = client.get("/v1/holdings/TP-0001/acb-vs-book").bodyAsText()
        }
        val row =
            Json.parseToJsonElement(body).jsonObject["holdings"]!!.jsonArray
                .map { it.jsonObject }.single { it["securityId"]!!.jsonPrimitive.content == sec }
        val over =
            row["reasons"]!!.jsonArray.map {
                it.jsonObject
            }.single { it["ruleId"]!!.jsonPrimitive.content == "DATA-GAP-OVERSELL" }
        over["entryIds"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() } shouldContain seeded.entryId
    }

    // 2: several over-sells and registered activity.
    test(
        "L-16 attack: two over-sells on one key give one sorted reason; registered transfers and sells not flagged",
    ) {
        val acts =
            listOf(
                act(9, "s9", ActivityType.SELL, 5, 12),
                act(3, "s3", ActivityType.SELL, 7, 11),
                act(5, "r5", ActivityType.TRANSFER_IN, 100, 10, accountType = AccountType.RRSP),
                act(6, "r6", ActivityType.SELL, 500, 13, accountType = AccountType.TFSA),
            )
        val out = DataGapWarnings.detect(acts, engine.replay(acts))
        out.keys shouldContainExactly setOf(PoolKey(Activities.TAXPAYER, "XYZ"))
        val reasons = out.getValue(PoolKey(Activities.TAXPAYER, "XYZ"))
        reasons.map { it.ruleId } shouldContainExactly listOf(DataGapWarnings.OVERSELL)
        reasons.single().entryIds shouldContainExactly listOf(3L, 9L)
        reasons.single().amountMinor shouldBe 0L
    }

    // 3: rejection matching is by idempotency key only.
    test("L-16 attack: the same key on another taxpayer's accepted sell is not flagged") {
        val acts =
            listOf(
                // TP-A: rejected, nothing held; TP-B: fine.
                act(1, "dup", ActivityType.SELL, 5, 10, taxpayer = "TP-A"),
                act(2, "buyB", ActivityType.BUY, 10, 9, taxpayer = "TP-B"),
                act(3, "dup", ActivityType.SELL, 5, 10, taxpayer = "TP-B"),
            )
        val out = DataGapWarnings.detect(acts, engine.replay(acts))
        out.keys shouldContainExactly setOf(PoolKey("TP-A", "XYZ"))
    }

    // 4: a security that only ever arrived by transfer has no pool and no book value.
    test("L-16 attack: a TRANSFER_IN-only security with no book value still shows its warning on the route") {
        val acts = listOf(act(1, "t1", ActivityType.TRANSFER_IN, 100, 10))
        var body = ""
        testApplication {
            application { acbVsBookFromActivities({ _ -> acts }, "security_id,book_value\n") }
            body = client.get("/v1/holdings/${Activities.TAXPAYER}/acb-vs-book").bodyAsText()
        }
        val holdings = Json.parseToJsonElement(body).jsonObject["holdings"]!!.jsonArray
        holdings.map { it.jsonObject["securityId"]!!.jsonPrimitive.content } shouldContain "XYZ"
        DataGapWarnings.detect(acts, engine.replay(acts)).isEmpty() shouldBe false
        DataGapWarnings.detect(emptyList(), engine.replay(emptyList())).shouldBeEmpty()
    }
})
