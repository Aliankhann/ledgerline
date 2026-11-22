package dev.ledgerline.testsupport

import dev.ledgerline.app.ReconcileCommand
import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.core.Activity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource

/**
 * Support for the database and CLI parts of story L-10 (`L10ReconcileCommandSpec`).
 *
 * The command under test is the QE-proposed API `dev.ledgerline.app.ReconcileCommand` (see the KDoc of the spec).
 */
object L10 {
    const val TP1 = L09F1.TP1
    const val AAA = L09F1.AAA
    const val BBB = L09F1.BBB

    /** `reconcile: pools=<n> drift=<n> replayHash=<64 hex> liveHash=<64 hex>`: the one-line summary of the story. */
    private val SUMMARY =
        Regex("^reconcile: pools=(\\d+) drift=(\\d+) replayHash=([0-9a-f]{64}) liveHash=([0-9a-f]{64})$")

    /** The summary line of a run's output. */
    data class Summary(val pools: Int, val drift: Int, val replayHash: String, val liveHash: String)

    /** The single summary line in [text] (fails if there is none or more than one). */
    fun summary(text: String): Summary {
        val found = text.lines().mapNotNull { SUMMARY.matchEntire(it) }
        check(found.size == 1) { "expected exactly one summary line, found ${found.size} in:\n$text" }
        val g = found.single().groupValues
        return Summary(g[1].toInt(), g[2].toInt(), g[3], g[4])
    }

    /** The drift items printed in [text], one `drift <json>` line each, as parsed JSON (whitespace-insensitive). */
    fun driftItems(text: String): List<JsonElement> =
        text.lines().filter { it.startsWith("drift ") }.map { Json.parseToJsonElement(it.removePrefix("drift ")) }

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** The story's drift item for a `FIELD_MISMATCH`, as parsed JSON (key order and whitespace do not matter). */
    fun fieldMismatch(
        taxpayer: String,
        security: String,
        field: String,
        live: Long,
        replay: Long,
    ): JsonElement =
        json(
            "{\"taxpayerId\":\"$taxpayer\",\"securityId\":\"$security\",\"kind\":\"FIELD_MISMATCH\"," +
                "\"field\":\"$field\",\"live\":$live,\"replay\":$replay}",
        )

    /** What a run printed and returned. */
    data class Run(val exit: Int, val out: String)

    /** Runs the command in-process as [ds], collecting everything it prints. */
    fun run(
        ds: DataSource,
        reportFile: Path? = null,
    ): Run {
        val sb = StringBuilder()
        val exit = ReconcileCommand.run(ds, sb, reportFile)
        return Run(exit, sb.toString())
    }

    /** A fresh temp directory for report files (deleted on JVM exit by the OS temp cleaner; tests are small). */
    fun tempDir(): Path = Files.createTempDirectory("l10-")

    /**
     * Posts [work] through the real ingestion path (ledger, outbox, relay, topic) and applies every event with a
     * [PoolConsumer] as the app role, so `calc.pool` is the consumer's own state. Fails if the inbox does not hold
     * exactly one row per outbox event afterwards. Calc migrations must already have run on `e.db`.
     */
    fun postAndApply(
        e: L05Env,
        work: List<Activity>,
        group: String = "l10",
    ): List<PostedActivity> {
        val posted = e.postAndPublish(work)
        val engine = dev.ledgerline.calc.AcbEngine(dev.ledgerline.calc.CaIta2026v1)
        PoolConsumer(e.db.app, e.consumerConfig(group), engine, e.topic).use {
            it.pollUntilInbox(e.db, e.events().size)
        }
        check(e.db.inboxEventIds().size == e.events().size) {
            "consumer applied ${e.db.inboxEventIds().size} of ${e.events().size} events"
        }
        return posted
    }

    /** A migrated ledger and calc schema on a fresh database, a topic of its own, and the broker. */
    fun env(
        pg: PostgresLedger,
        broker: RedpandaBroker,
    ): L05Env = pg.l05Env(broker).also { CalcMigrations.migrate(it.db.owner) }

    /** The live pool row's `shares` and `total_acb` for ([taxpayer], [security]), read as the table owner. */
    fun livePool(
        db: LedgerDb,
        taxpayer: String,
        security: String,
    ): Pair<Long, Long>? =
        db.query(
            db.owner,
            "SELECT shares, total_acb FROM calc.pool WHERE taxpayer_id = '$taxpayer' AND security_id = '$security'",
        ).singleOrNull()?.let { it[0]!!.toLong() to it[1]!!.toLong() }

    fun execAsOwner(
        db: LedgerDb,
        sql: String,
    ) {
        db.failureOf(db.owner, sql)?.let { throw it }
    }

    /**
     * SHA-256 over every row of every base table in each of [schemas] (rows as Postgres row text, sorted, table by
     * table), read as the table owner. A schema that does not exist contributes nothing. This is the "row hash" of
     * L-10 AC4: it must be equal before and after any `reconcile` run, for `ledger` and `calc`.
     */
    fun schemaHash(
        db: LedgerDb,
        vararg schemas: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (schema in schemas) {
            val tables =
                db.query(
                    db.owner,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' " +
                        "AND table_type = 'BASE TABLE' ORDER BY 1",
                ).map { it[0]!! }
            for (t in tables) {
                md.update("table $schema.$t\n".toByteArray())
                db.query(db.owner, "SELECT r::text FROM $schema.$t r ORDER BY 1").forEach {
                    md.update((it[0] + "\n").toByteArray())
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates a login role that may only read `ledger` and `calc` (USAGE on both schemas, SELECT on their tables) and
     * returns a data source that really logs in as it. Roles are cluster-wide, so the name is unique per call.
     */
    fun readOnlyLogin(db: LedgerDb): DataSource {
        val role = "l10_reader_${System.nanoTime()}"
        execAsOwner(db, "CREATE ROLE $role NOLOGIN")
        for (schema in listOf("ledger", "calc")) {
            execAsOwner(db, "GRANT USAGE ON SCHEMA $schema TO $role")
            execAsOwner(db, "GRANT SELECT ON ALL TABLES IN SCHEMA $schema TO $role")
        }
        return db.loginAs(role, "l10-reader")
    }

    /**
     * Runs `java -cp <test classpath> dev.ledgerline.app.MainKt reconcile` in a fresh JVM, in [workDir], logged in to
     * [db] as the app role (call [prepareChildLogin] first). The Kafka address is deliberately unreachable: the
     * command must not touch Kafka, start the HTTP server, the relay or the consumer. Stdout and stderr are merged.
     */
    fun launchMainReconcile(
        db: LedgerDb,
        workDir: Path,
    ): RunningChild {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd =
            listOf(java, "-cp", System.getProperty("java.class.path"), "dev.ledgerline.app.MainKt", "reconcile")
        val builder = ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true)
        builder.environment().apply {
            put("DATABASE_URL", db.url)
            put("DATABASE_USER", PostgresLedger.APP_ROLE)
            put("DATABASE_PASSWORD", CHILD_APP_PASSWORD)
            put("KAFKA_BOOTSTRAP", "127.0.0.1:1")
            put("HTTP_PORT", "0")
        }
        val p = builder.start()
        return RunningChild(p, CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() })
    }

    /** The default report location of the CLI, relative to its working directory (story L-10 interface proposal). */
    const val DEFAULT_REPORT = "build/reconciliation/report.json"
}
