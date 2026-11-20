package dev.ledgerline.spec

import dev.ledgerline.calc.AcbEngine
import dev.ledgerline.calc.CaIta2026v1
import dev.ledgerline.calc.PoolConsumer
import dev.ledgerline.calc.PoolStore
import dev.ledgerline.calc.PostedActivity
import dev.ledgerline.calc.Reconciler
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Activities
import dev.ledgerline.testsupport.L05Env
import dev.ledgerline.testsupport.L12ConsumerChild
import dev.ledgerline.testsupport.L12Workload
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.consumerConfig
import dev.ledgerline.testsupport.inboxEventIds
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.launchConsumerChild
import dev.ledgerline.testsupport.pollUntilInbox
import dev.ledgerline.testsupport.postAndPublish
import dev.ledgerline.testsupport.prepareChildLogin
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Story L-12 part 1 (L-12a, day 5; docs/stories/L-12-crash-recovery.md AC1 to AC4) plus the part 2 scenarios that need
 * no Toxiproxy (AC6 and AC7 in-process). Provisional: the story is Draft and the split is not approved. Every test
 * needs Docker. No tax value is expected; the oracle is [Reconciler] (zero drift against a replay of the posted
 * activities, which stands in for W42 until L-01's generator and L-10's CLI are wired). AC5 and AC8 (Toxiproxy, late
 * correction) are not covered here.
 *
 * API CONTRACT (Dev implements this; names are QE proposals), package `dev.ledgerline.calc`:
 *
 * ```kotlin
 * class PoolConsumer(dataSource: DataSource, consumerConfig: Map<String, Any>, engine: AcbEngine,
 *                    topic: String = OutboxTopic.NAME) : AutoCloseable {
 *     fun pollOnce(max: Int = 500): Int   // consumes up to max records; returns how many it handled (duplicates too)
 *     fun start(); fun stop()             // background loop of pollOnce; stop waits (at most 10 s); close() = stop()
 * }
 * class PoolStore(dataSource: DataSource) { fun pools(): Map<PoolKey, AcbPool> }   // read-only live pools
 * object CalcMigrations { fun migrate(dataSource: DataSource) }                  // owner login; app role gets DML
 * ```
 *
 * Behaviour:
 * - Tables, in schema `calc`: `inbox(event_id bigint PRIMARY KEY, ...)`, the live pools, and the consumer offsets keyed
 *   by (group id, topic, partition). The consumer owns its offsets in Postgres (it must not rely on Kafka commits):
 *   it assigns or seeks to the stored next offset, and starts at the earliest offset when it has none.
 * - ONE database transaction and ONE `commit()` per consumed record: inbox row (the `eventId` header), the pool
 *   change, and the next offset. A record whose event id is already in the inbox changes no pool and no inbox row,
 *   but its offset still advances (same transaction). Losing a race for an event id (two consumers) is a no-op, never
 *   an exception out of `pollOnce`.
 * - The activity is rebuilt from the event (the value carries the activity fields, or the consumer reads the ledger by
 *   `entryId`); live pools must equal `Reconciler(engine)` over the posted activities with zero drift.
 * - Per-key order is the topic's order, so these tests post activities in canonical order (rising dates).
 * - Offsets are in `calc.consumer_offset` (owner may DELETE rows to force redelivery from earliest, AC6).
 */
