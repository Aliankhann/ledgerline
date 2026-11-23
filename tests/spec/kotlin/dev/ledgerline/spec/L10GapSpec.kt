package dev.ledgerline.spec

import dev.ledgerline.ingest.CanonicalCsv
import dev.ledgerline.ingest.SyntheticGenerator
import dev.ledgerline.testsupport.CHILD_APP_PASSWORD
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.RunningChild
import dev.ledgerline.testsupport.prepareChildLogin
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Story L-10 gap tests for the traceability gate: the end-to-end parts of AC6 and AC7 (the pure parts are in
 * [L10ReconciliationSpec]). Both are tagged [Integration] (Docker), compile without it and were NOT run where they
 * were written.
 *
 * NOT COVERED, and why:
 * - AC8 (reconcile while the consumer is mid-stream): the story marks it "proposed" and the watermark decision is
 *   open with the owner; the report has no watermark field and no behaviour is specified to test.
 * - AC9 "after each chaos scenario": the wiring in `ci.yml` is tested in [L10ReconcileCommandSpec]; each L-12 chaos
 *   scenario ends with `reconcile` (`reconcileAndReport`, `tests/chaos`).
 *
 * The oracle is equality only (story note: no tax values): between two JVM configurations (AC6), and between three
 * stacks fed in three arrival orders (AC7).
 */
class L10GapSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val integration = setOf(Integration)

    /** The seed 42 year of L-01 AC7: 5 taxpayers, 2025, in file order. */
    fun seed42() =
        CanonicalCsv.parse(SyntheticGenerator.generate(42L, 5, 2025)).also {
            it.rejections.shouldBeEmpty()
            it.fileErrors.shouldBeEmpty()
        }.activities

    test("L-10 AC6: one ledger reconciled in two JVMs (UTC en-CA, Toronto fr-CA) gives one replayHash")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            L10.postAndApply(e, seed42())
            prepareChildLogin(e.db)

            fun reconcileIn(
                tz: String,
                language: String,
                country: String,
            ): L10.Summary {
                val dir = L10.tempDir()
                val (code, out) = launchReconcile(e.db.url, dir, tz, language, country).await()
                withClue("reconcile under TZ=$tz locale $language-$country exited $code:\n$out") { code shouldBe 0 }
                return L10.summary(out)
            }

            val utc = reconcileIn("UTC", "en", "CA")
            val toronto = reconcileIn("America/Toronto", "fr", "CA")
            utc.drift shouldBe 0
            toronto.drift shouldBe 0
            toronto.replayHash shouldBe utc.replayHash
            toronto.liveHash shouldBe utc.liveHash
            utc.replayHash shouldBe utc.liveHash
        }

    test(
        "L-10 AC7: seed 42 submitted in file order, reverse order and a shuffle (seed 7): same replayHash and liveHash",
    )
        .config(tags = integration) {
            val fileOrder = seed42()
            val orders =
                mapOf(
                    "file order" to fileOrder,
                    "reverse file order" to fileOrder.reversed(),
                    "shuffle seed 7" to fileOrder.shuffled(java.util.Random(7L)),
                )
            val summaries =
                orders.mapValues { (name, work) ->
                    // A fresh stack (database, topic, consumer group) per order.
                    val e = L10.env(pg, broker)
                    L10.postAndApply(e, work, group = "l10-ac7-${name.replace(' ', '-')}")
                    val run = L10.run(e.db.app)
                    withClue("$name: reconcile exit ${run.exit}\n${run.out}") { run.exit shouldBe 0 }
                    L10.summary(run.out)
                }
            val reference = summaries.getValue("file order")
            for ((name, s) in summaries) {
                withClue("order: $name") {
                    s.drift shouldBe 0
                    s.pools shouldBe reference.pools
                    s.replayHash shouldBe reference.replayHash
                    s.liveHash shouldBe reference.liveHash
                }
            }
        }
})

/**
 * `java -Duser.timezone=<tz> -Duser.language=<language> -Duser.country=<country> ... MainKt reconcile` in [workDir]
 * against the database at [url] as the app role (call `prepareChildLogin` first). `TZ`, `LANG` and `LC_ALL` are set
 * too, so neither the JVM property nor the operating system can leak the host's own zone or locale. Like
 * [L10.launchMainReconcile], Kafka is deliberately unreachable.
 */
private fun launchReconcile(
    url: String,
    workDir: Path,
    tz: String,
    language: String,
    country: String,
): RunningChild {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val cmd =
        listOf(
            java,
            "-Duser.timezone=$tz",
            "-Duser.language=$language",
            "-Duser.country=$country",
            "-cp",
            System.getProperty("java.class.path"),
            "dev.ledgerline.app.MainKt",
            "reconcile",
        )
    val builder = ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true)
    builder.environment().apply {
        put("TZ", tz)
        put("LANG", "${language}_$country.UTF-8")
        put("LC_ALL", "${language}_$country.UTF-8")
        put("DATABASE_URL", url)
        put("DATABASE_USER", PostgresLedger.APP_ROLE)
        put("DATABASE_PASSWORD", CHILD_APP_PASSWORD)
        put("KAFKA_BOOTSTRAP", "127.0.0.1:1")
        put("HTTP_PORT", "0")
    }
    val p = builder.start()
    return RunningChild(p, CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() })
}
