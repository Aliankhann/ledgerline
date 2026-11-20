package dev.ledgerline.spec

import dev.ledgerline.app.SubmitResult
import dev.ledgerline.app.ingestionModule
import dev.ledgerline.ledger.ReversalRefusedException
import dev.ledgerline.ledger.outbox.DrainResult
import dev.ledgerline.ledger.outbox.OutboxEventType
import dev.ledgerline.ledger.outbox.OutboxRelay
import dev.ledgerline.ledger.outbox.OutboxTopic
import dev.ledgerline.testsupport.FaultPoint
import dev.ledgerline.testsupport.Integration
import dev.ledgerline.testsupport.L02Json
import dev.ledgerline.testsupport.L05Activities
import dev.ledgerline.testsupport.L05RelayChild
import dev.ledgerline.testsupport.PostgresLedger
import dev.ledgerline.testsupport.RedpandaBroker
import dev.ledgerline.testsupport.TopicMessage
import dev.ledgerline.testsupport.closeQuietly
import dev.ledgerline.testsupport.failEveryCommitWithAnEntry
import dev.ledgerline.testsupport.l05Env
import dev.ledgerline.testsupport.launchRelayChild
import dev.ledgerline.testsupport.mutationsAllowedOn
import dev.ledgerline.testsupport.postJson
import dev.ledgerline.testsupport.postRaw
import dev.ledgerline.testsupport.prepareChildLogin
import dev.ledgerline.testsupport.stopFailingCommits
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * Spec for story L-05 (docs/stories/L-05-reliable-event-publication.md), written provisionally while the story is
 * Draft, on the owner's instruction. Every test needs Docker (Postgres 16 and `redpandadata/redpanda:v24.2.7`); the
 * property test is `dev.ledgerline.property.OutboxDeliveryPropertyTest`.
 *
 * PLACEHOLDERS (OWNER-INPUT; Dev adds them to docs/owner-inputs.md):
 * - C5: "sent" is an append-only `ledger.outbox_delivery` table (one row per confirmed delivery), never an UPDATE.
 * - C25: reversals and corrections write outbox events too: one outbox row per ledger entry, reversals included.
 * - C26: at-least-once delivery to `ledgerline.activities.v1`, key = taxpayer id; each event carries a stable event
 *   id, the outbox row id, so consumers can dedupe.
 *
 * DEVIATIONS FROM THE STORY TEXT:
 * - Crashes are injected at the producer handed to the relay ([dev.ledgerline.testsupport.FaultyProducer]), so the
 *   story's fault points need no hook in production code. AC4, AC5 and AC9 halt a real child JVM
 *   (`Runtime.halt`, see [L05RelayChild]); the property test aborts in-process. AC4's child posts through
 *   `IngestionService`, not HTTP, so "201 or no response" is checked as the client's retry being a duplicate.
 * - AC6 "stop Redpanda" is `docker pause`: stop/start would change the mapped port the broker advertises.
 * - The relay's topic is a constructor parameter, so each test drains to its own topic; AC3 uses the default.
 *
 * API CONTRACT (Dev implements exactly this; names are QE proposals, the owner may rename):
 *
 * ```kotlin
 * package dev.ledgerline.ledger.outbox
 * object OutboxTopic { const val NAME = "ledgerline.activities.v1" }
 * enum class OutboxEventType { ACTIVITY_POSTED, ENTRY_REVERSED }
 * // One ledger.outbox row. eventId = outbox row id (C26); entryId = the entry's ledger sequence (ADR 0005).
 * // For a reversal (C25) taxpayerId is the original activity's taxpayer and idempotencyKey is the reversal key.
 * data class OutboxEvent(val eventId: Long, val eventType: OutboxEventType, val taxpayerId: String,
 *                        val entryId: Long, val idempotencyKey: String)
 * class OutboxStore(dataSource: DataSource) {          // read-only; works as the app role
 *     fun events(): List<OutboxEvent>                  // every outbox row, ascending eventId
 *     fun pendingEventIds(): List<Long>                // rows with no ledger.outbox_delivery row, ascending
 * }
 * data class DrainResult(val published: Int, val failed: Int)
 * object OutboxProducers {                             // the production producer settings (acks=all, idempotent)
 *     fun create(bootstrapServers: String): org.apache.kafka.clients.producer.Producer<String, ByteArray>
 * }
 * class OutboxRelay(dataSource: DataSource, producer: Producer<String, ByteArray>,
 *                   topic: String = OutboxTopic.NAME) : AutoCloseable {
 *     // Synchronous. Sends up to [max] pending rows in ascending eventId order, waits for the broker's acks, then
 *     // appends a ledger.outbox_delivery row for each acked one. published = rows acked and recorded in this call;
 *     // failed = rows sent but not acked (they stay pending). Broker errors never throw; database errors do.
 *     // A row is recorded only after its ack. Rows of one taxpayer are never sent past an earlier unacked one.
 *     fun drainOnce(max: Int = 500): DrainResult
 *     fun start()                // background loop calling drainOnce; survives broker outages; returns at once
 *     fun stop()                 // stops the loop and waits for it (at most 10 s); does not close the producer
 *     override fun close()       // = stop()
 * }
 * ```
 *
 * Message on the topic (one per send; a re-send is byte-identical): key = taxpayerId (UTF-8); header `eventId` =
 * the event id as UTF-8 decimal; value = a UTF-8 JSON object with at least `eventId` (number), `eventType`,
 * `taxpayerId`, `idempotencyKey`, `entryId` (number), `sequence` (number, the ledger sequence) and, for
 * ENTRY_REVERSED, `reversesEntryId` (number). Partition = the client's default partitioner on the key. Not tested
 * here but expected by ADR 0005: ACTIVITY_POSTED should also carry the activity's fields, which L-06 will read.
 */
