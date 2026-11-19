package dev.ledgerline.ledger

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Runs the ledger module's Flyway migrations. The history table stays in the connection's default schema.
 *
 * Migrations run as an owner or admin login; the service runs as [APP_ROLE], which holds SELECT and INSERT only and
 * does not own the tables, so it cannot disable, drop or bypass the append-only triggers (draft ADR 0005).
 */
object LedgerMigrations {
    const val LOCATION = "classpath:db/migration"

    /** The least-privilege role the service logs in as. Created NOLOGIN by V2; [enableAppLogin] turns login on. */
    const val APP_ROLE = "ledgerline_app"

    /** Applies pending migrations and returns how many ran. */
    fun migrate(dataSource: DataSource): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .load()
            .migrate()
            .migrationsExecuted

    /**
     * Gives [APP_ROLE] LOGIN with [password]. Needs a connection that may alter roles (the migration login). The
     * password is quoted by the server (`format('%L')`), never concatenated into SQL here.
     */
    fun enableAppLogin(
        dataSource: DataSource,
        password: String,
    ) {
        require(password.isNotEmpty()) { "the $APP_ROLE password is empty" }
        dataSource.connection.use { c ->
            val sql =
                c.prepareStatement("SELECT format('ALTER ROLE %I LOGIN PASSWORD %L', ?::text, ?::text)").use { st ->
                    st.setString(1, APP_ROLE)
                    st.setString(2, password)
                    st.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            c.createStatement().use { it.execute(sql) }
        }
    }
}
