package dev.ledgerline.calc

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Runs the calc module's Flyway migrations (schema `calc`). Run it as the owner login, after the ledger's migrations:
 * it grants DML to the ledger's app role. The calc state is derived (rebuildable by replay), so unlike the ledger it
 * is updated in place. Its Flyway history table is separate from the ledger's.
 */
object CalcMigrations {
    const val LOCATION = "classpath:db/calc"
    const val SCHEMA = "calc"
    const val HISTORY_TABLE = "calc_schema_history"

    /** Applies pending migrations and returns how many ran. */
    fun migrate(dataSource: DataSource): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .table(HISTORY_TABLE)
            .load()
            .migrate()
            .migrationsExecuted
}
