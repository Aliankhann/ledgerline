package dev.ledgerline.testsupport

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.core.Activity
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import java.time.Duration

/**
 * Support for the seed-on-start feature (`SeedLoaderSpec`). OWNER-INPUT: C32 (provisional; no approved story yet).
 *
 * The feature loads the generator's year with the L-01 AC7 parameters: 5 taxpayers, calendar year 2025.
 */
object SeedSupport {
    const val SEED = 42L
    const val TAXPAYERS = 5
    const val YEAR = 2025

    /** The activities the generator makes for [seed]: the test's own oracle for "how many should be posted". */
    fun expected(seed: Long = SEED): List<Activity> {
        val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(seed, TAXPAYERS, YEAR))
        check(parsed.rejections.isEmpty() && parsed.fileErrors.isEmpty()) { "generator output must parse cleanly" }
        return parsed.activities
    }
}

/** (entry id, unit) pairs whose debits differ from their credits, read as the owner. Empty means invariant 1 holds. */
fun LedgerDb.unbalancedEntryUnits(): List<List<String?>> =
    query(
        owner,
        "SELECT entry_id, unit FROM ledger.line GROUP BY entry_id, unit " +
            "HAVING sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0",
    )

/** Idempotency keys of every ledger entry, sorted. */
fun LedgerDb.entryKeys(): List<String> =
    query(owner, "SELECT idempotency_key FROM ledger.entry ORDER BY 1").map { it[0]!! }

/**
 * Relays every pending outbox row to this env's topic, then applies every event with a `PoolConsumer` as the app
 * role, so `calc.pool` is the consumer's own state. Fails if the inbox does not hold one row per outbox event.
 */
fun L05Env.drainAndApply(group: String = "seed") {
    draining { check(awaitDelivered(Duration.ofSeconds(120)).isEmpty()) { "outbox not drained" } }
    val engine = AcbEngine(CaIta2026v1)
    PoolConsumer(db.app, consumerConfig(group), engine, topic).use { it.pollUntilInbox(db, events().size) }
    check(db.inboxEventIds().size == events().size) {
        "consumer applied ${db.inboxEventIds().size} of ${events().size} events"
    }
}
