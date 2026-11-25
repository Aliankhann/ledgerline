package dev.ledgerline.bench

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.ReconcileCommand
import dev.ledgerline.app.SubmitResult
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.ActivityStore
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.ledger.LedgerRepository
import java.util.concurrent.Executors
import java.util.concurrent.Future
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

    /** Burst: [writers] threads share one generated workload (distinct keys from [run]) and submit concurrently. */
    fun runBurst(
        config: BenchConfig,
        dataSource: DataSource,
        writers: Int = 8,
    ): BenchResults {
        val workload = generate(config.seed, config.activities, YEAR - 2)
        val service = IngestionService(LedgerRepository(dataSource))
        val pool = Executors.newFixedThreadPool(writers)
        try {
            val start = System.nanoTime()
            val futures: List<Future<List<Long>>> =
                (0 until writers).map { w ->
                    pool.submit<List<Long>> {
                        val mine = workload.filterIndexed { i, _ -> i % writers == w }
                        mine.map { a ->
                            val t0 = System.nanoTime()
                            val r = service.submit(a)
                            val micros = (System.nanoTime() - t0) / 1000
                            check(r is SubmitResult.Accepted) { "burst ${a.idempotencyKey} rejected: $r" }
                            micros
                        }
                    }
                }
            val latencies = futures.flatMap { it.get() }
            val elapsed = maxOf(1L, (System.nanoTime() - start) / 1_000_000)
            return BenchResults(latencies, elapsed, workload.size)
        } finally {
            pool.shutdownNow()
        }
    }

    /** End-to-end CSV import of [BenchConfig.activities] rows through [IngestionService.importCsv]. */
    fun runImport(
        config: BenchConfig,
        dataSource: DataSource,
    ): ImportBench {
        val taxpayers = (config.activities + EVENTS_PER_TAXPAYER - 1) / EVENTS_PER_TAXPAYER
        val csv = SyntheticGenerator.generate(config.seed, taxpayers, YEAR - 1)
        val rows = csv.lines().count { it.isNotBlank() } - 1
        val start = System.nanoTime()
        val report = IngestionService(LedgerRepository(dataSource)).importCsv(csv)
        val elapsed = maxOf(1L, (System.nanoTime() - start) / 1_000_000)
        check(report.rejected.isEmpty() && report.fileErrors.isEmpty()) { "import had rejections" }
        return ImportBench(report.posted + report.duplicates, elapsed).also { check(it.rows == rows) { "row count" } }
    }

    /** Full in-memory ACB replay of every stored activity, then the reconcile command end to end. */
    fun runReplay(dataSource: DataSource): ReplayBench {
        val store = ActivityStore(dataSource)
        val taxpayers =
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT DISTINCT taxpayer_id FROM ledger.activity ORDER BY 1").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        val t0 = System.nanoTime()
        val history = taxpayers.flatMap { store.effectiveActivities(it) }
        val t1 = System.nanoTime()
        AcbEngine(CaIta2026v1).replay(history)
        val t2 = System.nanoTime()
        val exit = ReconcileCommand.run(dataSource, StringBuilder())
        val t3 = System.nanoTime()
        return ReplayBench(
            history.size,
            maxOf(1L, (t1 - t0) / 1_000_000),
            maxOf(1L, (t2 - t1) / 1_000_000),
            maxOf(1L, (t3 - t2) / 1_000_000),
            exit,
        )
    }

    private fun generate(
        seed: Long,
        count: Int,
        year: Int,
    ) = CanonicalCsv.parse(
        SyntheticGenerator.generate(seed, (count + EVENTS_PER_TAXPAYER - 1) / EVENTS_PER_TAXPAYER, year),
    )
        .also { check(it.rejections.isEmpty() && it.fileErrors.isEmpty()) { "generated input did not parse" } }
        .activities.take(count)
}
