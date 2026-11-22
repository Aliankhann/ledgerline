package dev.ledgerline.spec

import dev.ledgerline.app.ReconcileCommand
import dev.ledgerline.app.SeedLoader
import dev.ledgerline.app.SeedResult
import dev.ledgerline.ledger.outbox.OutboxStore
import dev.ledgerline.testsupport.ComposeFile
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L10
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.SeedSupport
import dev.ledgerline.testsupport.SourceText
import dev.ledgerline.testsupport.drainAndApply
import dev.ledgerline.testsupport.entryKeys
import dev.ledgerline.testsupport.unbalancedEntryUnits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Seed on start (plan item 6: "docker compose up starts everything and loads seeded synthetic data").
 * OWNER-INPUT: C32. Provisional: there is no approved story file; the source is the lead's request. Test names say
 * `SEED ACn` for the behaviours of that request, numbered in the order they appear below.
 *
 * API CONTRACT (Dev builds this; names are QE proposals), package `dev.ledgerline.app`:
 *
 * ```kotlin
 * data class SeedResult(val posted: Int, val duplicates: Int)
 * object SeedLoader {
 *     /**
 *      * Posts the activities of `SyntheticGenerator.generate(seed, 5, 2025)` (L-01 AC7 parameters), parsed with
 *      * `CanonicalCsv`, one by one through the normal ingestion path (`IngestionService.submit`, so ledger entry,
 *      * idempotency key and outbox row in one transaction), as the app role behind [dataSource].
 *      * `posted` counts activities newly stored; `duplicates` counts those whose key was already stored with the
 *      * same payload. posted + duplicates == the generator's activity count. Never throws for a duplicate.
 *      * Does not start the relay or the consumer and does not touch Kafka.
 *      */
 *     fun load(dataSource: javax.sql.DataSource, seed: Long): SeedResult
 * }
 * ```
 *
 * Wiring contract for `app/src/main/kotlin/dev/ledgerline/app/Main.kt` (the text checks below rely on it): Main reads
 * the literal env name `SEED_YEAR`; only when it is set does Main call `SeedLoader.load(` (exactly one call), after
 * migrations and before `embeddedServer(...).start(...)`. The value is parsed as a Long seed. No SEED_YEAR, no seeding.
 * `docker-compose.yml` sets `SEED_YEAR` (an integer, or `${SEED_YEAR:-<integer>}`) in the `app` service.
 *
 * Needs Docker: tests tagged [Integration]. The text checks run everywhere. Not tested: the behaviour of a
 * non-numeric SEED_YEAR (the lead has not said whether it should fail startup).
 */
class SeedLoaderSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val integration = setOf(Integration)
    val expected = SeedSupport.expected()
    val count = expected.size

    test("SEED precondition: the generator's seed 42 year is a non-trivial set of unique keys") {
        count shouldBeGreaterThan 50
        expected.map { it.idempotencyKey }.toSet().size shouldBe count
    }

    test("SEED AC1: seed 42 into a fresh ledger posts every generated activity once; a second load posts nothing")
        .config(tags = integration) {
            val db = pg.freshDatabase()
            val first = SeedLoader.load(db.app, SeedSupport.SEED)
            first shouldBe SeedResult(posted = count, duplicates = 0)

            db.entryKeys() shouldBe expected.map { it.idempotencyKey }.sorted()
            db.unbalancedEntryUnits().shouldBeEmpty() // invariant 1
            val outbox = OutboxStore(db.app).events().size
            outbox shouldBe count // the normal path writes one outbox row per posting
            val before = db.sha256()

            val second = SeedLoader.load(db.app, SeedSupport.SEED)
            second shouldBe SeedResult(posted = 0, duplicates = count)
            db.sha256() shouldBe before // nothing new in any ledger table (invariants 2 and 3)
            OutboxStore(db.app).events().size shouldBe outbox
            db.unbalancedEntryUnits().shouldBeEmpty()
        }

    test("SEED AC2: a restart after a partial load posts only the missing activities")
        .config(tags = integration) {
            val db = pg.freshDatabase()
            // The first 10 activities were stored by an earlier, interrupted start, through the normal path.
            val service =
                dev.ledgerline.app.IngestionService(
                    dev.ledgerline.ledger.LedgerRepository(db.app),
                    java.time.Clock.systemUTC(),
                )
            expected.take(10).forEach { service.submit(it) }
            db.entryKeys().size shouldBe 10

            SeedLoader.load(db.app, SeedSupport.SEED) shouldBe SeedResult(posted = count - 10, duplicates = 10)
            db.entryKeys() shouldBe expected.map { it.idempotencyKey }.sorted()
            db.unbalancedEntryUnits().shouldBeEmpty()
        }

    test("SEED AC3: after the relay and consumer have applied the seeded year, reconcile exits 0 with no drift")
        .config(tags = integration) {
            val e = L10.env(pg, broker)
            SeedLoader.load(e.db.app, SeedSupport.SEED) shouldBe SeedResult(posted = count, duplicates = 0)
            e.drainAndApply()

            val run = L10.run(e.db.app)
            io.kotest.assertions.withClue("reconcile output:\n${run.out}") { run.exit shouldBe 0 }
            val summary = L10.summary(run.out)
            summary.pools shouldBeGreaterThan 0
            summary.drift shouldBe 0
            summary.replayHash shouldBe summary.liveHash

            // A restart: loading again adds nothing, so there is nothing to relay and reconcile is unchanged.
            SeedLoader.load(e.db.app, SeedSupport.SEED) shouldBe SeedResult(posted = 0, duplicates = count)
            e.pending().shouldBeEmpty()
            val again = StringBuilder()
            ReconcileCommand.run(e.db.app, again) shouldBe 0
            again.toString() shouldBe run.out
        }

    test("SEED AC4: Main.kt calls SeedLoader.load once, only if SEED_YEAR is set, between migrations and serving") {
        val code = SourceText.withoutComments(SourceText.read("app/src/main/kotlin/dev/ledgerline/app/Main.kt"))
        val call = Regex("""(?<!fun )\bSeedLoader\.load\(""").findAll(code).toList()
        call.size shouldBe 1
        val at = call.single().range.first

        val env = code.indexOf("SEED_YEAR")
        (env in 0 until at) shouldBe true // the variable is read before it is used

        // A guard sits between reading the variable and the call: null check, if, takeIf or ?.let.
        val between = code.substring(env, at)
        Regex("""\?\.let|\bif\s*\(|\.takeIf|!=\s*null|\bwhen\b|\?:\s*return""").containsMatchIn(between) shouldBe true

        val server = code.indexOf("embeddedServer(")
        (server > at) shouldBe true // seeded before serving
        val migrate = code.indexOf("LedgerMigrations.migrate(")
        (migrate in 0 until at) shouldBe true // and only once the schema exists
    }

    test("SEED AC4: Main.kt does not seed unconditionally: every SeedLoader mention is below a SEED_YEAR read") {
        val code = SourceText.withoutComments(SourceText.read("app/src/main/kotlin/dev/ledgerline/app/Main.kt"))
        val firstSeedUse = code.indexOf("SeedLoader")
        firstSeedUse shouldBeGreaterThan -1
        val firstEnv = code.indexOf("SEED_YEAR")
        (firstEnv in 0 until firstSeedUse) shouldBe true
    }

    test("SEED AC5: docker-compose.yml sets SEED_YEAR to an integer for the app service, and for no other service") {
        val compose = ComposeFile.file.readText()
        val services =
            Regex("""(?m)^  ([a-z][\w-]*):\s*$""").findAll(compose).toList()

        fun block(name: String): String {
            val i = services.indexOfFirst { it.groupValues[1] == name }
            check(i >= 0) { "service $name not found" }
            val end = services.getOrNull(i + 1)?.range?.first ?: compose.length
            return compose.substring(services[i].range.first, end)
        }
        val seedLine = Regex("""(?m)^\s*SEED_YEAR:\s*"?(?:\$\{SEED_YEAR:-)?(-?\d+)\}?"?\s*(#.*)?$""")
        seedLine.containsMatchIn(block("app")) shouldBe true
        services.map { it.groupValues[1] }.filter { it != "app" }.forEach {
            block(it).contains("SEED_YEAR") shouldBe false
        }
    }
})
