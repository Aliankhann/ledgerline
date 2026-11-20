package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.GainKind
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStep
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.RejectionCode
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.AccountType
import dev.ledgerline.core.ActivityType.BUY
import dev.ledgerline.core.ActivityType.SELL
import dev.ledgerline.testsupport.Activities.SHARE
import dev.ledgerline.testsupport.L06Activities
import dev.ledgerline.testsupport.L06Fixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * Story L-06 (docs/stories/L-06-pooled-acb.md), pure: no database. Every test assumes rule set `CA-ITA-2026.1`
 * (docs/tax-model.md, PROPOSED) and names it. Expected tax values come only from the fixtures in
 * `tests/fixtures/l06/`, each citing its published worked example.
 *
 * API contract (package `dev.ledgerline.calc`; Dev may add members, not change these):
 *
 * - `interface TaxRuleSet` (ADR 0004): pure, no I/O, clock or floating point. Must stay an interface so a test can
 *   wrap a rule set by delegation.
 *   - `val id: String`: rule set ID and version, `"CA-ITA-2026.1"` for [CaIta2026v1]. Goes into every output hash.
 *   - `val ruleIds: Set<String>`: every Rule ID it defines (the "Rule ID" column of docs/tax-model.md).
 *   - `val acbUnitsPerCent: Long`: `ROUND-SCALE`, internal ACB units in one cent; greater than 1 (invariant 8).
 *   - `fun toReportedCents(internal: Long): Long`: `ROUND-MODE`, internal units to whole cents, used only at a
 *     reporting boundary (`ROUND-BOUNDARY`).
 *   - `fun taxDate(activity: Activity): LocalDate`: `DATE-BASIS`.
 * - `object CaIta2026v1 : TaxRuleSet`.
 * - `data class PostedActivity(val activityId: String, val entryId: Long, val activity: Activity)`: an activity and
 *   the ledger entry it was posted as (AC9).
 * - `data class PoolKey(val taxpayerId: String, val securityId: String)`: one pool per taxpayer per security
 *   (`POOL-IDENTICAL`), across all non-registered accounts.
 * - `data class AcbPool(val key: PoolKey, val shares: Long, val totalAcb: Long, val history: List<PoolChange>)`;
 *   `AcbPool.empty(taxpayerId, securityId)` has 0 shares, 0 ACB and no history. `shares` is in 10^-6 share units;
 *   `totalAcb` is in internal units (`acbUnitsPerCent` per cent), never negative.
 * - `data class PoolChange(val activityId: String, val entryId: Long, val taxDate: LocalDate, val sharesDelta: Long,
 *   val acbDelta: Long, val ruleIds: Set<String>)`: one row per activity that changed shares or ACB; `acbDelta` in
 *   internal units. History is in canonical order.
 * - `enum class GainKind { DISPOSITION, DEEMED_GAIN_ROC }`.
 * - `data class GainLine(val kind: GainKind, val activityId: String, val taxDate: LocalDate, val gain: Long,
 *   val gainCents: Long, val acbSold: Long, val ruleIds: Set<String>)`: a reported capital gain (negative = loss).
 *   `gain` and `acbSold` (0 for a deemed gain) are internal units; `gainCents` is `toReportedCents(gain)`.
 * - `sealed interface PoolStep { val pool: AcbPool }` with
 *   `data class Applied(override val pool: AcbPool, val gains: List<GainLine>, val explanation: Explanation)` and
 *   `data class Rejected(override val pool: AcbPool, val rejection: Rejection)`; a rejected step returns the pool
 *   unchanged.
 * - `data class Explanation(val ruleSetId: String, val ruleIds: Set<String>, val text: String)`: the rule IDs the
 *   step applied (non-empty, a subset of `ruleSet.ruleIds`).
 * - `enum class RejectionCode { INSUFFICIENT_SHARES }`;
 *   `data class Rejection(val idempotencyKey: String, val code: RejectionCode, val held: Long, val requested: Long,
 *   val message: String)`; `held` and `requested` in 10^-6 share units (AC6).
 * - `class AcbEngine(val ruleSet: TaxRuleSet)`:
 *   - `fun apply(pool: AcbPool, posted: PostedActivity): PoolStep`. A registered-account activity is `Applied`
 *     with the pool unchanged and no history row (`POOL-REGISTERED-EXCL`).
 *   - `fun check(pool: AcbPool, activity: Activity): Rejection?`: the gate the poster calls before posting, so a
 *     rejected sell never reaches the ledger (AC6). `null` means acceptable.
 *   - `fun replay(activities: Collection<PostedActivity>): Replay`: sorts canonically (tax date, then `sourceSeq`, then
 *     idempotency key; never arrival order), skips activities without a security and registered-account ones.
 *   - `fun acbPerShareCents(pool: AcbPool): Long`: display only, total ACB per whole share, rounded by `ROUND-MODE`.
 * - `data class Replay(val ruleSetId: String, val pools: Map<PoolKey, AcbPool>, val gains: List<GainLine>,
 *   val rejections: List<Rejection>, val outputHash: String)`: gains and rejections in canonical order; the hash
 *   covers the rule set ID, pools and gains.
 */
