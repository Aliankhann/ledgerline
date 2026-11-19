package dev.ledgerline.app

import dev.ledgerline.ledger.LedgerMigrations

/**
 * Runtime configuration, read from environment variables (see .env.example and docker-compose.yml).
 *
 * Two database logins (draft ADR 0005): [migrationUser] owns the schema and runs Flyway at startup only;
 * [databaseUser] is the least-privilege role the service uses for everything else.
 */
data class AppConfig(
    val httpPort: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val migrationUser: String,
    val migrationPassword: String,
    val kafkaBootstrap: String,
) {
    init {
        require(databaseUser != migrationUser) {
            "DATABASE_USER and DATABASE_MIGRATION_USER are both '$databaseUser'; the service must not run as the " +
                "role that owns the ledger tables"
        }
    }

    override fun toString(): String =
        "AppConfig(httpPort=$httpPort, databaseUrl=$databaseUrl, databaseUser=$databaseUser, " +
            "migrationUser=$migrationUser, kafkaBootstrap=$kafkaBootstrap)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                httpPort = env["HTTP_PORT"]?.toInt() ?: 8080,
                databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/ledgerline",
                databaseUser = env["DATABASE_USER"] ?: LedgerMigrations.APP_ROLE,
                databasePassword = env["DATABASE_PASSWORD"] ?: "ledgerline_app",
                migrationUser = env["DATABASE_MIGRATION_USER"] ?: "ledgerline",
                migrationPassword = env["DATABASE_MIGRATION_PASSWORD"] ?: "ledgerline",
                kafkaBootstrap = env["KAFKA_BOOTSTRAP"] ?: "localhost:19092",
            )
    }
}
