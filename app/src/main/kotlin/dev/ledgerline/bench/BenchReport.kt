package dev.ledgerline.bench

/** One latency per write request in microseconds, in arrival order, plus the wall time of the whole load. */
data class BenchResults(val latenciesMicros: List<Long>, val elapsedMillis: Long, val activities: Int)

data class ImportBench(val rows: Int, val elapsedMillis: Long)

data class ReplayBench(
    val events: Int,
    val loadMillis: Long,
    val replayMillis: Long,
    val reconcileMillis: Long,
    val reconcileExit: Int,
)

/** Optional extra sections of the report: a concurrent burst, a CSV import and replay/reconcile. */
data class BenchExtras(
    val burst: BenchResults?,
    val burstWriters: Int,
    val import: ImportBench?,
    val replay: ReplayBench?,
)

data class Hardware(val cpuModel: String, val logicalCores: Int, val memoryGiB: Int, val os: String)

/** Pure report generator: integer arithmetic only, no clock, no host lookups. */
object BenchReport {
    /** Nearest-rank percentile: element ceil(percent / 100 * n), 1-based, of the sorted samples. */
    fun percentileMicros(
        samples: List<Long>,
        percent: Int,
    ): Long {
        require(samples.isNotEmpty()) { "no samples" }
        require(percent in 1..100) { "percent must be 1..100, was $percent" }
        val rank = (percent.toLong() * samples.size + 99) / 100
        return samples.sorted()[(rank - 1).toInt()]
    }

    fun render(
        results: BenchResults,
        hardware: Hardware,
        seed: Long,
        gitSha: String,
        extras: BenchExtras? = null,
    ): String {
        require(results.latenciesMicros.isNotEmpty()) { "no latency samples" }
        require(results.elapsedMillis > 0) { "elapsedMillis must be positive" }
        val tenths = results.activities.toLong() * 10_000 / results.elapsedMillis
        return buildString {
            appendLine("# Ledgerline burst benchmark")
            appendLine()
            appendLine("- Seed: $seed")
            appendLine("- Commit: $gitSha")
            val hw = hardware
            appendLine("- Hardware: ${hw.cpuModel}, ${hw.logicalCores} cores, ${hw.memoryGiB} GiB, ${hw.os}")
            appendLine("- Activities: ${results.activities}")
            appendLine()
            appendLine("| Measurement | Actual |")
            appendLine("| --- | --- |")
            appendLine("| ingest throughput (activities/s) | ${tenths / 10}.${tenths % 10} |")
            for (p in listOf(50, 95, 99)) {
                appendLine("| write latency p$p (ms) | ${millis(percentileMicros(results.latenciesMicros, p))} |")
            }
            extras?.let { appendExtras(it) }
        }
    }

    private fun StringBuilder.appendExtras(x: BenchExtras) {
        appendLine()
        appendLine("| Measurement | Actual |")
        appendLine("| --- | --- |")
        x.burst?.let { b ->
            appendLine(
                "| burst throughput, ${x.burstWriters} writers (activities/s) | ${rate(
                    b.activities.toLong(),
                    b.elapsedMillis,
                )} |",
            )
            for (p in listOf(50, 95, 99)) {
                appendLine("| burst write latency p$p (ms) | ${millis(percentileMicros(b.latenciesMicros, p))} |")
            }
        }
        x.import?.let {
            appendLine(
                "| CSV import end to end, ${it.rows} rows (rows/s) | ${rate(it.rows.toLong(), it.elapsedMillis)} |",
            )
        }
        x.replay?.let { r ->
            appendLine("| ledger load, ${r.events} events (events/s) | ${rate(r.events.toLong(), r.loadMillis)} |")
            appendLine("| ACB replay, ${r.events} events (events/s) | ${rate(r.events.toLong(), r.replayMillis)} |")
            appendLine("| reconcile command (ms, exit ${r.reconcileExit}) | ${r.reconcileMillis} |")
        }
    }

    private fun rate(
        count: Long,
        millis: Long,
    ): String = (count * 10_000 / millis).let { "${it / 10}.${it % 10}" }

    private fun millis(micros: Long): String = "${micros / 1000}.${(micros % 1000).toString().padStart(3, '0')}"
}
