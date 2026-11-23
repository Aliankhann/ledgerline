package dev.ledgerline.spec

import dev.ledgerline.bench.BenchConfig
import dev.ledgerline.bench.BenchHarness
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.entryKeys
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.MessageDigest

/**
 * Story L-13 gap tests for the traceability gate. Only AC6 can be tested today, and only at the level of the workload
 * (the report has no `workloadSha256` field yet).
 *
 * NOT COVERED, and why (no production change was made; none of this is a defect, the Dev change notes say so):
 * - AC1 (exit code, `report.json`, `latencies.csv`, `lag.csv`, `report.md` in `bench/reports/<ts>-seed42/`), AC4
 *   (missing `bench/targets.yml`), AC5 (`Met` column, exit 0 on a miss), AC7 (`INVALID` status, no row in
 *   `docs/benchmarks.md`), AC8 (`COMPARE=1`) and AC9 (`make bench-smoke` in CI): there is no `make bench` or
 *   `make bench-smoke` target, no `bench/targets.yml`, no CI step, and `BenchReport` renders only throughput and
 *   p50/p95/p99 with an `Actual` column. A test would be red for a feature that is not built; owner inputs C10
 *   (targets) and B3 (hardware) are still open.
 * - AC2 fields `dockerVersion`, `jvmVersion`, `postgresVersion`, `redpandaVersion`, `gitDirty`, workload parameters
 *   and `report.json` itself: `Hardware` has only cpuModel, logicalCores, memoryGiB, os. (Seed, hardware and commit
 *   are tested in [L13BenchReportSpec].)
 * - AC3 rows for burst peak lag, burst drain time, replay time and recompute after 1, 6 and 12 months, and the
 *   `Target` and `Met` columns: not rendered yet.
 */
class L13GapSpec : FunSpec({

    fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    test("L-13 AC6 (workload part): the same seed generates byte-identical input, another seed does not") {
        val a = sha256(SyntheticGenerator.generate(42L, 3, 2024))
        val b = sha256(SyntheticGenerator.generate(42L, 3, 2024))
        a shouldBe b
        sha256(SyntheticGenerator.generate(43L, 3, 2024)) shouldNotBe a
    }

    test("L-13 AC6 (harness part): two runs with seed 42 post the same activities, whatever the timings")
        .config(tags = setOf(Integration)) {
            PostgresLedger().use { pg ->
                val n = 120
                // Oracle: the first n activities of the generator's output for the harness's own parameters
                // (ceil(n / 40) taxpayers, year 2024), parsed independently of the harness.
                val expected =
                    CanonicalCsv.parse(SyntheticGenerator.generate(42L, (n + 39) / 40, 2024))
                        .activities.take(n).map { it.idempotencyKey }.sorted()
                val runs =
                    (1..2).map {
                        val db = pg.freshDatabase()
                        BenchHarness.run(BenchConfig(seed = 42, activities = n), db.app).activities shouldBe n
                        db.entryKeys()
                    }
                runs[0] shouldBe expected
                runs[1] shouldBe expected
            }
        }
})
