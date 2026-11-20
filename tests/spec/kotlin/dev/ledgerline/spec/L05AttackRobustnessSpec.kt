package dev.ledgerline.spec

import dev.ledgerline.app.ingestionModule
import dev.ledgerline.ledger.outbox.DrainResult
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Json
import dev.ledgerline.testsupport.L05Activities
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.appendRawOutboxEvent
import dev.ledgerline.testsupport.closeQuietly
import dev.ledgerline.testsupport.copiesPerEventId
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.postJson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * QE phase B, round 1, story L-05 (docs/stories/L-05-reliable-event-publication.md): poison events and the
 * append-only delivery table. Every test needs Docker (Postgres 16 and Redpanda). Bugs: B-016 (an accepted activity
 * too large for the broker is never published and stalls its taxpayer), B-017 (one undecodable outbox row stops the
 * relay for every taxpayer and re-sends the rows before it on every retry).
 */
class L05AttackRobustnessSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val date = LocalDate.of(2025, 10, 1)

    test("L-05 attack (B-016): an activity accepted with a 1.5 MB description is published or refused, never stuck")
        .config(tags = setOf(Integration), timeout = 5.minutes) {
            val e = pg.l05Env(broker)
            val poisonKey = "TP-0001-2025-0910"
            // Under the route's 4 MiB body limit; over Kafka's default 1 MiB record limit (producer max.request.size).
            val body =
                L02Json.body(
                    "idempotencyKey" to poisonKey,
                    "activityType" to "DEPOSIT",
                    "effectiveDate" to "2025-10-01",
                    "settlementDate" to "2025-10-01",
                    "securityId" to null,
                    "quantity" to null,
                    "amount" to "10.00",
                    "commission" to "0.00",
                    "description" to "x".repeat(1_500_000),
                )
            var status = 0
            testApplication {
                application { ingestionModule(e.service) }
                status = client.postJson(body).first.value
            }
            println("L-05 attack B-016: POST with a 1.5 MB description answered $status")
            val later = L05Activities.deposit("TP-0001-2025-0911", 100L, date, 911L)
            val other = L05Activities.deposit("TP-0002-2025-0912", 100L, date, 912L, "TP-0002")
            e.post(later)
            e.post(other)
            val stuck = e.draining { e.awaitDelivered(Duration.ofSeconds(30)) }
            val msgs = e.readAll()
            val copies = msgs.copiesPerEventId()
            println("L-05 attack B-016: still pending after 30 s: $stuck; copies per event id on the topic: $copies")
            if (status in 200..299) {
                withClue("an accepted activity is a posted ledger entry, and every posted entry reaches the topic") {
                    stuck.shouldBeEmpty()
                }
            } else {
                withClue("refused ($status): nothing was posted for it") { e.entriesFor(poisonKey) shouldBe 0L }
                stuck.shouldBeEmpty()
            }
            msgs.mapNotNull { it.eventId }.toSet() shouldBe e.eventIds()
            withClue("no crash happened, so no event is re-sent more than once: $copies") {
                copies.filterValues { it > 2 } shouldBe emptyMap()
            }
        }

    test("L-05 attack (B-017): one undecodable outbox row does not stop other taxpayers or re-send earlier rows")
        .config(tags = setOf(Integration), timeout = 5.minutes) {
            val e = pg.l05Env(broker)
            e.post(L05Activities.deposit("TP-0001-2025-0921", 100L, date, 921L))
            val bad = e.db.appendRawOutboxEvent("TP-0001-2025-0922", "TP-0001", "{\"eventType\": }")
            e.post(L05Activities.deposit("TP-0002-2025-0923", 100L, date, 923L, "TP-0002"))
            e.post(L05Activities.deposit("TP-0001-2025-0924", 100L, date, 924L))
            val ids = e.events().map { it.eventId }
            ids shouldBe listOf(1L, 2L, 3L, 4L)
            bad shouldBe 2L
            val stuck = e.draining { e.awaitDelivered(Duration.ofSeconds(15)) }
            val msgs = e.readAll()
            val copies = msgs.copiesPerEventId()
            println("L-05 attack B-017: still pending after 15 s: $stuck; copies per event id on the topic: $copies")
            withClue("event 1 (before the bad row) is delivered and recorded") { (1L in stuck) shouldBe false }
            withClue("event 3 (TP-0002) is not held back by TP-0001's bad row") { (3L in stuck) shouldBe false }
            withClue("rows before the bad one are not re-sent on every retry: $copies") {
                copies.filterValues { it > 2 } shouldBe emptyMap()
            }
            withClue("the bad row itself is never published under its event id") { copies["2"] shouldBe null }
        }

    test("L-05 attack C5: the app role cannot update, delete or bypass ledger.outbox_delivery; owner upserts fail")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            L05Activities.deposits(931..932, 100L, date).forEach { e.post(it) }
            val p = e.producer()
            e.relay(p).drainOnce(10) shouldBe DrainResult(published = 2, failed = 0)
            closeQuietly(p)
            val before = e.db.snapshot()
            val table = "ledger.outbox_delivery"
            val privileges =
                e.db.query(
                    e.db.owner,
                    "SELECT p, has_table_privilege('ledgerline_app', '$table', p) " +
                        "FROM unnest(ARRAY['UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER']) AS p",
                )
            withClue("app role privileges on $table") {
                privileges.filter { it[1] != "f" }.map { it[0] } shouldBe emptyList()
            }
            val appRefusals =
                listOf(
                    "UPDATE $table SET kafka_offset = kafka_offset + 1 WHERE outbox_id = 1",
                    "DELETE FROM $table WHERE outbox_id = 1",
                    "TRUNCATE $table",
                    "INSERT INTO $table (outbox_id, topic, kafka_partition, kafka_offset) VALUES (1, 'x', 0, 0) " +
                        "ON CONFLICT (outbox_id) DO UPDATE SET topic = EXCLUDED.topic",
                    "MERGE INTO $table d USING (VALUES (1::bigint)) v(id) ON d.outbox_id = v.id " +
                        "WHEN MATCHED THEN DELETE",
                    "ALTER TABLE $table DISABLE TRIGGER outbox_delivery_append_only",
                    "DROP TRIGGER outbox_delivery_append_only ON $table",
                    "ALTER TABLE $table DROP CONSTRAINT outbox_delivery_pkey",
                )
            appRefusals.forEach { sql ->
                withClue("as the app role: $sql") {
                    e.db.failureInRolledBackTx(e.db.app, sql)?.sqlState shouldBe "42501"
                }
            }
            withClue("a second delivery row for one event is refused (PK), not recorded twice") {
                e.db.failureInRolledBackTx(
                    e.db.app,
                    "INSERT INTO $table (outbox_id, topic, kafka_partition, kafka_offset) VALUES (1, 'x', 0, 0)",
                )?.sqlState shouldBe "23505"
            }
            withClue("a delivery row for an event that does not exist yet is refused (it would hide that event)") {
                e.db.failureInRolledBackTx(
                    e.db.app,
                    "INSERT INTO $table (outbox_id, topic, kafka_partition, kafka_offset) VALUES (999, 'x', 0, 0)",
                )?.sqlState shouldBe "23503"
            }
            val ownerAttempts =
                listOf(
                    "INSERT INTO $table (outbox_id, topic, kafka_partition, kafka_offset) VALUES (1, 'x', 0, 0) " +
                        "ON CONFLICT (outbox_id) DO UPDATE SET topic = EXCLUDED.topic",
                    "MERGE INTO $table d USING (VALUES (1::bigint)) v(id) ON d.outbox_id = v.id " +
                        "WHEN MATCHED THEN UPDATE SET kafka_offset = 0",
                    "MERGE INTO $table d USING (VALUES (1::bigint)) v(id) ON d.outbox_id = v.id " +
                        "WHEN MATCHED THEN DELETE",
                )
            ownerAttempts.forEach { sql ->
                withClue("as the table owner: $sql") {
                    val failure = e.db.failureInRolledBackTx(e.db.owner, sql)
                    (failure?.message ?: "<succeeded>").contains("append-only") shouldBe true
                }
            }
            e.db.snapshot() shouldBe before
        }
})
