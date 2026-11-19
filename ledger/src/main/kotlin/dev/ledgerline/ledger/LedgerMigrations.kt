package dev.ledgerline.ledger

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/** Runs the ledger module's Flyway migrations. The history table stays in the connection's default schema. */
object LedgerMigrations {
    const val LOCATION = "classpath:db/migration"

    /** Applies pending migrations and returns how many ran. */
    fun migrate(dataSource: DataSource): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(LOCATION)
            .load()
            .migrate()
            .migrationsExecuted
}
