package dev.ledgerline.spec

import dev.ledgerline.calc.PluginReplay
import dev.ledgerline.core.ActivityType
import dev.ledgerline.core.Currency
import dev.ledgerline.core.Validation
import dev.ledgerline.plugins.cash.CashBalancePlugin
import dev.ledgerline.testsupport.Activities.ACCOUNT
import dev.ledgerline.testsupport.Activities.activity
import dev.ledgerline.testsupport.ActivityCountPlugin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * Story L-14 (second domain plugin: cash-balance tracker), pure tests. PROVISIONAL: the story is still Draft and the
 * owner has not decided C11 (which plugin), where plugins live, the overdraft policy, or reconciliation. AC1, AC6 and
 * AC7 need git, the ledger or `reconcile`; they are not covered here.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, the owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.plugins.cash                      // placement is an owner decision
 * data class CashKey(val accountId: String, val currency: Currency)
 * data class CashEntry(val idempotencyKey: String, val effectiveDate: LocalDate, val signedAmountMinor: Long)
 * data class CashState(val balances: Map<CashKey, Long>, val entries: Map<CashKey, List<CashEntry>>) {
 *     fun balanceOf(accountId: String, currency: Currency): Long      // 0 when absent
 *     fun canonicalText(): String   // integers only, LF only, keys sorted; the input to PluginReplay.sha256Hex
 * }
 * data class CashExplanation(val state: CashState) {
 *     fun entriesFor(accountId: String, currency: Currency): List<CashEntry>  // in apply order
 * }
 * object CashBalancePlugin : DomainPlugin<CashState, CashExplanation> {
 *     fun empty(): CashState
 *     // Stateless shape check: rejects a blank accountId or a negative amountMinor / commissionMinor.
 *     override fun validate(activity: Activity): Validation
 *     // Stateful overdraft check (validate() has no state). Rejected.reason contains "INSUFFICIENT_CASH", the
 *     // account id, the currency and the available balance. OWNER-INPUT C11 policy: refuse, never allow negative.
 *     fun validateAgainst(state: CashState, activity: Activity): Validation
 *     override fun apply(state: CashState, activity: Activity): CashState
 *     override fun explain(state: CashState): CashExplanation
 * }
 * ```
 *
 * Signed effect on the activity's own currency: DEPOSIT +amount; WITHDRAWAL -amount; FEE -amount;
 * BUY -(amount + commission); SELL +(amount - commission); DIVIDEND +amount; RETURN_OF_CAPITAL +amount;
 * FX_CONVERSION -amount in `currency` and +counterAmount in `counterCurrency`. Apply keeps no ordering of its own.
 *
 * Reuse, in package `dev.ledgerline.calc` (public, plugin-neutral; the existing ACB replay helpers are internal):
 *
 * ```kotlin
 * object PluginReplay {
 *     fun canonicalOrder(activities: Collection<Activity>): List<Activity>   // effectiveDate, sourceSeq, key
 *     // canonical order, then fold apply; a repeated idempotencyKey is skipped (redelivery changes nothing)
 *     fun <S, E> replay(plugin: DomainPlugin<S, E>, initial: S, activities: Collection<Activity>): S
 *     fun sha256Hex(text: String): String   // lower-case hex of SHA-256 over UTF-8
 * }
 * ```
 *
 * Gap for the owner: AC2 also asks that "an activity and then its reversal returns the prior state". Activity has no
 * reversal form, so this is not tested until the story says how a plugin sees a reversal.
 */
class L14CashPluginSpec : FunSpec({

    val cad = Currency.CAD
    val usd = Currency.USD

    // Fixture F2 (story L-14, TP-0001-NR1), all NON_REGISTERED.
    val f2 =
        listOf(
            activity("TP-0001-2025-0001", ActivityType.DEPOSIT, LocalDate.of(2025, 1, 6), 1_000_000, sourceSeq = 1),
            activity(
                "TP-0001-2025-0002",
                ActivityType.BUY,
                LocalDate.of(2025, 1, 7),
                301_250,
                securityId = "SYN-AAA",
                quantity = 100_000_000,
                commissionMinor = 495,
                sourceSeq = 2,
            ),
            activity(
                "TP-0001-2025-0003",
                ActivityType.FX_CONVERSION,
                LocalDate.of(2025, 1, 9),
                138_000,
                counterCurrency = usd,
                counterAmountMinor = 100_000,
                sourceSeq = 3,
            ),
            activity(
                "TP-0001-2025-0004",
                ActivityType.DIVIDEND,
                LocalDate.of(2025, 3, 31),
                1_234,
                securityId = "SYN-AAA",
                sourceSeq = 4,
            ),
            activity("TP-0001-2025-0005", ActivityType.FEE, LocalDate.of(2025, 4, 1), 995, sourceSeq = 5),
            activity(
                "TP-0001-2025-0006", ActivityType.BUY, LocalDate.of(2025, 4, 2), 50_000, currency = usd,
                securityId = "SYN-USX", quantity = 10_000_000, commissionMinor = 495, sourceSeq = 6,
            ),
        )
    val plugin = CashBalancePlugin
    val afterF2 = f2.fold(plugin.empty(), plugin::apply)

    test("L-14 AC3: F2 leaves CAD 560494 and USD 49505 on TP-0001-NR1") {
        afterF2.balanceOf(ACCOUNT, cad) shouldBe 1_000_000L - 301_745 - 138_000 + 1_234 - 995
        afterF2.balanceOf(ACCOUNT, cad) shouldBe 560_494L
        afterF2.balanceOf(ACCOUNT, usd) shouldBe 49_505L
    }

    test("L-14 AC4: explain lists the five CAD entries in date order with signed amounts, no USD-only activity") {
        val entries = plugin.explain(afterF2).entriesFor(ACCOUNT, cad)
        entries.map { it.idempotencyKey } shouldBe (1..5).map { "TP-0001-2025-000$it" }
        entries.map { it.signedAmountMinor } shouldBe listOf(1_000_000L, -301_745L, -138_000L, 1_234L, -995L)
        entries.map { it.effectiveDate } shouldBe entries.map { it.effectiveDate }.sorted()
    }

    test("L-14 AC5: a 560495 CAD withdrawal is refused with INSUFFICIENT_CASH; 560494 is accepted; nothing changes") {
        fun withdrawal(amount: Long) =
            activity("TP-0001-2025-0007", ActivityType.WITHDRAWAL, LocalDate.of(2025, 5, 1), amount, sourceSeq = 7)
        val refused = plugin.validateAgainst(afterF2, withdrawal(560_495))
        refused.shouldBeInstanceOf<Validation.Rejected>()
        listOf("INSUFFICIENT_CASH", ACCOUNT, "CAD", "560494").forEach { refused.reason shouldContain it }
        plugin.validateAgainst(afterF2, withdrawal(560_494)) shouldBe Validation.Accepted
        plugin.validate(withdrawal(560_495)) shouldBe Validation.Accepted // stateless shape check only
        afterF2.balanceOf(ACCOUNT, cad) shouldBe 560_494L // validation never mutates state
    }

    test("L-14 AC2: same contract as the ACB plugin; core replay utilities work unchanged for both plugins") {
        // Deterministic apply, and a redelivered key changes nothing.
        val shuffled = f2.reversed() + f2.take(3)
        val replayed = PluginReplay.replay(plugin, plugin.empty(), shuffled)
        replayed shouldBe afterF2

        // Replaying in canonical order twice gives the same state hash.
        fun hash(s: dev.ledgerline.plugins.cash.CashState) = PluginReplay.sha256Hex(s.canonicalText())
        val once = hash(PluginReplay.replay(plugin, plugin.empty(), f2))
        once shouldBe hash(PluginReplay.replay(plugin, plugin.empty(), f2))
        hash(replayed) shouldBe hash(afterF2)
        hash(afterF2).length shouldBe 64
        // The very same utilities drive the other plugin.
        val counted = PluginReplay.replay(ActivityCountPlugin, ActivityCountPlugin.empty(), f2.reversed() + f2)
        counted.countOf(ACCOUNT) shouldBe 6
        counted.keys shouldBe PluginReplay.canonicalOrder(f2).map { it.idempotencyKey }
    }
})