class L12CrashRecoverySpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val engine = AcbEngine(CaIta2026v1)
    val reconciler = Reconciler(engine)
    val integration = setOf(Integration)

    fun consumer(
        e: L05Env,
        group: String = "l12",
    ) = PoolConsumer(e.db.app, e.consumerConfig(group), engine, e.topic)

    fun env(): L05Env =
        pg.l05Env(broker).also {
            CalcMigrationsHook.migrate(it)
        }

    fun L05Env.driftOf(posted: List<PostedActivity>) = reconciler.reconcile(PoolStore(db.app).pools(), posted).drift

    fun L05Env.offsets() =
        db.query(db.owner, "SELECT partition_id, next_offset FROM calc.consumer_offset ORDER BY 1").map {
            it[0]!!.toInt() to it[1]!!.toLong()
        }

    test(
        "L-12 AC1: each event is applied once and recorded in the inbox, with the offset past it",
    ).config(tags = integration) {
        val e = env()
        val posted = e.postAndPublish(listOf(L05Activities.a1))
        consumer(e).use { it.pollUntilInbox(e.db, 1) }
        e.db.inboxEventIds() shouldContainExactly e.events().map { it.eventId }
        e.offsets().sumOf { it.second } shouldBe 1L
        e.driftOf(posted).shouldBeEmpty()
    }

    test("L-12 AC1: a failure after the state write leaves no inbox row, pool change or offset (one transaction)")
        .config(tags = integration) {
            val e = env()
            val posted = e.postAndPublish(listOf(L05Activities.a1))
            e.db.failureOf(
                e.db.owner,
                "CREATE FUNCTION calc.l12_fail() RETURNS trigger LANGUAGE plpgsql AS " +
                    "\$\$ BEGIN RAISE EXCEPTION 'L-12 injected failure before commit'; END \$\$",
            )?.let { throw it }
            e.db.failureOf(
                e.db.owner,
                "CREATE CONSTRAINT TRIGGER l12_fail AFTER INSERT ON calc.inbox DEFERRABLE INITIALLY DEFERRED " +
                    "FOR EACH ROW EXECUTE FUNCTION calc.l12_fail()",
            )?.let { throw it }
            consumer(e).use { c -> shouldThrowAny { repeat(5) { c.pollOnce(10) } } }
            e.db.inboxEventIds().shouldBeEmpty()
            PoolStore(e.db.app).pools() shouldBe emptyMap()
            e.offsets().shouldBeEmpty()
            e.db.failureOf(e.db.owner, "DROP TRIGGER l12_fail ON calc.inbox")?.let { throw it }
            consumer(e).use { it.pollUntilInbox(e.db, 1) }
            e.db.inboxEventIds().size shouldBe 1
            e.driftOf(posted).shouldBeEmpty()
        }

    test("L-12 AC2: a redelivered event changes nothing and the offset advances past it").config(tags = integration) {
        val e = env()
        val posted = e.postAndPublish(listOf(L05Activities.a1))
        consumer(e).use { it.pollUntilInbox(e.db, 1) }
        val before = PoolStore(e.db.app).pools()
        val msg = e.readAll().single()
        e.producer().use { p ->
            val rec = org.apache.kafka.clients.producer.ProducerRecord(e.topic, msg.partition, msg.key, msg.value)
            msg.headers.forEach { (k, v) -> rec.headers().add(k, v) }
            p.send(rec).get()
        }
        consumer(e).use { it.pollUntilInbox(e.db, 1) }
        PoolStore(e.db.app).pools() shouldBe before
        e.db.inboxEventIds().size shouldBe 1
        e.offsets().sumOf { it.second } shouldBe 2L
        e.driftOf(posted).shouldBeEmpty()
    }

    val total = 40

    fun killAndRecover(commit: Int) {
        val e = env()
        prepareChildLogin(e.db)
        val posted = e.postAndPublish(L12Workload.buys(seed = 42, count = total))
        val (code, out) = launchConsumerChild(e, commit, before = true).await()
        withClue("child output: $out") { code shouldBe L12ConsumerChild.HALTED }
        withClue("a halt before commit $commit leaves exactly ${commit - 1} committed events") {
            e.db.inboxEventIds().size shouldBe commit - 1
        }
        consumer(e, "l12-child").use { it.pollUntilInbox(e.db, total) }
        e.db.inboxEventIds() shouldContainExactly e.events().map { it.eventId }.sorted()
        e.driftOf(posted).shouldBeEmpty()
    }

    test("L-12 AC3: kill mid-batch, restart: every event once, zero drift").config(tags = integration) {
        killAndRecover(total / 2)
    }

    listOf(1, total / 2, total - 1).forEach { n ->
        test("L-12 AC4: kill before commit $n of $total, restart: zero drift").config(tags = integration) {
            killAndRecover(n)
        }
    }

    test("L-12 AC4: kill right after commit 7 (state and offset durable), restart: zero drift")
        .config(tags = integration) {
            val e = env()
            prepareChildLogin(e.db)
            val posted = e.postAndPublish(L12Workload.buys(seed = 7, count = 20))
            launchConsumerChild(e, 7, before = false).await().first shouldBe L12ConsumerChild.HALTED
            e.db.inboxEventIds().size shouldBeInRange 7..7
            consumer(e, "l12-child").use { it.pollUntilInbox(e.db, 20) }
            e.db.inboxEventIds().size shouldBe 20
            e.driftOf(posted).shouldBeEmpty()
        }

    test("L-12 AC6: redelivering the whole partition (offsets reset to earliest) changes nothing")
        .config(tags = integration) {
            val e = env()
            val posted = e.postAndPublish(L12Workload.buys(seed = 42, count = total))
            consumer(e).use { it.pollUntilInbox(e.db, total) }
            val pools = PoolStore(e.db.app).pools()
            val inbox = e.db.inboxEventIds()
            e.db.failureOf(e.db.owner, "DELETE FROM calc.consumer_offset")?.let { throw it }
            consumer(e).use { c -> repeat(10) { c.pollOnce(100) } }
            PoolStore(e.db.app).pools() shouldBe pools
            e.db.inboxEventIds() shouldContainExactly inbox
            e.offsets().sumOf { it.second } shouldBe total.toLong()
            e.driftOf(posted).shouldBeEmpty()
        }

    test("L-12 AC7: two consumers overlapping on every partition never double-apply").config(tags = integration) {
        val e = env()
        val posted = e.postAndPublish(L12Workload.buys(seed = 42, count = total))
        // Different group ids force the overlap a rebalance causes: both read every record, so they race on each event.
        consumer(e, "l12-a").use { a ->
            consumer(e, "l12-b").use { b ->
                a.start()
                b.start()
                a.pollUntilInboxAfterStart(e)
                Thread.sleep(3_000)
                a.stop()
                b.stop()
            }
        }
        e.db.inboxEventIds() shouldContainExactly e.events().map { it.eventId }.sorted()
        e.driftOf(posted).shouldBeEmpty()
    }

    test("L-12 AC7: a second member joining and leaving the same group never double-applies")
        .config(tags = integration) {
            val e = env()
            val posted = e.postAndPublish(L12Workload.buys(seed = 43, count = total))
            consumer(e).use { a ->
                a.start()
                consumer(e).use { b ->
                    b.start()
                    Thread.sleep(5_000)
                    b.stop()
                }
                a.pollUntilInboxAfterStart(e)
                a.stop()
            }
            e.db.inboxEventIds() shouldContainExactly e.events().map { it.eventId }.sorted()
            e.driftOf(posted).shouldBeEmpty()
        }
})

private fun PoolConsumer.pollUntilInboxAfterStart(e: L05Env) {
    val want = e.events().size
    val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
    while (e.db.inboxEventIds().size < want && System.nanoTime() < deadline) Thread.sleep(200)
}

/** The consumer tables come from `CalcMigrations` (owner login), after the ledger's. */
private object CalcMigrationsHook {
    fun migrate(e: L05Env) = dev.ledgerline.calc.CalcMigrations.migrate(e.db.owner)
}
