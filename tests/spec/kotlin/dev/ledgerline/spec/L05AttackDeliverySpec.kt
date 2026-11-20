package dev.ledgerline.spec

import dev.ledgerline.testsupport.AckFailingProducer
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L05Activities
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.bulkAppendOutboxEvents
import dev.ledgerline.testsupport.closeQuietly
import dev.ledgerline.testsupport.copiesPerEventId
import dev.ledgerline.testsupport.firstSeenOrderByKey
import dev.ledgerline.testsupport.l05Env
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * QE phase B, round 1, story L-05 (docs/stories/L-05-reliable-event-publication.md): delivery order, concurrent relays
 * and drain throughput. Every test needs Docker (Postgres 16 and Redpanda). Bugs: B-015 (per-key order after a failed
 * ack mid-drain).
 */
class L05AttackDeliverySpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val sixty: Duration = Duration.ofSeconds(60)

    test("L-05 attack AC7 (B-015): a failed ack mid-drain must not let a later event of that taxpayer be seen first")
        .config(tags = setOf(Integration), timeout = 5.minutes) {
            val e = pg.l05Env(broker)
            // Interleaved: event ids 1..10, odd ids TP-0001, even ids TP-0002 (one transaction each, so ids follow).
            (1..10).forEach { i ->
                val tp = if (i % 2 == 1) "TP-0001" else "TP-0002"
                val key = "$tp-2025-%04d".format(700 + i)
                e.post(L05Activities.deposit(key, 100L * i, LocalDate.of(2025, 4, 2), i.toLong(), tp))
            }
            val events = e.events()
            events.map { it.eventId } shouldBe (1L..10L).toList()
            // The 3rd send (event 3, TP-0001) never reaches the broker; its future fails. Events 5, 7 and 9 of the same
            // taxpayer are pipelined behind it in the same drain and do reach the broker.
            val real = e.producer()
            val faulty = AckFailingProducer(real, failSends = setOf(3))
            val relay = e.relay(faulty)
            relay.drainOnce(500)
            faulty.failedEventIds shouldBe listOf("3")
            withClue("event 3 and every later TP-0001 event stay pending after the failed ack") {
                e.pending() shouldBe listOf(3L, 5L, 7L, 9L)
            }
            relay.drainOnce(500)
            e.pending().shouldBeEmpty()
            closeQuietly(real)
            val ids = e.eventIds()
            val msgs = e.readUntilSeen(ids, sixty)
            val topicOrder = msgs.sortedBy { it.offset }.groupBy { it.key }.mapValues { (_, m) -> m.map { it.eventId } }
            println("L-05 attack B-015 topic order per key: $topicOrder")
            msgs.mapNotNull { it.eventId }.toSet() shouldBe ids
            msgs.groupBy { it.eventId }.forEach { (id, copies) ->
                withClue("event $id: copies identical") { copies.map { it.fingerprint }.toSet() shouldHaveSize 1 }
            }
            val expected = events.groupBy { it.taxpayerId }.mapValues { (_, own) -> own.map { it.eventId.toString() } }
            val seen = msgs.firstSeenOrderByKey()
            expected.forEach { (tp, own) ->
                withClue(
                    "$tp: order in which a deduplicating consumer first sees each event id must be outbox order " +
                        "(AC7: strictly increasing ledger sequence per taxpayer)",
                ) { seen[tp] shouldBe own }
            }
        }

    test(
        "L-05 attack: two relays draining one outbox concurrently record each event once; copies share bytes and order",
    )
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            val seed = 20_251_121L
            println("L-05 attack concurrent relays seed $seed")
            val e = pg.l05Env(broker)
            val activities = L05Activities.generated(seed, 300, 3)
            val producers = listOf(e.producer(), e.producer())
            val relays = producers.map { e.relay(it) }
            val posting = AtomicBoolean(true)
            val pool = Executors.newFixedThreadPool(3)
            val drained = IntArray(2)
            try {
                val drainers =
                    relays.mapIndexed { i, relay ->
                        pool.submit<Unit> {
                            val r = Random(seed + i)
                            val deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos()
                            while (posting.get() || e.pending().isNotEmpty()) {
                                check(System.nanoTime() < deadline) { "relay $i did not finish" }
                                val got = relay.drainOnce(1 + r.nextInt(40)).published
                                drained[i] += got
                                if (got == 0) Thread.sleep(5)
                            }
                        }
                    }
                pool.submit<Unit> {
                    try {
                        activities.forEach { e.post(it) }
                    } finally {
                        posting.set(false)
                    }
                }.get(5, TimeUnit.MINUTES)
                drainers.forEach { it.get(5, TimeUnit.MINUTES) }
            } finally {
                pool.shutdownNow()
                producers.forEach { closeQuietly(it) }
            }
            val events = e.events()
            events shouldHaveSize 300
            val ids = e.eventIds()
            val delivery =
                e.db.query(e.db.owner, "SELECT outbox_id, kafka_partition, kafka_offset FROM ledger.outbox_delivery")
            withClue("exactly one delivery row per outbox row") {
                delivery shouldHaveSize 300
                delivery.map { it[0] }.toSet() shouldBe ids
            }
            val msgs = e.readUntilSeen(ids, sixty)
            val copies = msgs.copiesPerEventId()
            println(
                "L-05 attack concurrent relays: published counts ${drained.toList()}, ${msgs.size} messages for " +
                    "${ids.size} events, max copies ${copies.values.maxOrNull()}",
            )
            withClue("every message carries an outbox event id") {
                msgs.filter { it.eventId == null || it.eventId !in ids }.shouldBeEmpty()
            }
            msgs.mapNotNull { it.eventId }.toSet() shouldBe ids
            val taxpayerOf = events.associate { it.eventId.toString() to it.taxpayerId }
            msgs.groupBy { it.eventId!! }.forEach { (id, same) ->
                withClue("event $id: duplicates share key, headers and value") {
                    same.map { it.fingerprint }.toSet() shouldHaveSize 1
                    same.first().key shouldBe taxpayerOf.getValue(id)
                }
            }
            val byPosition = msgs.associateBy { it.partition to it.offset }
            delivery.forEach { (id, partition, offset) ->
                withClue("delivery row of event $id points at a copy of that event (p$partition@$offset)") {
                    byPosition[partition!!.toInt() to offset!!.toLong()]?.eventId shouldBe id
                }
            }
            val seen = msgs.firstSeenOrderByKey()
            events.groupBy { it.taxpayerId }.forEach { (tp, own) ->
                withClue(
                    "$tp: first-seen order is outbox order",
                ) { seen[tp] shouldBe own.map { it.eventId.toString() } }
            }
        }

    test("L-05 attack: 10 000 pending events drain within a generous bound (timing printed)")
        .config(tags = setOf(Integration), timeout = 15.minutes) {
            val e = pg.l05Env(broker)
            val n = 10_000
            val t0 = System.nanoTime()
            e.db.bulkAppendOutboxEvents(n, taxpayers = 5)
            val seedMs = (System.nanoTime() - t0) / 1_000_000
            e.pending() shouldHaveSize n
            val p = e.producer()
            val relay = e.relay(p)
            var drains = 0
            var published = 0
            val t1 = System.nanoTime()
            val deadline = t1 + Duration.ofMinutes(5).toNanos()
            while (published < n && System.nanoTime() < deadline) {
                val r = relay.drainOnce(500)
                drains += 1
                published += r.published
                withClue("drain $drains: $r") { r.failed shouldBe 0 }
            }
            val drainMs = (System.nanoTime() - t1) / 1_000_000
            val t2 = System.nanoTime()
            val idle = e.relay(p).drainOnce(500)
            val freshRelayIdleMs = (System.nanoTime() - t2) / 1_000_000
            closeQuietly(p)
            val perSecond = if (drainMs == 0L) n.toLong() else n * 1_000L / drainMs
            println(
                "L-05 attack throughput: seeded $n outbox rows in $seedMs ms; drained in $drainMs ms over $drains " +
                    "drains (~$perSecond events/s); a fresh relay's idle drain over $n delivered rows took " +
                    "$freshRelayIdleMs ms",
            )
            idle.published shouldBe 0
            e.pending().shouldBeEmpty()
            withClue("generous bound: 10 000 events in under 120 s (drainMs=$drainMs)") {
                (drainMs < 120_000L) shouldBe true
            }
            withClue("an idle drain after 10 000 deliveries takes under 5 s (ms=$freshRelayIdleMs)") {
                (freshRelayIdleMs < 5_000L) shouldBe true
            }
            val ids = e.eventIds()
            val msgs = e.readUntilSeen(ids, Duration.ofSeconds(120))
            msgs.mapNotNull { it.eventId }.toSet() shouldBe ids
            val seen = msgs.firstSeenOrderByKey()
            e.events().groupBy { it.taxpayerId }.forEach { (tp, own) ->
                withClue("$tp: order") { seen[tp] shouldBe own.map { it.eventId.toString() } }
            }
        }
})