class L05OutboxSpec : FunSpec({

    val pg = PostgresLedger()
    val broker = RedpandaBroker()
    afterSpec {
        pg.close()
        broker.close()
    }
    val a1 = L05Activities.a1
    val key = L05Activities.A1_KEY
    val ten: Duration = Duration.ofSeconds(10)
    val thirty: Duration = Duration.ofSeconds(30)
    val sixty: Duration = Duration.ofSeconds(60)

    fun List<TopicMessage>.withEventId(id: Long) = filter { it.eventId == id.toString() }

    test("L-05 AC1: posting A1 commits exactly one entry and one outbox row referencing it, in one transaction")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            val entryId = e.post(a1)
            e.entriesFor(key) shouldBe 1L
            val rows =
                e.db.query(
                    e.db.owner,
                    "SELECT e.xmin::text, o.xmin::text FROM ledger.entry e JOIN ledger.outbox o " +
                        "ON o.entry_id = e.entry_id WHERE e.idempotency_key = '$key'",
                )
            rows shouldHaveSize 1
            withClue("same transaction: equal xmin") { rows[0][0] shouldBe rows[0][1] }
            e.db.rowCounts().getValue("outbox") shouldBe 1L
            val event = e.events().single()
            event.eventType shouldBe OutboxEventType.ACTIVITY_POSTED
            event.entryId shouldBe entryId
            event.taxpayerId shouldBe "TP-0001"
            event.idempotencyKey shouldBe key
            e.pending() shouldContainExactly listOf(event.eventId)
        }

    test("L-05 AC2: a failure after the ledger insert and before commit writes neither; 5xx; the retry is 201")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            e.db.failEveryCommitWithAnEntry()
            testApplication {
                application { ingestionModule(e.service) }
                // the test host rethrows an exception that escapes the route; a real server answers 500
                val first = runCatching { client.postRaw(L02Json.body()) }
                withClue("first post: $first") {
                    (first.isFailure || first.getOrThrow().first.value in 500..599) shouldBe true
                }
                e.entriesFor(key) shouldBe 0L
                val written = e.db.rowCounts().filterKeys { it in setOf("entry", "line", "activity", "outbox") }
                written.values.toSet() shouldBe setOf(0L)
                e.events().shouldBeEmpty()
                e.db.stopFailingCommits()
                client.postJson(L02Json.body()).first shouldBe HttpStatusCode.Created
            }
            e.entriesFor(key) shouldBe 1L
            e.events().map { it.idempotencyKey } shouldContainExactly listOf(key)
        }

    test("L-05 AC3: within 10 s the default topic has one message: key TP-0001, header eventId, stable value shape")
        .config(tags = setOf(Integration)) {
            OutboxTopic.NAME shouldBe "ledgerline.activities.v1"
            val e = pg.l05Env(broker)
            broker.createTopic(OutboxTopic.NAME)
            val producer = e.producer()
            val relay = OutboxRelay(e.db.app, producer)
            relay.start()
            try {
                val entryId = e.post(a1)
                val event = e.events().single()
                val sequence = e.repo.entries().single { it.idempotencyKey == key }.sequence
                val msgs = broker.read(OutboxTopic.NAME, ten) { m -> m.any { it.eventId == event.eventId.toString() } }
                val m = msgs.single()
                m.key shouldBe "TP-0001"
                m.eventId shouldBe event.eventId.toString()
                m.field("eventId") shouldBe event.eventId.toString()
                m.field("eventType") shouldBe "ACTIVITY_POSTED"
                m.field("idempotencyKey") shouldBe key
                m.field("taxpayerId") shouldBe "TP-0001"
                m.field("entryId") shouldBe entryId.toString()
                m.field("sequence") shouldBe sequence.toString()
                e.awaitDelivered(ten).shouldBeEmpty()
            } finally {
                relay.stop()
                closeQuietly(producer)
            }
        }

    test("L-05 AC4: a process halted after commit and before send loses nothing; after restart exactly one message")
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            listOf(
                "post-then-halt" to "halted after the commit, before any drain",
                "relay" to "halted in the first send",
            )
                .forEach { (mode, what) ->
                    withClue(what) {
                        val e = pg.l05Env(broker)
                        prepareChildLogin(e.db)
                        val (code, out) =
                            launchRelayChild(e, mode, FaultPoint.BEFORE_SEND, atSend = 1, postA1 = true).await()
                        withClue("child output:\n$out") { code shouldBe L05RelayChild.HALTED }
                        e.entriesFor(key) shouldBe 1L
                        val event = e.events().single()
                        e.readAll().shouldBeEmpty()
                        // the client saw 201 or no response; its retry must be a duplicate, not a second posting
                        (e.service.submit(a1) as SubmitResult.Accepted).alreadyExisted shouldBe true
                        e.events() shouldHaveSize 1
                        val msgs = e.draining { e.readUntilSeen(setOf(event.eventId.toString()), thirty) }
                        msgs.withEventId(event.eventId) shouldHaveSize 1
                        msgs shouldHaveSize 1
                        e.entriesFor(key) shouldBe 1L
                        e.pending().shouldBeEmpty()
                    }
                }
        }

    test(
        "L-05 AC5: a process halted after the broker ack and before marking leaves 1 or 2 identical copies; then marks",
    )
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            val e = pg.l05Env(broker)
            prepareChildLogin(e.db)
            val (code, out) = launchRelayChild(e, "relay", FaultPoint.AFTER_ACK, atSend = 1, postA1 = true).await()
            withClue("child output:\n$out") { code shouldBe L05RelayChild.HALTED }
            val event = e.events().single()
            withClue("the broker acked before the halt") { e.readAll().withEventId(event.eventId) shouldHaveSize 1 }
            withClue("the relay cannot have recorded a delivery it never saw acked") {
                e.pending() shouldContainExactly listOf(event.eventId)
            }
            val msgs =
                e.draining {
                    e.awaitDelivered(thirty).shouldBeEmpty()
                    e.readAll()
                }
            val copies = msgs.withEventId(event.eventId)
            copies.size shouldBeInRange 1..2
            copies.map { it.fingerprint }.toSet() shouldHaveSize 1
            withClue("no message for A1 carries any other event id") {
                msgs.filter { it.field("idempotencyKey") == key }.map { it.eventId }.toSet() shouldBe
                    setOf(event.eventId.toString())
                msgs shouldHaveSize copies.size
            }
            e.pending().shouldBeEmpty()
        }

    test("L-05 AC6: with the broker down, 10 postings return 201; all 10 drain within 60 s of its return")
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            val e = pg.l05Env(broker)
            val keys = (101..110).map { "TP-0001-2025-%04d".format(it) }
            e.draining {
                testApplication {
                    application { ingestionModule(e.service) }
                    val statuses =
                        broker.whileDown {
                            val s =
                                keys.map { k -> client.postJson(L05Activities.ac6Json(k)).first }
                            Thread.sleep(3_000)
                            withClue("nothing is recorded as delivered while the broker is down") {
                                e.pending() shouldHaveSize 10
                            }
                            s
                        }
                    statuses shouldBe List(10) { HttpStatusCode.Created }
                }
                val ids = e.eventIds()
                ids shouldHaveSize 10
                val msgs = e.readUntilSeen(ids, sixty)
                msgs.mapNotNull { it.eventId }.toSet() shouldBe ids
                e.awaitDelivered(thirty).shouldBeEmpty()
            }
        }

    test("L-05 AC7: 50 postings for TP-0001 appear with key TP-0001 in strictly increasing ledger sequence order")
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            val e = pg.l05Env(broker)
            val msgs =
                e.draining {
                    L05Activities.ac7.forEach { e.post(it) }
                    e.readUntilSeen(e.eventIds(), sixty)
                }
            msgs shouldHaveSize 50
            msgs.map { it.key }.toSet() shouldBe setOf("TP-0001")
            msgs.map { it.partition }.toSet() shouldHaveSize 1
            val sequences = msgs.sortedBy { it.offset }.map { it.field("sequence")!!.toLong() }
            sequences.zipWithNext().forEach { (a, b) -> withClue("$a then $b") { (b > a) shouldBe true } }
            sequences.toSet() shouldBe e.repo.entries().map { it.sequence }.toSet()
        }

    test("L-05 ordering: per taxpayer key, partition order follows outbox order (5 taxpayers posting concurrently)")
        .config(tags = setOf(Integration), timeout = 10.minutes) {
            val e = pg.l05Env(broker)
            val pool = Executors.newFixedThreadPool(5)
            val msgs =
                e.draining {
                    (1..5).map { t ->
                        pool.submit<Unit> {
                            (1..20).forEach { n ->
                                e.post(
                                    L05Activities.deposit(
                                        "TP-%04d-2025-%04d".format(t, 300 + n),
                                        1_000L * n,
                                        LocalDate.of(2025, 6, 1),
                                        n.toLong(),
                                        "TP-%04d".format(t),
                                    ),
                                )
                            }
                        }
                    }.forEach { it.get(120, TimeUnit.SECONDS) }
                    e.readUntilSeen(e.eventIds(), sixty)
                }
            pool.shutdown()
            val events = e.events()
            events shouldHaveSize 100
            withClue("outbox order is ledger sequence order") {
                events.map { it.entryId } shouldBe events.map { it.entryId }.sorted()
            }
            events.groupBy { it.taxpayerId }.forEach { (tp, own) ->
                withClue(tp) {
                    val onTopic = msgs.filter { it.key == tp }
                    onTopic.map { it.partition }.toSet() shouldHaveSize 1
                    onTopic.sortedBy { it.offset }.map { it.eventId } shouldBe own.map { it.eventId.toString() }
                }
            }
        }

    test("L-05 AC8: a reversal is published as ENTRY_REVERSED after ACTIVITY_POSTED, referencing the original entryId")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            val original = e.post(a1)
            val reversal = e.repo.reverse(original, "REV-$key", "L-05 AC8")
            val events = e.events()
            events.map { it.eventType } shouldBe listOf(OutboxEventType.ACTIVITY_POSTED, OutboxEventType.ENTRY_REVERSED)
            events.map { it.entryId } shouldBe listOf(original, reversal.sequence)
            events[1].taxpayerId shouldBe "TP-0001"
            events[1].idempotencyKey shouldBe "REV-$key"
            val msgs = e.draining { e.readUntilSeen(e.eventIds(), thirty) }.sortedBy { it.offset }
            msgs shouldHaveSize 2
            msgs.map { it.key } shouldBe listOf("TP-0001", "TP-0001")
            msgs.map { it.field("eventType") } shouldBe listOf("ACTIVITY_POSTED", "ENTRY_REVERSED")
            msgs[0].field("entryId") shouldBe original.toString()
            msgs[1].field("entryId") shouldBe reversal.sequence.toString()
            msgs[1].field("reversesEntryId") shouldBe original.toString()
        }

    test("L-05 C25: a correction publishes ENTRY_REVERSED and ACTIVITY_POSTED; repeats and refusals publish nothing")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            val original = e.post(a1)
            val replacement = a1.copy(idempotencyKey = "TP-0001-2025-0012", amountMinor = 301_000L)
            val fix = e.repo.correct(original, "COR-$key", "L-05 C25", replacement)
            val types = mapOf(fix.reversal.sequence to "ENTRY_REVERSED", fix.replacement.sequence to "ACTIVITY_POSTED")
            val expected = listOf(original) + types.keys.sorted()

            fun outboxMatchesEntries() {
                val events = e.events()
                events.map { it.entryId } shouldBe expected
                events.drop(1).forEach { ev -> ev.eventType.name shouldBe types.getValue(ev.entryId) }
                e.db.rowCounts().getValue("outbox") shouldBe e.db.rowCounts().getValue("entry")
            }
            outboxMatchesEntries()
            e.repo.correct(original, "COR-$key", "L-05 C25", replacement) shouldBe fix
            shouldThrow<ReversalRefusedException> { e.repo.reverse(original, "REV-$key", "again") }
            (e.service.submit(a1) as SubmitResult.Accepted).alreadyExisted shouldBe true
            outboxMatchesEntries()
            val msgs = e.draining { e.readUntilSeen(e.eventIds(), thirty) }
            msgs shouldHaveSize 3
            val reversed = msgs.single { it.field("eventType") == "ENTRY_REVERSED" }
            reversed.field("reversesEntryId") shouldBe original.toString()
            msgs.single { it.field("idempotencyKey") == replacement.idempotencyKey }.field("eventType") shouldBe
                "ACTIVITY_POSTED"
        }

    test("L-05 C5: delivery is appended to ledger.outbox_delivery; no other ledger row changes and it is append-only")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            L05Activities.deposits(401..403, 500L, LocalDate.of(2025, 7, 1)).forEach { e.post(it) }
            val before = e.db.snapshot()
            val p = e.producer()
            e.relay(p).drainOnce(10) shouldBe DrainResult(published = 3, failed = 0)
            closeQuietly(p)
            e.pending().shouldBeEmpty()
            val after = e.db.snapshot()
            withClue(
                "draining only appends delivery rows",
            ) { after - "outbox_delivery" shouldBe before - "outbox_delivery" }
            (after.getValue("outbox_delivery").size >= 3) shouldBe true
            e.db.mutationsAllowedOn("outbox_delivery").shouldBeEmpty()
            e.db.snapshot() shouldBe after
        }

    test("L-05 contract: drainOnce(max) sends at most max rows in outbox order and never re-sends delivered rows")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            L05Activities.deposits(501..505, 700L, LocalDate.of(2025, 8, 1)).forEach { e.post(it) }
            val ids = e.events().map { it.eventId }
            val p = e.producer()
            val relay = e.relay(p)
            relay.drainOnce(2) shouldBe DrainResult(published = 2, failed = 0)
            e.pending() shouldBe ids.drop(2)
            relay.drainOnce(10) shouldBe DrainResult(published = 3, failed = 0)
            relay.drainOnce(10) shouldBe DrainResult(published = 0, failed = 0)
            closeQuietly(p)
            e.readAll().sortedBy { it.offset }.map { it.eventId } shouldBe ids.map { it.toString() }
        }

    test("L-05 contract: start() drains in the background; after stop() new rows wait for the next relay")
        .config(tags = setOf(Integration)) {
            val e = pg.l05Env(broker)
            val (d1, d2) = L05Activities.deposits(601..602, 900L, LocalDate.of(2025, 9, 1))
            e.draining {
                e.post(d1)
                e.readUntilSeen(e.eventIds(), ten) shouldHaveSize 1
            }
            e.post(d2)
            Thread.sleep(3_000)
            e.readAll() shouldHaveSize 1
            e.pending() shouldHaveSize 1
            e.draining { e.readUntilSeen(e.eventIds(), ten) } shouldHaveSize 2
        }

    test("L-05 AC9: 200 activities across 5 taxpayers, drainer halted 5 times at random fault points: no loss")
        .config(tags = setOf(Integration), timeout = 20.minutes) {
            val seed = 20_251_120L
            println("L-05 AC9 seed $seed")
            val r = Random(seed)
            val e = pg.l05Env(broker)
            prepareChildLogin(e.db)
            val all = L05Activities.generated(seed, 200, 5)
            all.chunked(40).forEachIndexed { round, chunk ->
                val point = FaultPoint.entries[r.nextInt(FaultPoint.entries.size)]
                val atSend = 1 + r.nextInt(e.pending().size + chunk.size)
                val child = launchRelayChild(e, "relay", point, atSend)
                chunk.forEach { e.post(it) }
                val (code, out) = child.await()
                withClue(
                    "round ${round + 1}: ${point.storyName} at send $atSend\n$out",
                ) { code shouldBe L05RelayChild.HALTED }
            }
            val ids = e.eventIds()
            ids shouldHaveSize 200
            val msgs =
                e.draining {
                    e.awaitDelivered(sixty).shouldBeEmpty()
                    e.readUntilSeen(ids, sixty)
                }
            withClue("every message carries an eventId header") { msgs.filter { it.eventId == null }.shouldBeEmpty() }
            withClue(
                "distinct event ids on the topic equal the outbox",
            ) { msgs.mapNotNull { it.eventId }.toSet() shouldBe ids }
            val taxpayerOf = e.events().associate { it.eventId.toString() to it.taxpayerId }
            msgs.groupBy { it.eventId!! }.forEach { (id, copies) ->
                withClue("event $id: copies are identical and keyed by taxpayer") {
                    copies.map { it.fingerprint }.toSet() shouldHaveSize 1
                    copies.first().key shouldBe taxpayerOf.getValue(id)
                }
            }
            println("L-05 AC9: ${msgs.size} messages for ${ids.size} events (${msgs.size - ids.size} duplicates)")
        }
})
