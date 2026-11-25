package dev.ledgerline.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.ActivityStore
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.ledger.LedgerMigrations
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.outbox.OutboxProducers
import dev.ledgerline.ledger.outbox.OutboxRelay
import dev.ledgerline.ledger.outbox.OutboxTopic
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("dev.ledgerline.app")

@Serializable
data class Health(val status: String, val modules: List<String>)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "reconcile") exitProcess(reconcileFromEnv())
    serve()
}

/** The `reconcile` command: read-only, no migrations, no Ktor, no relay, consumer or Kafka. */
private fun reconcileFromEnv(): Int {
    val config = AppConfig.fromEnv()
    return pool(config.databaseUrl, config.databaseUser, config.databasePassword, size = 2).use {
        ReconcileCommand.run(
            it,
            System.out,
            Path.of("build/reconciliation/report.json"),
            AcbEngine(CaIta2026v1, ProductionFx.cache()),
        )
    }
}

private fun serve() {
    // Route java.util.logging and System.Logger (used by ledger/ and calc/) through logback.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    val config = AppConfig.fromEnv()

    // Migrate as the owner login, then close that pool; the service only ever holds the app role's connections.
    // Flyway holds two connections at once (schema-history lock plus migration).
    pool(config.databaseUrl, config.migrationUser, config.migrationPassword, size = 2).use { owner ->
        log.info("Ledger Flyway applied {} migration(s) as {}", LedgerMigrations.migrate(owner), config.migrationUser)
        // Must follow the ledger's migrations: calc V1 grants DML to the ledger's app role.
        log.info("Calc Flyway applied {} migration(s) as {}", CalcMigrations.migrate(owner), config.migrationUser)
        if (config.databaseUser == LedgerMigrations.APP_ROLE) {
            LedgerMigrations.enableAppLogin(owner, config.databasePassword)
        }
    }

    val fx = ProductionFx.cache()
    val dataSource = pool(config.databaseUrl, config.databaseUser, config.databasePassword, size = 10)
    log.info("Serving as database role {}", config.databaseUser)

    // OWNER-INPUT: C32. Seed on start only when SEED_YEAR is set (its value is the generator seed). The outbox rows the
    // seeding writes are relayed once the relay below starts.
    System.getenv("SEED_YEAR")?.let { seed ->
        log.info("Seed load finished: {}", SeedLoader.load(dataSource, seed.toLong()))
    }

    // Draft ADR 0005 (L-05): the outbox relay runs in the service process, as the app role. OWNER-INPUT: C26.
    val producer = OutboxProducers.create(config.kafkaBootstrap)
    val relay = OutboxRelay(dataSource, producer, OutboxTopic.NAME, config.maxEventBytes)
    relay.start()
    log.info("Outbox relay draining to {} at {}", OutboxTopic.NAME, config.kafkaBootstrap)
    val consumer =
        PoolConsumer(
            dataSource,
            mapOf(
                "bootstrap.servers" to config.kafkaBootstrap,
                "group.id" to config.kafkaGroupId,
                "auto.offset.reset" to "earliest",
            ),
            AcbEngine(CaIta2026v1, fx),
        )
    consumer.start()
    log.info("Pool consumer started, group {} at {}", config.kafkaGroupId, config.kafkaBootstrap)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutting down: stopping pool consumer and outbox relay")
            consumer.close()
            relay.stop()
            producer.close(Duration.ofSeconds(5))
            dataSource.close()
            log.info("Shutdown complete")
        },
    )

    val ingestion = IngestionService(LedgerRepository(dataSource, config.maxEventBytes))
    embeddedServer(Netty, port = config.httpPort) {
        module()
        ingestionModule(ingestion)
        // L-20: upload preview (read-only) and confirm (the same ingestion path, atomic per file).
        importPreviewModule(ingestion)
        // Read-only, per request: the taxpayer's stored activities (not reversed, C19) are replayed to pools WITH step
        // history, so reasons can be explained. calc.pool stays the consumer's state; reconciliation compares the two.
        // acbVsBookFromActivities wraps acbVsBookModule with the superficial-loss replay.
        // OWNER-INPUT: C31 holdings list every pool (NO_BROKER_VALUE / DATA_GAP placeholders).
        // Book values come from the optional file named by BOOK_VALUE_CSV (header security_id,book_value). OWNER-INPUT: C29.
        val activities = ActivityStore(dataSource)
        acbVsBookFromActivities(activities::effectiveActivities, bookValueCsv(), fx)
        // L-17: the timeline and sum check behind one holding's difference, from the same stored activities.
        explainFromActivities(activities::effectiveActivities, bookValueCsv(), fx)
        // L-18: your tax year (read-only), from the same stored activities.
        taxYearFromActivities(fx, activities::effectiveActivities)
    }.start(wait = true)
}

private fun bookValueCsv(): String =
    System.getenv("BOOK_VALUE_CSV")?.let { java.io.File(it).readText() } ?: "security_id,book_value\n"

private fun pool(
    url: String,
    user: String,
    secret: String,
    size: Int,
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            password = secret
            maximumPoolSize = size
        },
    )

fun Application.module() {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") {
            call.respond(
                Health(
                    status = "ok",
                    modules =
                        listOf(
                            dev.ledgerline.core.Module.NAME,
                            dev.ledgerline.ledger.Module.NAME,
                            dev.ledgerline.calc.Module.NAME,
                            dev.ledgerline.ingest.Module.NAME,
                        ),
                ),
            )
        }
    }
}
