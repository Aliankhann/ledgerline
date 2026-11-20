package dev.ledgerline.property

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.AcbPool
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.GainKind
import dev.ledgerline.calc.PoolKey
import dev.ledgerline.calc.PoolStep
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.TaxRuleSet
import dev.ledgerline.core.ActivityType
import dev.ledgerline.testsupport.L06Streams
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.random.Random

/**
 * Invariant 8 (money precision) and ADR 0004 traceability for the L-06 pool engine, rule set `CA-ITA-2026.1`.
 * The API contract is in the KDoc of `dev.ledgerline.spec.L06PooledAcbSpec`.
 *
 * The BigInteger oracle restates the rule text of docs/tax-model.md (PROPOSED), not any worked example:
 * `COST-BUY-COMMISSION` (a buy adds cost + commission), `ACB-SOLD` with `ROUND-SCALE` (ACB sold = floor(total ACB x
 * sold / held); the pool keeps the remainder), `PROCEEDS-SELL-COMMISSION` (gain = proceeds - commission - ACB sold),
 * `ROC-REDUCES-ACB` / `ROC-NEG-GAIN` / `ROC-NEG-RESET` (ROC absorbs up to the pool ACB, the excess is a deemed gain)
 * and `ROUND-MODE` (half up, away from zero). If the owner changes one of these rules, this oracle changes with it.
 */
