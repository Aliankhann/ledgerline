package dev.ledgerline.property

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.SubmitResult
import dev.ledgerline.core.Activity
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.testsupport.IdempotencyPlans
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.LineMath
import dev.ledgerline.testsupport.MutableClock
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.SubmissionKind
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Invariant 2 (Idempotency), story L-02 AC11. The API contract is in the KDoc of `dev.ledgerline.spec.L02IngestionSpec`.
 *
 * Each case generates 1 to 8 valid activities, submits each 1 to 5 times (plus, for some, a mutated copy that arrives
 * after the original, and a copy with only the description changed, placeholder C20) in random order to ledger A, and
 * the distinct activities once each to ledger B. Checked online: the first submission of a key posts, repeats return
 * the original ids, a mutated payload is a `KeyMismatch`. Checked on the ledgers: A equals B (same entries, lines and
 * per-account balances per unit, and the same row count in every table), after every 100 cases and at the end.
 */
class IdempotencyInvariantPropertyTest : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }

    /** Key to (date, seq, canonical lines), and balance per account and unit. Ignores sequence numbers (arrival order). */
    fun view(repo: LedgerRepository): Pair<Map<String, List<Any>>, Map<Pair<String, String>, Long>> {
        val entries = repo.entries()
        val byKey =
            entries.associate {
                it.idempotencyKey to listOf(it.effectiveDate, it.sourceSeq, LineMath.canonical(it.lines))
            }
        entries.size shouldBe byKey.size
        val balances =
            entries.flatMap { it.lines }.groupBy { it.account to it.unit }
                .mapValues { (k, ls) -> LineMath.delta(ls, k.first, k.second) }
        return byKey to balances
    }

    test(
        "Invariant 2 (L-02 AC11): duplicates and changed payloads in any order give the distinct-key ledger",
    )
        .config(tags = setOf(Integration), timeout = 30.minutes) {
            val a = pg.freshDatabase()
            val b = pg.freshDatabase()
            val clock = MutableClock(Instant.parse("2025-01-07T15:00:00Z"))
            val subject = IngestionService(LedgerRepository(a.app), clock)
            val reference = IngestionService(LedgerRepository(b.app), clock)
            var cases = 0
            var submissions = 0
            var rejected = 0

            fun shouldMatchReference() {
                view(LedgerRepository(a.app)) shouldBe view(LedgerRepository(b.app))
                a.rowCounts() shouldBe b.rowCounts()
            }

            val ctx =
                checkAll(PropTestConfig(iterations = 500), IdempotencyPlans.arb()) { plan ->
                    cases += 1
                    val firstIds = mutableMapOf<String, SubmitResult.Accepted>()
                    plan.submissions.forEach { s ->
                        submissions += 1
                        val key = s.activity.idempotencyKey
                        val result = subject.submit(s.activity)
                        withClue("case $cases, ${s.kind} of $key: $result") {
                            if (s.kind == SubmissionKind.MUTATED) {
                                rejected += 1
                                result shouldBe SubmitResult.KeyMismatch(key)
                            } else {
                                val accepted = result as SubmitResult.Accepted
                                val first = firstIds[key]
                                if (first == null) {
                                    accepted.alreadyExisted shouldBe false
                                    firstIds[key] = accepted
                                } else {
                                    accepted shouldBe first.copy(alreadyExisted = true)
                                }
                            }
                        }
                    }
                    plan.distinct.forEach { x: Activity ->
                        (reference.submit(x) as SubmitResult.Accepted).alreadyExisted shouldBe false
                    }
                    if (cases % 100 == 0) shouldMatchReference()
                }
            shouldMatchReference()
            println(
                "L-02 AC11 / invariant 2: ${ctx.attempts()} cases, $submissions submissions, $rejected mutants refused",
            )
            ctx.attempts() shouldBe 500
        }
})
