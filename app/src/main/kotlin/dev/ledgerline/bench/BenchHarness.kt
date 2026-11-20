package dev.ledgerline.bench

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.SubmitResult
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.ledger.LedgerRepository
import javax.sql.DataSource

data class BenchConfig(val seed: Long, val activities: Int)

object BenchHarness {
    private const val EVENTS_PER_TAXPAYER = 40
    private const val YEAR = 2024

    /** Submits [BenchConfig.activities] generated activities one by one and times each submit. */
    fun run(
        config: BenchConfig,
        dataSource: DataSource,
    ): BenchResults {
        require(config.activities > 0) { "activities must be positive" }
        val taxpayers = (config.activities + EVENTS_PER_TAXPAYER - 1) / EVENTS_PER_TAXPAYER
        val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(config.seed, taxpayers, YEAR))
        check(parsed.rejections.isEmpty() && parsed.fileErrors.isEmpty()) { "generated input did not parse" }
        val workload = parsed.activities.take(config.activities)
        check(workload.size == config.activities) { "generator produced only ${workload.size} activities" }
        val service = IngestionService(LedgerRepository(dataSource))
        val latencies = ArrayList<Long>(workload.size)
        val start = System.nanoTime()
        for (activity in workload) {
            val t0 = System.nanoTime()
            val result = service.submit(activity)
            latencies += (System.nanoTime() - t0) / 1000
            check(result is SubmitResult.Accepted) { "activity ${activity.idempotencyKey} rejected: $result" }
        }
        val elapsed = maxOf(1L, (System.nanoTime() - start) / 1_000_000)
        return BenchResults(latencies, elapsed, workload.size)
    }
}
