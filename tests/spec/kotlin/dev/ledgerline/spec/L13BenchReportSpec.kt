package dev.ledgerline.spec

import dev.ledgerline.bench.BenchConfig
import dev.ledgerline.bench.BenchHarness
import dev.ledgerline.bench.BenchReport
import dev.ledgerline.bench.BenchResults
import dev.ledgerline.bench.Hardware
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Story L-13 (docs/stories/L-13-burst-benchmark.md), kept light: the report generator is pure and tested exactly;
 * the harness gets one end-to-end smoke test. Story status is Draft, so these tests are provisional. No test here
 * asserts a target or a timing bound: targets are owner input C10 and hardware is B3. The story's AC1, AC4 to AC9
 * (make targets, exit codes, targets.yml, INVALID runs, comparison, CI) are not covered here; see the Test map.
 *
 * API contract (package `dev.ledgerline.bench`, in any module `:tests` already depends on, for example `app`):
 *
 * - `data class BenchResults(val latenciesMicros: List<Long>, val elapsedMillis: Long, val activities: Int)`:
 *   one latency per write request in microseconds, in arrival order; wall time of the whole load; number of
 *   activities accepted.
 * - `data class Hardware(val cpuModel: String, val logicalCores: Int, val memoryGiB: Int, val os: String)`.
 * - `object BenchReport` with:
 *   - `fun percentileMicros(samples: List<Long>, percent: Int): Long`: nearest-rank on the sorted samples, that is
 *     element `ceil(percent / 100 * n)` (1-based). Input order is irrelevant; empty samples throw
 *     `IllegalArgumentException`; `percent` outside 1..100 throws `IllegalArgumentException`.
 *   - `fun render(results: BenchResults, hardware: Hardware, seed: Long, gitSha: String): String`: markdown. It
 *     contains the table rows `| write latency p50 (ms) | 50.000 |`, same for p95 and p99 (milliseconds with exactly
 *     three decimals, integer arithmetic only), and `| ingest throughput (activities/s) | 500.0 |` (activities *
 *     1000 / elapsedMillis, truncated to one decimal), plus the lines `- Seed: <seed>`, `- Commit: <gitSha>` and
 *     `- Hardware: <cpuModel>, <logicalCores> cores, <memoryGiB> GiB, <os>`. It is deterministic: no clock, no
 *     random, no host lookups, and the same input always gives the same string. Empty latencies throw
 *     `IllegalArgumentException`; `elapsedMillis <= 0` throws `IllegalArgumentException`.
 * - `data class BenchConfig(val seed: Long, val activities: Int)` and
 *   `object BenchHarness { fun run(config: BenchConfig, dataSource: javax.sql.DataSource): BenchResults }`: generates
 *   `activities` activities with the L-01 generator from `seed`, submits them through the ingestion service against
 *   the migrated database, and returns the measurements. It throws if any activity is rejected.
 */
class L13BenchReportSpec :
    FunSpec({
        val hw = Hardware(cpuModel = "Test CPU 9000", logicalCores = 8, memoryGiB = 32, os = "Linux 6.1")

        fun ms(n: Long) = n * 1000

        test("L-13 AC3: percentiles are exact nearest-rank values, whatever the input order") {
            val shuffled = (1L..100L).map { ms(it) }.shuffled(java.util.Random(7))
            BenchReport.percentileMicros(shuffled, 50) shouldBe ms(50)
            BenchReport.percentileMicros(shuffled, 95) shouldBe ms(95)
            BenchReport.percentileMicros(shuffled, 99) shouldBe ms(99)
            val four = listOf(ms(40), ms(10), ms(30), ms(20))
            BenchReport.percentileMicros(four, 50) shouldBe ms(20)
            BenchReport.percentileMicros(four, 99) shouldBe ms(40)
            BenchReport.percentileMicros(listOf(5L), 99) shouldBe 5L
            shouldThrow<IllegalArgumentException> { BenchReport.percentileMicros(emptyList(), 50) }
            shouldThrow<IllegalArgumentException> { BenchReport.percentileMicros(four, 0) }
        }

        test("L-13 AC3: the report has p50, p95, p99 and throughput rows with exact values") {
            val r = BenchResults((1L..100L).map { ms(it) }, elapsedMillis = 2000, activities = 1000)
            val md = BenchReport.render(r, hw, seed = 42, gitSha = "abc1234")
            md shouldContain "| write latency p50 (ms) | 50.000 |"
            md shouldContain "| write latency p95 (ms) | 95.000 |"
            md shouldContain "| write latency p99 (ms) | 99.000 |"
            md shouldContain "| ingest throughput (activities/s) | 500.0 |"
            val sub = BenchReport.render(BenchResults(listOf(1234L, 2L), 3000, 1), hw, 1, "x")
            sub shouldContain "| write latency p99 (ms) | 1.234 |"
            sub shouldContain "| ingest throughput (activities/s) | 0.3 |"
        }

        test("L-13 AC2: the report records seed, hardware and commit") {
            val md = BenchReport.render(BenchResults(listOf(1000L), 10, 1), hw, seed = 42, gitSha = "abc1234")
            md shouldContain "- Seed: 42"
            md shouldContain "- Commit: abc1234"
            md shouldContain "- Hardware: Test CPU 9000, 8 cores, 32 GiB, Linux 6.1"
        }

        test("L-13 AC6: rendering is deterministic and ignores latency arrival order") {
            val samples = (1L..50L).map { it * 700 }
            val a = BenchReport.render(BenchResults(samples, 900, 50), hw, 42, "abc1234")
            val b = BenchReport.render(BenchResults(samples.reversed(), 900, 50), hw, 42, "abc1234")
            a shouldBe BenchReport.render(BenchResults(samples, 900, 50), hw, 42, "abc1234")
            a shouldBe b
            a shouldNotContain "NaN"
        }

        test("L-13 AC3: a report cannot be rendered from no measurements") {
            shouldThrow<IllegalArgumentException> {
                BenchReport.render(BenchResults(emptyList(), 100, 0), hw, 42, "abc1234")
            }
            shouldThrow<IllegalArgumentException> {
                BenchReport.render(BenchResults(listOf(1L), 0, 1), hw, 42, "abc1234")
            }
        }

        test("L-13 AC1: a tiny burst runs end to end and yields a report with non-zero throughput")
            .config(tags = setOf(Integration)) {
                PostgresLedger().use { pg ->
                    val results = BenchHarness.run(BenchConfig(seed = 42, activities = 200), pg.freshDatabase().app)
                    results.activities shouldBe 200
                    results.latenciesMicros.size shouldBe 200
                    val md = BenchReport.render(results, hw, 42, "smoke")
                    md shouldContain "| write latency p99 (ms) |"
                    // Throughput row exists and is not zero (no timing bound: targets are owner input C10).
                    md shouldContain "| ingest throughput (activities/s) |"
                    md shouldNotContain "| ingest throughput (activities/s) | 0.0 |"
                }
            }
    })
