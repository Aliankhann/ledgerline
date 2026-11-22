package dev.ledgerline.app

import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.ledger.LedgerRepository
import javax.sql.DataSource

data class SeedResult(val posted: Int, val duplicates: Int)

/**
 * Seed on start. OWNER-INPUT: C32. Posts the generator's year (5 taxpayers, 2025) through the normal ingestion path, so
 * every activity gets its ledger entry, idempotency key and outbox row. The relay and consumer pick the outbox rows up.
 * Re-running is harmless: same-key same-payload rows count as duplicates.
 */
object SeedLoader {
    const val TAXPAYERS = 5
    const val YEAR = 2025

    fun load(
        dataSource: DataSource,
        seed: Long,
    ): SeedResult {
        val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(seed, TAXPAYERS, YEAR))
        check(parsed.rejections.isEmpty() && parsed.fileErrors.isEmpty()) { "generated seed data must parse cleanly" }
        val service = IngestionService(LedgerRepository(dataSource))
        var posted = 0
        var duplicates = 0
        for (activity in parsed.activities) {
            when (val result = service.submit(activity)) {
                is SubmitResult.Accepted -> if (result.alreadyExisted) duplicates++ else posted++
                else -> error("seed activity ${activity.idempotencyKey} refused: $result")
            }
        }
        return SeedResult(posted, duplicates)
    }
}