class L06PooledAcbSpec : FunSpec({

    val rules: TaxRuleSet = CaIta2026v1
    val rs = "[CA-ITA-2026.1]"
    val engine = AcbEngine(rules)

    fun pool(security: String): AcbPool = AcbPool.empty(L06Activities.TAXPAYER, security)

    fun applied(step: PoolStep): PoolStep.Applied = step.shouldBeInstanceOf<PoolStep.Applied>()

    /** Applies [steps] in order from an empty pool; every step must be accepted. */
    fun run(
        security: String,
        steps: List<PostedActivity>,
    ): List<PoolStep.Applied> {
        var p = pool(security)
        return steps.map { a -> applied(engine.apply(p, a)).also { p = it.pool } }
    }

    /** Asserts the fixture's `prefix.expected.*` values: shares, ACB (reported and exact) and per share if given. */
    fun expectPool(
        f: L06Fixture,
        prefix: String,
        p: AcbPool,
    ) {
        p.shares shouldBe f.long("$prefix.expected.shares.units")
        val acbCents = f.long("$prefix.expected.acb.cents")
        rules.toReportedCents(p.totalAcb) shouldBe acbCents
        p.totalAcb shouldBe Math.multiplyExact(acbCents, rules.acbUnitsPerCent)
        val perShare = "$prefix.expected.acbPerShare.cents"
        if (f.has(perShare)) engine.acbPerShareCents(p) shouldBe f.long(perShare)
    }

    fun expectDisposition(
        f: L06Fixture,
        prefix: String,
        step: PoolStep.Applied,
    ) {
        val cents = f.long("$prefix.expected.gain.cents")
        step.gains shouldHaveSize 1
        val line = step.gains.single()
        line.kind shouldBe GainKind.DISPOSITION
        line.gainCents shouldBe cents
        line.gain shouldBe Math.multiplyExact(cents, rules.acbUnitsPerCent)
        line.taxDate shouldBe f.date("$prefix.date")
    }

    val howto = L06Fixture.load("acb-howto-chain")
    val refUn = L06Fixture.load("acb-roc-ref-un")
    val sharon = L06Fixture.load("acb-neg-sharon1")
    val chain = (1..4).map { L06Activities.row(howto, "t$it", seq = it.toLong()) }

    test("L-06 $rs: the spec runs under rule set CA-ITA-2026.1 with an internal ACB unit finer than a cent") {
        rules.id shouldBe "CA-ITA-2026.1"
        engine.ruleSet.id shouldBe rules.id
        (rules.acbUnitsPerCent > 1L) shouldBe true
    }

    test("L-06 AC1 $rs: buy with commission; total ACB = cost + commission (ACB-HOWTO transaction 1)") {
        val step = run(howto.security, chain.take(1)).single()
        expectPool(howto, "t1", step.pool)
        step.gains.shouldBeEmpty()
    }

    test("L-06 AC1b $rs: buy with commission, second example (ACB-ROC, 1,000 units of REF-UN.TO)") {
        val step = run(refUn.security, listOf(L06Activities.row(refUn, "t1", seq = 1))).single()
        expectPool(refUn, "t1", step.pool)
    }

    test("L-06 AC2 $rs: a second buy adds to the post-sale pool (ACB-HOWTO transaction 3)") {
        val steps = run(howto.security, chain.take(3))
        expectPool(howto, "t3", steps[2].pool)
        steps[2].gains.shouldBeEmpty()
    }

    test("L-06 AC2b $rs: 90 shares onto 280 shares at ACB 0; per-share display 13.64 (ACB-NEG, Sharon1)") {
        val setup =
            listOf(
                L06Activities.buy(sharon, "setup.buy", seq = 1),
                L06Activities.roc(
                    "L06-sharon-setup-roc",
                    sharon.date("setup.roc.date"),
                    sharon.security,
                    sharon.long("setup.roc.amount.cents"),
                    seq = 2,
                ),
            )
        val before = run(sharon.security, setup).last().pool
        before.shares shouldBe 280_000_000L
        before.totalAcb shouldBe 0L
        val step = applied(engine.apply(before, L06Activities.row(sharon, "t1", seq = 3)))
        expectPool(sharon, "t1", step.pool)
    }

    test("L-06 AC3 $rs: sells with commission; gain uses total ACB x sold / held (ACB-HOWTO transactions 2, 4)") {
        val steps = run(howto.security, chain)
        expectDisposition(howto, "t2", steps[1])
        expectPool(howto, "t2", steps[1].pool)
        expectDisposition(howto, "t4", steps[3])
        expectPool(howto, "t4", steps[3].pool)
        steps[1].gains.single().ruleIds shouldContainAll setOf("ACB-SOLD", "PROCEEDS-SELL-COMMISSION")
    }

    test("L-06 AC4 $rs: return of capital reduces ACB; shares unchanged; no gain (ACB-ROC, REF-UN.TO)") {
        val steps = run(refUn.security, (1..2).map { L06Activities.row(refUn, "t$it", seq = it.toLong()) })
        expectPool(refUn, "t2", steps[1].pool)
        steps[1].gains.shouldBeEmpty()
        steps[1].explanation.ruleIds shouldContainAll setOf("ROC-REDUCES-ACB")
    }

    test("L-06 AC5 $rs: ROC above ACB resets ACB to 0 and the excess is a deemed gain (ACB-NEG body)") {
        val neg = L06Fixture.load("acb-neg-body")
        val rows = (1..4).map { L06Activities.row(neg, "t$it", seq = it.toLong()) }
        val steps = run(neg.security, rows)
        steps.forEachIndexed { i, step ->
            val prefix = "t${i + 1}"
            expectPool(neg, prefix, step.pool)
            if (i == 0) return@forEachIndexed
            val expected = neg.long("$prefix.expected.gain.cents")
            if (expected == 0L) {
                step.gains.filter { it.gainCents != 0L }.shouldBeEmpty()
            } else {
                val line = step.gains.single()
                line.kind shouldBe GainKind.DEEMED_GAIN_ROC
                line.gainCents shouldBe expected
                line.gain shouldBe Math.multiplyExact(expected, rules.acbUnitsPerCent)
                line.acbSold shouldBe 0L
                line.activityId shouldBe rows[i].activityId
                // Story: "in the year of the ROC"; CA-ITA-2026.1 ROC-NEG-DATE stamps it with the ROC's tax date.
                line.taxDate.year shouldBe 2025
                line.taxDate shouldBe neg.date("$prefix.date")
                step.explanation.ruleIds shouldContainAll setOf("ROC-NEG-GAIN", "ROC-NEG-RESET")
            }
        }
    }

    // AC6 and AC9 inputs are non-tax (story: "use any synthetic values"); prices are QE-chosen.
    val ac6Buy =
        L06Activities.trade(
            "L06-AC6-buy",
            BUY,
            LocalDate.of(2025, 3, 3),
            "SYN-A",
            10 * SHARE,
            10_000L,
            0L,
            seq = 1,
        )
    val ac6Sell =
        L06Activities.trade(
            "L06-AC6-sell",
            SELL,
            LocalDate.of(2025, 3, 4),
            "SYN-A",
            11 * SHARE,
            11_000L,
            0L,
            seq = 2,
        )

    test("L-06 AC6 $rs: selling 11 when 10 are held is rejected (held 10, requested 11); pool unchanged") {
        val before = run("SYN-A", listOf(ac6Buy)).single().pool
        val gate = engine.check(before, ac6Sell.activity).shouldNotBeNull()
        gate.code shouldBe RejectionCode.INSUFFICIENT_SHARES
        gate.held shouldBe 10 * SHARE
        gate.requested shouldBe 11 * SHARE
        gate.message shouldContain "10"
        gate.message shouldContain "11"
        val step = engine.apply(before, ac6Sell).shouldBeInstanceOf<PoolStep.Rejected>()
        step.rejection shouldBe gate
        step.pool shouldBe before
        engine.check(before, ac6Sell.activity.copy(quantity = 10 * SHARE)).shouldBeNull()

        val replayed = engine.replay(listOf(ac6Sell, ac6Buy))
        replayed.rejections.single() shouldBe gate
        replayed.pools.getValue(before.key) shouldBe before
        replayed.gains.shouldBeEmpty()
    }

    test("L-06 AC7 $rs: reverse arrival order gives the same pools, results and hash (ACB-HOWTO chain)") {
        val forward = engine.replay(chain)
        val reversed = engine.replay(chain.reversed())
        reversed shouldBe forward
        reversed.outputHash shouldBe forward.outputHash
        forward.rejections.shouldBeEmpty()
        forward.ruleSetId shouldBe rules.id
        val p = forward.pools.getValue(PoolKey(L06Activities.TAXPAYER, howto.security))
        p.shares shouldBe howto.long("t4.expected.shares.units")
        rules.toReportedCents(p.totalAcb) shouldBe howto.long("t4.expected.acb.cents")
        forward.gains.map { it.gainCents } shouldBe listOf("t2", "t4").map { howto.long("$it.expected.gain.cents") }
    }

    test("L-06 AC8 $rs: one pool across non-registered accounts; a TFSA buy never enters it (ACB-REG)") {
        val reg = L06Fixture.load("acb-reg-pooling")
        val tfsa =
            L06Activities.buy(
                reg,
                "tfsa",
                seq = 2,
                accountId = L06Activities.TFSA,
                accountType = AccountType.TFSA,
            )
        val runs =
            listOf(
                listOf(chain[0]),
                listOf(
                    L06Activities.buy(reg, "a", seq = 1),
                    L06Activities.buy(reg, "b", seq = 2, accountId = L06Activities.NR_B),
                ),
                listOf(chain[0], tfsa),
            ).map { engine.replay(it) }
        runs.forEach { r ->
            val pools = r.pools.filterKeys { it.securityId == reg.security }
            pools.keys shouldBe setOf(PoolKey(L06Activities.TAXPAYER, reg.security))
            val p = pools.values.single()
            p.shares shouldBe reg.long("expected.shares.units")
            rules.toReportedCents(p.totalAcb) shouldBe reg.long("expected.acb.cents")
            p.totalAcb shouldBe Math.multiplyExact(reg.long("expected.acb.cents"), rules.acbUnitsPerCent)
        }
        runs[2].pools shouldBe runs[0].pools
        val p1 = runs[0].pools.values.single()
        val tfsaStep = applied(engine.apply(p1, tfsa))
        tfsaStep.pool shouldBe p1
        tfsaStep.explanation.ruleIds shouldContainAll setOf("POOL-REGISTERED-EXCL")
    }

    test("L-06 AC9 $rs: every pool change names the activity ID and ledger entry that caused it") {
        val p = run("SYN-A", listOf(ac6Buy)).single().pool
        val row = p.history.single()
        row.activityId shouldBe ac6Buy.activityId
        row.entryId shouldBe ac6Buy.entryId
        row.sharesDelta shouldBe 10 * SHARE
        row.acbDelta shouldBe p.totalAcb
        engine.replay(listOf(ac6Buy)).pools.getValue(p.key).history shouldBe p.history

        val full = engine.replay(chain).pools.values.single()
        full.history.map { it.activityId to it.entryId } shouldBe chain.map { it.activityId to it.entryId }
    }
})
