package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.ReplayHash
import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L09F1
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.SourceText
import dev.ledgerline.testsupport.prepareChildLogin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Story L-10 (docs/stories/L-10-reconciliation-by-replay.md), the database and CLI parts: AC1 to AC5 on real tables,
 * the `reconcile` command and its exit codes, and AC9's wiring. The pure diff and hash are in
 * [L10ReconciliationSpec]. The story is Draft in the owner's flow; this is provisional.
 *
 * Every test tagged [Integration] needs Docker (Postgres and Redpanda by Testcontainers). The live pools are produced
 * by the real path: activities are posted through the ingestion service, relayed to a topic, and applied by a
 * `PoolConsumer` as the app role. Fixture F1 is loaded for AC1 to AC4 (the story's inputs name F1: three pools,
 * `TP-0001`/`SYN-AAA` holds 60000000 shares); the seed 42 year (L-01 `SyntheticGenerator.generate(42, 5, 2025)`) for AC5.
 * No tax value is expected from a person's reasoning: the oracle for the hash is the already specified pure engine
 * (`AcbEngine.replay` then `ReplayHash.of`), and for drift it is the edit the test made.
 *
 * API CONTRACT (Dev builds this; names are QE proposals), package `dev.ledgerline.app`:
 *
 * ```kotlin
 * object ReconcileCommand {
 *     /** 0 = no drift, 1 = drift, 2 = error (a failure to read, reported on [out]; never thrown). */
 *     fun run(
 *         dataSource: DataSource,
 *         out: Appendable,
 *         reportFile: java.nio.file.Path? = null,
 *         engine: AcbEngine = AcbEngine(CaIta2026v1),
 *     ): Int
 * }
 * fun main(args: Array<String>)   // `reconcile` as args[0] runs the command and exits with its code
 * ```
 *
 * Behaviour the tests rely on:
 * - Replay: read every activity in `ledger.activity` of every taxpayer, except those with a reversal row (the same
 *   set the consumer applies; see `ActivityStore`), replay them with the engine used by the consumer (canonical order,
 *   `Reconciler`), and diff against `calc.pool` (`PoolStore`). The replay is in memory: `recon` is not required.
 * - `out` receives, in this order: one summary line
 *   `reconcile: pools=<n> drift=<n> replayHash=<64 lower-case hex> liveHash=<64 lower-case hex>` (story example), then one
 *   line per drift item, `drift <json>` (no other line starts with `drift `), JSON as in the story, for example
 *   `drift {"taxpayerId":"TP-0001","securityId":"SYN-AAA","kind":"FIELD_MISMATCH","field":"shares","live":59000000,"replay":60000000}`.
 *   `MISSING_IN_LIVE` and `EXTRA_IN_LIVE` items carry `taxpayerId`, `securityId`, `kind`; their `field`, `live` and
 *   `replay` are null or absent. Output is a pure function of the database state: no timestamps, no durations, no
 *   host names. Drift order is the `Reconciler`'s: (taxpayerId, securityId), then field. On error `out` receives one
 *   line starting `reconcile: error` and NO summary line.
 * - `reportFile`, when not null, receives the JSON report
 *   `{"poolsCompared":n,"drift":[<items as above>],"replayHash":"..","liveHash":".."}` (extra keys allowed), parent
 *   directories created. The CLI writes it to `build/reconciliation/report.json` under its working directory (story
 *   interface proposal; Dev may refine, in which case QE updates [L10.DEFAULT_REPORT]).
 * - Read-only: no INSERT, UPDATE, DELETE, DDL, TRUNCATE, `SET`-persisting or advisory-lock-leaking work against `ledger`
 *   or `calc`; it must work as a login that only has SELECT on those two schemas.
 * - The CLI (`MainKt reconcile`) reads `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` like the service, and does
 *   NOT run migrations, start Ktor, the relay or the consumer, or contact Kafka. It exits with the command's code.
 *
 * NOT COVERED here: AC6 (two JVMs, TZ and locale: needs a CI job), AC7 (three stacks, shuffle), AC8 (reconciling while
 * the consumer is mid-stream: the watermark decision is still open with the owner), and the part of AC9 that says the
 * job runs after each chaos scenario (the chaos scenarios of L-12 do not exist yet; only the wiring is tested).
 */
