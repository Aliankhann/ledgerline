package dev.ledgerline.app

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.ledgerline.ledger.LedgerMigrations
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

private val log = LoggerFactory.getLogger("dev.ledgerline.app")

@Serializable
data class Health(val status: String, val modules: List<String>)

fun main() {
    val config = AppConfig.fromEnv()

    // Migrate as the owner login, then close that pool; the service only ever holds the app role's connections.
    // Flyway holds two connections at once (schema-history lock plus migration).
    pool(config.databaseUrl, config.migrationUser, config.migrationPassword, size = 2).use { owner ->
        log.info("Flyway applied {} migration(s) as {}", LedgerMigrations.migrate(owner), config.migrationUser)
        if (config.databaseUser == LedgerMigrations.APP_ROLE) {
            LedgerMigrations.enableAppLogin(owner, config.databasePassword)
        }
    }

    // The ledger repository arrives on this pool with L-02's ingestion API.
    val dataSource = pool(config.databaseUrl, config.databaseUser, config.databasePassword, size = 10)
    log.info("Serving as database role {}", config.databaseUser)
    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })

    embeddedServer(Netty, port = config.httpPort, module = Application::module).start(wait = true)
}

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
