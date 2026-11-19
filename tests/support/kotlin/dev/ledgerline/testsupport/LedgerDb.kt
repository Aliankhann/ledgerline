package dev.ledgerline.testsupport

import dev.ledgerline.ledger.LedgerMigrations
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.PrintWriter
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * One Postgres 16 container (Testcontainers, image cached locally) shared by a spec. Each call to [freshDatabase]
 * creates a new database in it and runs the ledger module's Flyway migrations as the container superuser, which
 * therefore owns the tables. Only use from tests tagged [Integration]. The container starts on first use.
 */
class PostgresLedger : AutoCloseable {
    companion object {
        const val IMAGE = "postgres:16.4-alpine"

        /** The application's database role, created by the ledger migrations. */
        const val APP_ROLE = "ledgerline_app"
    }

    private val started = lazy { PostgreSQLContainer<Nothing>(DockerImageName.parse(IMAGE)).also { it.start() } }
    private val container by started
    private var databases = 0

    fun freshDatabase(): LedgerDb {
        databases += 1
        val name = "ledger_t$databases"
        owner(container.databaseName).connection.use {
                c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val ownerDs = owner(name)
        LedgerMigrations.migrate(ownerDs)
        return LedgerDb(ownerDs, AsRoleDataSource(ownerDs, APP_ROLE))
    }

    private fun owner(database: String): DataSource =
        PGSimpleDataSource().apply {
            setURL("jdbc:postgresql://${container.host}:${container.firstMappedPort}/$database")
            user = container.username
            password = container.password
        }

    override fun close() {
        if (started.isInitialized()) container.stop()
    }
}

/** A migrated ledger database: [owner] is the table owner (superuser), [app] runs as [PostgresLedger.APP_ROLE]. */
class LedgerDb(val owner: DataSource, val app: DataSource) {
    /** Base tables in schema `ledger`, sorted by name. */
    fun tables(): List<String> =
        query(
            owner,
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'ledger' " +
                "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY 1",
        ).map { it[0]!! }

    fun rowCounts(): Map<String, Long> =
        tables().associateWith { t ->
            query(
                owner,
                "SELECT count(*) FROM ledger.$t",
            )[0][0]!!.toLong()
        }

    /** Every row of every ledger table as Postgres row text, sorted, per table. */
    fun snapshot(): Map<String, List<String>> =
        tables().associateWith {
                t ->
            query(owner, "SELECT r::text FROM ledger.$t r ORDER BY 1").map { it[0]!! }
        }

    /** SHA-256 over [snapshot]. Rows are ordered by their full text, which is deterministic and implies key order equality. */
    fun sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        snapshot().forEach { (t, rows) ->
            md.update("table $t\n".toByteArray())
            rows.forEach { md.update((it + "\n").toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Runs [sql] and returns the exception it raised, or null if it succeeded. Autocommit, one statement. */
    fun failureOf(
        ds: DataSource,
        sql: String,
    ): SQLException? =
        ds.connection.use { c ->
            try {
                c.createStatement().use { it.execute(sql) }
                null
            } catch (e: SQLException) {
                e
            }
        }

    /** The smallest `line_id` of the entry posted for [idempotencyKey]. */
    fun firstLineIdOf(idempotencyKey: String): String =
        requireNotNull(
            query(
                owner,
                "SELECT min(l.line_id) FROM ledger.line l JOIN ledger.entry e ON e.entry_id = l.entry_id " +
                    "WHERE e.idempotency_key = '$idempotencyKey'",
            )[0][0],
        ) { "no lines for $idempotencyKey" }

    /** The error message [sql] raises when run as the table owner, or a marker if it succeeded. */
    fun ownerError(sql: String): String = failureOf(owner, sql)?.let { it.message ?: it.sqlState } ?: "<succeeded>"

    fun query(
        ds: DataSource,
        sql: String,
    ): List<List<String?>> =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
                }
            }
        }
}

/** Hands out connections that have run `SET ROLE <role>`, so privilege checks apply to that role. */
class AsRoleDataSource(private val delegate: DataSource, private val role: String) : DataSource {
    private fun asRole(c: Connection): Connection =
        c.also { conn -> conn.createStatement().use { it.execute("SET ROLE $role") } }

    override fun getConnection(): Connection = asRole(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = asRole(delegate.getConnection(username, password))

    override fun getLogWriter(): PrintWriter? = delegate.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    override fun getParentLogger(): Logger = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
}