class L06PrecisionAndRulesPropertyTest : FunSpec({

    val rules: TaxRuleSet = CaIta2026v1
    val engine = AcbEngine(rules)
    val u: BigInteger by lazy { BigInteger.valueOf(rules.acbUnitsPerCent) }

    fun big(x: Long): BigInteger = BigInteger.valueOf(x)

    fun halfUp(x: BigInteger): BigInteger {
        val q = (x.abs().shiftLeft(1) + u).divide(u.shiftLeft(1))
        return if (x.signum() < 0) q.negate() else q
    }

    test("Invariant 8 [CA-ITA-2026.1]: ROUND-MODE reports whole cents half up, away from zero on exact halves") {
        val bound = Long.MAX_VALUE / 4
        checkAll(2_000, Arb.long(-bound..bound)) { x ->
            big(rules.toReportedCents(x)) shouldBe halfUp(big(x))
        }
        checkAll(1_000, Arb.long(-1_000_000_000_000L..1_000_000_000_000L)) { cents ->
            val exactHalf = big(cents) * u + u.shiftRight(1)
            if (!u.testBit(0) && exactHalf.bitLength() < 63) {
                big(rules.toReportedCents(exactHalf.toLong())) shouldBe halfUp(exactHalf)
                big(rules.toReportedCents(exactHalf.negate().toLong())) shouldBe halfUp(exactHalf.negate())
            }
        }
    }

    test(
        "Invariant 8 [CA-ITA-2026.1]: pool ACB, ACB sold and gains are exact against a BigInteger oracle (500 cases)",
    ) {
        val ctx =
            checkAll(500, L06Streams.stream(maxLength = 25, big = true)) { stream ->
                val r = engine.replay(stream.shuffled(Random(stream.size.toLong())))
                val rejected = r.rejections.map { it.idempotencyKey }.toSet()
                val gainsById = r.gains.groupBy { it.activityId }
                val counted = stream.filter { !L06Streams.isRegistered(it) && it.activity.idempotencyKey !in rejected }
                gainsById.keys.minus(counted.map { it.activityId }.toSet()).shouldBeEmpty()
                counted.groupBy(L06Streams::key).forEach { (key, acts) ->
                    val pool = r.pools.getValue(key)
                    val rows = pool.history.associateBy { it.activityId }
                    var acb = BigInteger.ZERO
                    var shares = BigInteger.ZERO
                    acts.forEach { p ->
                        val a = p.activity
                        val q = big(a.quantity ?: 0L)
                        val gains = gainsById[p.activityId].orEmpty()
                        val (dShares, dAcb) =
                            when (a.activityType) {
                                ActivityType.BUY -> q to (big(a.amountMinor) + big(a.commissionMinor)) * u
                                ActivityType.SELL -> {
                                    val sold = acb * q / shares
                                    val line = gains.single { it.kind == GainKind.DISPOSITION }
                                    big(line.acbSold) shouldBe sold
                                    big(line.gain) shouldBe (big(a.amountMinor) - big(a.commissionMinor)) * u - sold
                                    big(line.gainCents) shouldBe halfUp(big(line.gain))
                                    q.negate() to sold.negate()
                                }
                                else -> {
                                    val roc = big(a.amountMinor) * u
                                    val absorbed = roc.min(acb)
                                    val deemed = gains.filter { it.kind == GainKind.DEEMED_GAIN_ROC }
                                    deemed.fold(BigInteger.ZERO) { s, g -> s + big(g.gain) } shouldBe roc - absorbed
                                    deemed.forEach { big(it.gainCents) shouldBe halfUp(big(it.gain)) }
                                    BigInteger.ZERO to absorbed.negate()
                                }
                            }
                        val row = rows[p.activityId]
                        if (row == null) {
                            (dShares.signum() == 0 && dAcb.signum() == 0) shouldBe true
                        } else {
                            big(row.sharesDelta) shouldBe dShares
                            big(row.acbDelta) shouldBe dAcb
                        }
                        shares += dShares
                        acb += dAcb
                        acb.signum() shouldBeGreaterThanOrEqual 0
                    }
                    big(pool.totalAcb) shouldBe acb
                    big(pool.shares) shouldBe shares
                }
            }
        println("L-06 invariant 8: ran ${ctx.attempts()} generated streams with amounts up to 10^9 cents")
        ctx.attempts() shouldBeGreaterThanOrEqual 500
    }

    test("Invariant 8: no Double, Float or BigDecimal in any dev.ledgerline.calc class signature or constant pool") {
        val root = Paths.get(AcbEngine::class.java.protectionDomain.codeSource.location.toURI())
        val files: Map<String, ByteArray> =
            if (Files.isDirectory(root)) {
                Files.walk(root).use { s ->
                    s.filter { it.toString().endsWith(".class") }.toList()
                        .associate { root.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it) }
                }
            } else {
                JarFile(root.toFile()).use { jar ->
                    jar.entries().toList().filter { it.name.endsWith(".class") }
                        .associate { e -> e.name to jar.getInputStream(e).use { it.readBytes() } }
                }
            }
        val calc = files.filterKeys { it.startsWith("dev/ledgerline/calc/") }
        (calc.size > 1) shouldBe true
        val banned = setOf("java/math/BigDecimal", "java/lang/Double", "java/lang/Float")
        val forbidden =
            setOf(Double::class.javaPrimitiveType, Float::class.javaPrimitiveType, BigDecimal::class.java) +
                setOf(Double::class.javaObjectType, Float::class.javaObjectType)
        val offenders =
            calc.flatMap { (name, bytes) ->
                val text = String(bytes, Charsets.ISO_8859_1)
                val strings = banned.filter { it in text }.map { "$name references $it" }
                val className = name.removeSuffix(".class").replace('/', '.')
                val cls = Class.forName(className, false, AcbEngine::class.java.classLoader)
                val types =
                    cls.declaredFields.map { it.type } +
                        cls.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } +
                        cls.declaredConstructors.flatMap { it.parameterTypes.toList() }
                strings + types.filter { it in forbidden }.map { "$name uses ${it.name}" }
            }
        offenders.shouldBeEmpty()
    }

    val l06Rules =
        setOf(
            "DATE-BASIS", "POOL-IDENTICAL", "POOL-REGISTERED-EXCL", "ACB-SOLD", "COST-BUY-COMMISSION",
            "PROCEEDS-SELL-COMMISSION", "ROC-REDUCES-ACB", "ROC-DATE", "ROC-INPUT", "ROC-NEG-GAIN", "ROC-NEG-RESET",
            "ROC-NEG-DATE", "ROUND-SCALE", "ROUND-MODE", "ROUND-BOUNDARY",
        )

    fun required(
        p: PostedActivity,
        before: AcbPool,
    ): Set<String> =
        when {
            L06Streams.isRegistered(p) -> setOf("POOL-REGISTERED-EXCL")
            p.activity.activityType == ActivityType.BUY -> setOf("DATE-BASIS", "POOL-IDENTICAL", "COST-BUY-COMMISSION")
            p.activity.activityType == ActivityType.SELL -> setOf("DATE-BASIS", "ACB-SOLD", "PROCEEDS-SELL-COMMISSION")
            big(p.activity.amountMinor) * u > big(before.totalAcb) ->
                setOf("DATE-BASIS", "ROC-REDUCES-ACB", "ROC-NEG-GAIN", "ROC-NEG-RESET", "ROC-NEG-DATE")
            else -> setOf("DATE-BASIS", "ROC-REDUCES-ACB")
        }

    test("ADR 0004 [CA-ITA-2026.1]: the rule set defines every L-06 rule ID") {
        rules.ruleIds shouldContainAll l06Rules
    }

    test("ADR 0004 [CA-ITA-2026.1]: every pool step, change and gain line names the rule IDs it used (500 cases)") {
        checkAll(500, L06Streams.stream(maxLength = 25)) { stream ->
            val pools = mutableMapOf<PoolKey, AcbPool>()
            stream.forEach { p ->
                val k = L06Streams.key(p)
                val before = pools[k] ?: AcbPool.empty(k.taxpayerId, k.securityId)
                val step = engine.apply(before, p)
                pools[k] = step.pool
                if (step is PoolStep.Applied) {
                    step.explanation.ruleSetId shouldBe rules.id
                    rules.ruleIds shouldContainAll step.explanation.ruleIds
                    step.explanation.ruleIds shouldContainAll required(p, before)
                    step.pool.history.drop(before.history.size).forEach { row ->
                        row.ruleIds.isNotEmpty() shouldBe true
                        rules.ruleIds shouldContainAll row.ruleIds
                    }
                    step.gains.forEach { g ->
                        rules.ruleIds shouldContainAll g.ruleIds
                        val need =
                            if (g.kind == GainKind.DISPOSITION) {
                                setOf("ACB-SOLD", "PROCEEDS-SELL-COMMISSION", "ROUND-MODE")
                            } else {
                                setOf("ROC-NEG-GAIN", "ROC-NEG-DATE", "ROUND-MODE")
                            }
                        g.ruleIds shouldContainAll need
                    }
                }
            }
        }
    }

    test("ADR 0004: the rule set ID is part of the output hash; a renamed rule set gives the same pools") {
        val variant =
            object : TaxRuleSet by CaIta2026v1 {
                override val id: String = "CA-ITA-2026.1-qe-variant"
            }
        checkAll(100, L06Streams.stream(maxLength = 15)) { stream ->
            val a = engine.replay(stream)
            val b = AcbEngine(variant).replay(stream)
            b.ruleSetId shouldBe variant.id

            fun totals(pools: Map<PoolKey, AcbPool>) = pools.mapValues { it.value.shares to it.value.totalAcb }
            totals(b.pools) shouldBe totals(a.pools)
            b.outputHash shouldNotBe a.outputHash
        }
    }
})