class L10ReconcileCommandSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val integration = setOf(Integration)
    val engine = AcbEngine(CaIta2026v1)

    /** A fresh stack with fixture F1 posted and applied by the consumer. */
    fun f1() =
        L10.env(pg, broker).also { e ->
            L10.postAndApply(e, L09F1.f1().map { it.activity })
            // Precondition of AC2: the consumer left 60000000 shares in TP-0001/SYN-AAA (F1: 100 - 60 + 20 shares).
            L10.livePool(e.db, L10.TP1, L10.AAA)!!.first shouldBe 60_000_000L
        }

    val aaaFieldMismatch =
        L10.fieldMismatch("TP-0001", "SYN-AAA", "shares", live = 59_000_000L, replay = 60_000_000L)

    /** The replay hash of F1 by the pure engine over the story's activities: an oracle independent of the CLI. */
    val f1ReplayHash = ReplayHash.of(engine.replay(L09F1.f1()).pools)

    test("L-10 AC1: clean F1 run exits 0 with poolsCompared=3, drift=[] and replayHash equal to liveHash")
        .config(tags = integration) {
            val e = f1()
            val report = L10.tempDir().resolve("report.json")
            val run = L10.run(e.db.app, report)
            run.exit shouldBe 0
            val s = L10.summary(run.out)
            s.pools shouldBe 3
            s.drift shouldBe 0
            s.replayHash shouldBe s.liveHash
            s.replayHash shouldBe f1ReplayHash
            L10.driftItems(run.out).shouldBeEmpty()

            val json = L10.json(Files.readString(report)).jsonObject
            json.getValue("poolsCompared").jsonPrimitive.content shouldBe "3"
            json.getValue("drift").jsonArray shouldBe JsonArray(emptyList())
            json.getValue("replayHash").jsonPrimitive.content shouldBe s.replayHash
            json.getValue("liveHash").jsonPrimitive.content shouldBe s.liveHash
        }

    test("L-10 AC1: `MainKt reconcile` in a fresh JVM exits 0, prints the summary and writes the default report")
        .config(tags = integration) {
            val e = f1()
            prepareChildLogin(e.db)
            val dir = L10.tempDir()
            val (code, out) = L10.launchMainReconcile(e.db, dir).await()
            withClueOf(out) { code shouldBe 0 }
            val s = L10.summary(out)
            s.pools shouldBe 3
            s.drift shouldBe 0
            s.replayHash shouldBe s.liveHash
            val json = L10.json(Files.readString(dir.resolve(L10.DEFAULT_REPORT))).jsonObject
            json.getValue("poolsCompared").jsonPrimitive.content shouldBe "3"
            json.getValue("replayHash").jsonPrimitive.content shouldBe s.replayHash
        }

    test(
        "L-10 AC2: shares of TP-0001/SYN-AAA 60000000 -> 59000000 gives exactly one FIELD_MISMATCH, exit 1",
    )
        .config(tags = integration) {
            val e = f1()
            val clean = L10.summary(L10.run(e.db.app).out)
            // The app role may be read-only on calc in a hardened deployment, so tamper as the table owner.
            L10.execAsOwner(
                e.db,
                "UPDATE calc.pool SET shares = 59000000 WHERE taxpayer_id = 'TP-0001' AND security_id = 'SYN-AAA'",
            )
            val report = L10.tempDir().resolve("nested/report.json")
            val run = L10.run(e.db.app, report)
            run.exit shouldBe 1
            val s = L10.summary(run.out)
            s.drift shouldBe 1
            s.pools shouldBe 3
            s.replayHash shouldNotBe s.liveHash
            s.replayHash shouldBe clean.replayHash
            s.liveHash shouldNotBe clean.liveHash
            L10.driftItems(run.out) shouldContainExactly listOf(aaaFieldMismatch)
            L10.json(Files.readString(report)).jsonObject.getValue("drift").jsonArray shouldContainExactly
                listOf(aaaFieldMismatch)
        }

    test("L-10 AC2: a tampered totalAcb is reported as field totalAcb with both values, and shares are not")
        .config(tags = integration) {
            val e = f1()
            val (shares, acb) = L10.livePool(e.db, L10.TP1, L10.BBB)!!
            L10.execAsOwner(
                e.db,
                "UPDATE calc.pool SET total_acb = total_acb + 1 " +
                    "WHERE taxpayer_id = 'TP-0001' AND security_id = 'SYN-BBB'",
            )
            val run = L10.run(e.db.app)
            run.exit shouldBe 1
            val items = L10.driftItems(run.out)
            items shouldContainExactly
                listOf(
                    L10.fieldMismatch("TP-0001", "SYN-BBB", "totalAcb", live = acb + 1, replay = acb),
                )
            shares shouldBe 50_000_000L
        }

    test("L-10 AC2: `MainKt reconcile` exits 1 on drift and prints the drift line")
        .config(tags = integration) {
            val e = f1()
            L10.execAsOwner(
                e.db,
                "UPDATE calc.pool SET shares = 59000000 WHERE taxpayer_id = 'TP-0001' AND security_id = 'SYN-AAA'",
            )
            prepareChildLogin(e.db)
            val dir = L10.tempDir()
            val (code, out) = L10.launchMainReconcile(e.db, dir).await()
            withClueOf(out) { code shouldBe 1 }
            L10.driftItems(out) shouldContainExactly listOf(aaaFieldMismatch)
            val drift = L10.json(Files.readString(dir.resolve(L10.DEFAULT_REPORT))).jsonObject.getValue("drift")
            drift.jsonArray shouldContainExactly listOf(aaaFieldMismatch)
        }

    test("L-10 AC3: a deleted live pool and an extra live pool are reported as MISSING_IN_LIVE and EXTRA_IN_LIVE")
        .config(tags = integration) {
            val e = f1()
            L10.execAsOwner(
                e.db,
                "DELETE FROM calc.pool WHERE taxpayer_id = 'TP-0001' AND security_id = 'SYN-BBB'",
            )
            L10.execAsOwner(
                e.db,
                "INSERT INTO calc.pool (taxpayer_id, security_id, shares, total_acb) " +
                    "VALUES ('TP-0003', 'SYN-ZZZ', 1000000, 5000)",
            )
            val run = L10.run(e.db.app)
            run.exit shouldBe 1
            val s = L10.summary(run.out)
            s.drift shouldBe 2
            // Union of replayed keys (3, incl. the deleted one) and live keys (3, incl. the extra one).
            s.pools shouldBe 4
            val items = L10.driftItems(run.out).map { it.jsonObject }
            items.map { it.text("taxpayerId") to it.text("securityId") }
                .shouldContainExactly("TP-0001" to "SYN-BBB", "TP-0003" to "SYN-ZZZ")
            items.map { it.text("kind") }
                .shouldContainExactly("MISSING_IN_LIVE", "EXTRA_IN_LIVE")
        }

    test(
        "L-10 AC4: ledger and calc row hashes are equal before and after a clean run, and after a run that finds drift",
    )
        .config(tags = integration) {
            val e = f1()
            val before = L10.schemaHash(e.db, "ledger", "calc")
            L10.run(e.db.app).exit shouldBe 0
            L10.schemaHash(e.db, "ledger", "calc") shouldBe before

            L10.execAsOwner(
                e.db,
                "UPDATE calc.pool SET shares = 59000000 WHERE taxpayer_id = 'TP-0001' AND security_id = 'SYN-AAA'",
            )
            val tampered = L10.schemaHash(e.db, "ledger", "calc")
            tampered shouldNotBe before
            L10.run(e.db.app).exit shouldBe 1
            L10.schemaHash(e.db, "ledger", "calc") shouldBe tampered
        }

    test("L-10 AC4: the command runs as a login that can only SELECT on ledger and calc, and then changes nothing")
        .config(tags = integration) {
            val e = f1()
            val before = L10.schemaHash(e.db, "ledger", "calc")
            val run = L10.run(L10.readOnlyLogin(e.db))
            withClueOf(run.out) { run.exit shouldBe 0 }
            L10.summary(run.out).replayHash shouldBe f1ReplayHash
            L10.schemaHash(e.db, "ledger", "calc") shouldBe before
        }

    test("L-10 AC4: two runs in a row print identical output (nothing is left over from the first)")
        .config(tags = integration) {
            val e = f1()
            val first = L10.run(e.db.app)
            val second = L10.run(e.db.app)
            second shouldBe first
        }

    test("L-10 AC5: the seed 42 year, reconciled twice, prints the same replayHash and the same report")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            val parsed = CanonicalCsv.parse(SyntheticGenerator.generate(42L, 5, 2025))
            parsed.rejections.shouldBeEmpty()
            L10.postAndApply(e, parsed.activities)
            val before = L10.schemaHash(e.db, "ledger", "calc")
            val first = L10.run(e.db.app)
            val second = L10.run(e.db.app)
            first.exit shouldBe second.exit
            L10.summary(first.out).replayHash shouldBe L10.summary(second.out).replayHash
            second.out shouldBe first.out
            L10.schemaHash(e.db, "ledger", "calc") shouldBe before
        }

    test("L-10 error: a database without the calc schema exits 2 with an error line and no summary")
        .config(tags = integration) {
            val db = pg.freshDatabase() // ledger migrated, calc not
            val run = L10.run(db.app)
            run.exit shouldBe 2
            run.out shouldContain "reconcile: error"
            run.out.lines().none { it.startsWith("reconcile: pools=") } shouldBe true
        }

    test("L-10 error: a data source that cannot connect exits 2 and does not throw") {
        val broken =
            java.lang.reflect.Proxy.newProxyInstance(
                DataSource::class.java.classLoader,
                arrayOf(DataSource::class.java),
            ) { _, m, _ ->
                if (m.name == "getConnection") {
                    throw SQLException(
                        "connection refused",
                    )
                } else {
                    null
                }
            } as DataSource
        val run = L10.run(broken)
        run.exit shouldBe 2
        run.out shouldContain "reconcile: error"
    }

    test("L-10 AC9 (wiring only): CI runs reconcile and attaches report.json as an artifact") {
        // The chaos scenarios of L-12 do not exist yet, so "after each chaos scenario" is not tested; only that the
        // workflow runs the command, lets its exit code fail the job (no `|| true`, no continue-on-error), and
        // uploads the report even when the job failed.
        val ci = SourceText.read(".github/workflows/ci.yml")
        ci shouldContain "reconcile"
        ci shouldContain "reconciliation/report.json"
        ci shouldContain "actions/upload-artifact"
        ci.lines().filter { it.contains("reconcile") }.none { it.contains("|| true") } shouldBe true
        ci.contains("continue-on-error") shouldBe false
    }
})

private inline fun withClueOf(
    output: String,
    block: () -> Unit,
) = io.kotest.assertions.withClue("process output:\n$output", block)

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
