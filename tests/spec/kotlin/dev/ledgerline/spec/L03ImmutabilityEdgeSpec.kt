package dev.ledgerline.spec

import dev.ledgerline.ledger.LedgerMigrations
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.Posting
import dev.ledgerline.testsupport.ComposeFile
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L03Activities
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.login
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * L-03 phase B attack tests, round 1: immutability in practice (story AC6, invariant 3, draft ADR 0005).
 *
 * The stack's app connects as the compose superuser, so revoked grants alone do not protect the ledger there. These
 * tests show what does: the append-only trigger against the superuser, the grants against a real `ledgerline_app`
 * LOGIN (given LOGIN here, test only), and what a superuser can still do to bypass the trigger. Bypass attempts run
 * in transactions that are always rolled back.
 */
class L03ImmutabilityEdgeSpec : FunSpec({

    val pg = PostgresLedger()
    afterSpec { pg.close() }
    val ac1 = L03Activities.ac1

    /** UPDATE, DELETE and TRUNCATE in every form we could think of, against both ledger tables. */
    fun mutations(lineId: String): List<String> =
        listOf(
            "UPDATE ledger.line SET amount = 1 WHERE line_id = $lineId",
            "UPDATE ledger.entry SET source_seq = source_seq + 1",
            "UPDATE ledger.line l SET amount = l.amount FROM ledger.entry e WHERE e.entry_id = l.entry_id",
            "DELETE FROM ledger.line WHERE line_id = $lineId",
            "DELETE FROM ledger.entry",
            "DELETE FROM ledger.line l USING ledger.entry e WHERE e.entry_id = l.entry_id",
            "WITH d AS (DELETE FROM ledger.line RETURNING 1) SELECT count(*) FROM d",
            "TRUNCATE ledger.line",
            "TRUNCATE ledger.entry CASCADE",
            "TRUNCATE ledger.entry, ledger.line",
            "INSERT INTO ledger.entry (idempotency_key, effective_date, source_seq) " +
                "VALUES ('${ac1.idempotencyKey}', DATE '2025-01-07', 2) " +
                "ON CONFLICT (idempotency_key) DO UPDATE SET source_seq = 99",
            "MERGE INTO ledger.line l USING (SELECT $lineId AS id) s ON l.line_id = s.id " +
                "WHEN MATCHED THEN UPDATE SET amount = 1",
            "MERGE INTO ledger.line l USING (SELECT $lineId AS id) s ON l.line_id = s.id WHEN MATCHED THEN DELETE",
        )

    test("L-03 edge: as the superuser, every UPDATE, DELETE and TRUNCATE form fails with append-only; hash unchanged")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            db.query(db.owner, "SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user") shouldBe
                listOf(listOf("true"))
            val lineId = db.firstLineIdOf(ac1.idempotencyKey)
            val before = db.sha256()
            assertSoftly {
                mutations(lineId).forEach { sql ->
                    withClue("as superuser: $sql") { db.ownerError(sql) shouldContainIgnoringCase "append-only" }
                }
                db.sha256() shouldBe before
            }
        }

    test("L-03 edge: as a real ledgerline_app LOGIN, mutations and trigger bypasses fail 42501; posting still works")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            val app = db.loginAs(PostgresLedger.APP_ROLE, "l03-edge-only")
            val repo = LedgerRepository(app)
            repo.post(ac1)
            db.query(app, "SELECT current_user, rolsuper::text FROM pg_roles WHERE rolname = current_user") shouldBe
                listOf(listOf("ledgerline_app", "false"))
            val lineId = db.firstLineIdOf(ac1.idempotencyKey)
            val before = db.sha256()
            val bypasses =
                listOf(
                    "ALTER TABLE ledger.line DISABLE TRIGGER line_append_only",
                    "ALTER TABLE ledger.line DISABLE TRIGGER ALL",
                    "DROP TRIGGER line_append_only ON ledger.line",
                    "SET session_replication_role = replica",
                    "ALTER TABLE ledger.line ALTER COLUMN amount TYPE bigint USING amount + 1",
                    "DROP TABLE ledger.line",
                    "CREATE OR REPLACE FUNCTION ledger.reject_mutation() RETURNS trigger LANGUAGE plpgsql " +
                        "AS 'BEGIN RETURN NULL; END'",
                    "CREATE TABLE ledger.shadow (x int)",
                )
            assertSoftly {
                (mutations(lineId) + bypasses).forEach { sql ->
                    withClue("as ledgerline_app: $sql") {
                        db.failureOf(app, sql).shouldNotBeNull().sqlState shouldBe "42501"
                    }
                }
                db.sha256() shouldBe before
                repo.entries().map { it.idempotencyKey } shouldContainExactly listOf(ac1.idempotencyKey)
            }
        }

    // Characterisation, not a requirement: records what a superuser (the role the stack's app uses today, B-003) can
    // do despite the trigger. Each attempt is rolled back. If one of these starts failing, the protection improved.
    test("L-03 edge (documented limitation): a superuser can bypass the append-only trigger in four ways")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            val lineId = db.firstLineIdOf(ac1.idempotencyKey)
            val before = db.sha256()
            val amount = "SELECT amount FROM ledger.line WHERE line_id = $lineId"
            val original = db.query(db.owner, amount).single().single()

            fun mutatedWith(vararg setup: String): String? =
                db.rolledBack(db.owner) { c ->
                    c.createStatement().use { st ->
                        setup.forEach { st.execute(it) }
                        st.executeQuery(amount).use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                }
            assertSoftly {
                withClue("DISABLE TRIGGER then UPDATE") {
                    mutatedWith(
                        "ALTER TABLE ledger.line DISABLE TRIGGER line_append_only",
                        "UPDATE ledger.line SET amount = 1 WHERE line_id = $lineId",
                    ) shouldBe "1"
                }
                withClue("session_replication_role = replica then UPDATE") {
                    mutatedWith(
                        "SET LOCAL session_replication_role = replica",
                        "UPDATE ledger.line SET amount = 1 WHERE line_id = $lineId",
                    ) shouldBe "1"
                }
                withClue("ALTER COLUMN TYPE ... USING rewrites every amount without firing UPDATE triggers") {
                    mutatedWith("ALTER TABLE ledger.line ALTER COLUMN amount TYPE bigint USING amount + 1") shouldBe
                        (original!!.toLong() + 1).toString()
                }
                withClue("DROP TABLE removes the rows outright") {
                    db.rolledBack(db.owner) { c ->
                        c.createStatement().use { it.execute("DROP TABLE ledger.line") }
                    }
                }
                db.sha256() shouldBe before
            }
        }

    // B-003: the stack's app logs in as the compose superuser, so the grants protect nothing and the trigger can be
    // switched off by the app's own connection (see the limitation test above).
    test("L-03 edge (B-003): docker-compose runs the app as a different database user from the superuser") {
        ComposeFile.env("DATABASE_USER") shouldNotBe ComposeFile.env("POSTGRES_USER")
    }

    test("L-03 edge (B-003): with docker-compose credentials, the app's role is not superuser and cannot disable it")
        .config(tags = setOf(Integration)) {
            val superUser = ComposeFile.env("POSTGRES_USER")
            val container =
                PostgreSQLContainer<Nothing>(DockerImageName.parse(PostgresLedger.IMAGE)).apply {
                    withDatabaseName(ComposeFile.env("POSTGRES_DB"))
                    withUsername(superUser)
                    withPassword(ComposeFile.env("POSTGRES_PASSWORD"))
                }
            container.start()
            try {
                // As Main.kt does (ADR 0005): migrate and enable the app login as the admin, then serve as the app.
                val admin =
                    login(
                        container.jdbcUrl,
                        ComposeFile.env("DATABASE_MIGRATION_USER"),
                        ComposeFile.env("DATABASE_MIGRATION_PASSWORD"),
                    )
                LedgerMigrations.migrate(admin)
                LedgerMigrations.enableAppLogin(admin, ComposeFile.env("DATABASE_PASSWORD"))
                val app =
                    login(container.jdbcUrl, ComposeFile.env("DATABASE_USER"), ComposeFile.env("DATABASE_PASSWORD"))
                LedgerRepository(app).post(ac1)

                fun scalar(sql: String): String =
                    app.connection.use { c ->
                        c.createStatement().use { st ->
                            st.executeQuery(sql).use {
                                it.next()
                                it.getString(1)
                            }
                        }
                    }

                fun failure(sql: String): java.sql.SQLException? =
                    app.connection.use { c ->
                        c.autoCommit = false
                        try {
                            c.createStatement().use { it.execute(sql) }
                            null
                        } catch (e: java.sql.SQLException) {
                            e
                        } finally {
                            c.rollback()
                        }
                    }
                assertSoftly {
                    withClue("current_user") { scalar("SELECT current_user") shouldBe ComposeFile.env("DATABASE_USER") }
                    withClue("app role is superuser") {
                        scalar("SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user") shouldBe "false"
                    }
                    listOf(
                        "UPDATE ledger.line SET amount = 1",
                        "DELETE FROM ledger.line",
                        "ALTER TABLE ledger.line DISABLE TRIGGER ALL",
                    ).forEach { sql ->
                        withClue("app connection could run: $sql") { failure(sql).shouldNotBeNull() }
                    }
                    withClue("entries posted through the app login") {
                        scalar("SELECT count(*)::text FROM ledger.entry") shouldBe "1"
                    }
                }
            } finally {
                container.stop()
            }
        }

    // B-004: INSERT is the app role's one write privilege, and nothing in the database ties lines to a balanced,
    // closed entry. A later INSERT can change what a committed entry says without any UPDATE.
    test("L-03 edge (B-004): as ledgerline_app, a line added to an already committed entry is refused")
        .config(tags = setOf(Integration)) {
            val db = pg.freshDatabase()
            LedgerRepository(db.app).post(ac1)
            val entryId =
                db.query(db.owner, "SELECT entry_id FROM ledger.entry WHERE idempotency_key = '${ac1.idempotencyKey}'")
                    .single().single()
            val before = db.sha256()
            val sql =
                "INSERT INTO ledger.line (entry_id, line_no, account, unit, side, amount) " +
                    "VALUES ($entryId, 99, 'TP-0001-NR1', 'CAD', 'DEBIT', 1000000)"
            assertSoftly {
                withClue("as ledgerline_app: $sql") { db.failureOf(db.app, sql).shouldNotBeNull() }
                withClue("ledger rows after the attempt") { db.sha256() shouldBe before }
                LedgerRepository(db.app).entries().single().lines.size shouldBe
                    Posting.linesFor(ac1).size
            }
        }

    test("L-03 edge: a real ledgerline_app LOGIN cannot SET ROLE to the superuser")
        .config(tags = setOf(Integration)) {
            // Unlike the SET ROLE helper in LedgerDb, a real app login has no other role to switch to.
            val db = pg.freshDatabase()
            val app = db.loginAs(PostgresLedger.APP_ROLE, "l03-edge-only")
            val superUser = db.query(db.owner, "SELECT current_user").single().single()
            db.failureOf(app, "SET ROLE \"$superUser\"").shouldNotBeNull().sqlState shouldBe "42501"
            db.failureOf(app, "SELECT 1").shouldBeNull()
        }
})
