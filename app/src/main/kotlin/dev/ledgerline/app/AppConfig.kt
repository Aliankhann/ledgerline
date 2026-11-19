package dev.ledgerline.app

/** Runtime configuration, read from environment variables (see .env.example). */
data class AppConfig(
    val httpPort: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val kafkaBootstrap: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                httpPort = env["HTTP_PORT"]?.toInt() ?: 8080,
                databaseUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/ledgerline",
                databaseUser = env["DATABASE_USER"] ?: "ledgerline",
                databasePassword = env["DATABASE_PASSWORD"] ?: "ledgerline",
                kafkaBootstrap = env["KAFKA_BOOTSTRAP"] ?: "localhost:19092",
            )
    }
}
