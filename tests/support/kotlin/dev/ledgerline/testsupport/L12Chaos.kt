package dev.ledgerline.testsupport

import dev.ledgerline.calc.CalcMigrations
import dev.ledgerline.ledger.LedgerMigrations
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.ToxiproxyContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import javax.sql.DataSource

/**
 * Support for the L-12 chaos scenarios (`tests/chaos`). Only use from tests tagged [Integration] (Docker).
 *
 * [ProxiedPostgres] is a Postgres 16 container and a Toxiproxy container on one Docker network. Databases made by
 * [freshDatabase] are reachable two ways: [ProxiedDb.direct] (the test's own, never faulted: posting, reading, the
 * `reconcile` oracle) and [ProxiedDb.proxiedApp] (the app role through Toxiproxy: what the consumer under test uses).
 */
class ProxiedPostgres : AutoCloseable {
    companion object {
        const val TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.5.0"
        private const val ALIAS = "ledger-pg"
        private const val PG_PORT = 5432

        /** One of the ports `ToxiproxyContainer` exposes (8666 and up). */
        private const val LISTEN_PORT = 8666
    }

    private class Pg : PostgreSQLContainer<Pg>(DockerImageName.parse(PostgresLedger.IMAGE))

    private val network: Network = Network.newNetwork()
    private val pg = Pg().withNetwork(network).withNetworkAliases(ALIAS)
    private val toxi = ToxiproxyContainer(TOXIPROXY_IMAGE).withNetwork(network)
    private val proxy by lazy {
        pg.start()
        toxi.start()
        ToxiproxyClient(toxi.host, toxi.controlPort).createProxy("ledger-pg", "0.0.0.0:$LISTEN_PORT", "$ALIAS:$PG_PORT")
    }
    private var databases = 0

    /** The proxy in front of Postgres: add and remove toxics on `proxy.toxics()`. */
    val toxiproxy: Proxy get() = proxy

    private fun directUrl(db: String) = "jdbc:postgresql://${pg.host}:${pg.firstMappedPort}/$db"

    private fun proxiedUrl(db: String) = "jdbc:postgresql://${toxi.host}:${toxi.getMappedPort(LISTEN_PORT)}/$db"

    /** A migrated (ledger and calc) database, with the app role reachable directly and through the proxy. */
    fun freshDatabase(): ProxiedDb {
        proxy // starts both containers on first use
        databases += 1
        val name = "chaos_t$databases"
        login(directUrl(pg.databaseName), pg.username, pg.password).connection.use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val owner = login(directUrl(name), pg.username, pg.password)
        LedgerMigrations.migrate(owner)
        CalcMigrations.migrate(owner)
        val proxiedOwner = login(proxiedUrl(name), pg.username, pg.password)
        return ProxiedDb(
            LedgerDb(owner, AsRoleDataSource(owner, PostgresLedger.APP_ROLE), directUrl(name)),
            AsRoleDataSource(proxiedOwner, PostgresLedger.APP_ROLE),
        )
    }

    override fun close() {
        runCatching { toxi.stop() }
        runCatching { pg.stop() }
        runCatching { network.close() }
    }
}

/** [direct] never goes through Toxiproxy; [proxiedApp] always does. Both are the same database. */
class ProxiedDb(val direct: LedgerDb, val proxiedApp: DataSource)

/** Polls until the inbox holds one row per outbox event (and nothing is pending), or [timeout]; returns whether it does. */
fun L05Env.awaitFullyConsumed(timeout: Duration): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        val want = events().size
        if (pending().isEmpty() && db.inboxEventIds().size == want) return true
        Thread.sleep(200)
    }
    return pending().isEmpty() && db.inboxEventIds().size == events().size
}

/**
 * Ends a chaos scenario (story L-12 AC9): runs `reconcile` as the app role on the direct connection, prints one line
 * `CHAOS scenario=<name> result=PASS|FAIL events=<inbox rows>` so a run lists every scenario, and fails the test
 * unless `reconcile` exits 0 with `drift=0`.
 */
fun L05Env.reconcileAndReport(name: String): L10.Run {
    val run = L10.run(db.app)
    val ok = run.exit == 0 && L10.summary(run.out).drift == 0
    println("CHAOS scenario=$name result=${if (ok) "PASS" else "FAIL"} events=${db.inboxEventIds().size}")
    check(ok) { "scenario $name: reconcile exit=${run.exit}\n${run.out}" }
    return run
}
