package dev.ledgerline.testsupport

import dev.ledgerline.app.IngestionService
import dev.ledgerline.app.SubmitResult
import dev.ledgerline.core.Activity
import dev.ledgerline.core.ActivityType
import dev.ledgerline.ledger.LedgerRepository
import dev.ledgerline.ledger.outbox.OutboxEvent
import dev.ledgerline.ledger.outbox.OutboxProducers
import dev.ledgerline.ledger.outbox.OutboxRelay
import dev.ledgerline.ledger.outbox.OutboxStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.testcontainers.redpanda.RedpandaContainer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One Redpanda broker (Testcontainers, image pinned by the L-05 task) shared by a spec. Starts on first use. Each test
 * gets its own topic from [freshTopic], because every fresh database numbers its outbox rows (event ids) from 1 again.
 */
class RedpandaBroker : AutoCloseable {
    companion object {
        const val IMAGE = "redpandadata/redpanda:v24.2.7"
    }

    private val started = lazy { RedpandaContainer(IMAGE).also { it.start() } }
    private val container by started
    private var topics = 0

    val bootstrap: String get() = container.bootstrapServers

    /** A new topic with [partitions] partitions, so per-key ordering is tested across more than one partition. */
    fun freshTopic(partitions: Int = 3): String {
        topics += 1
        return "l05.t$topics.${System.nanoTime()}".also { createTopic(it, partitions) }
    }

    fun createTopic(
        name: String,
        partitions: Int = 3,
    ) {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use {
            it.createTopics(listOf(NewTopic(name, partitions, 1.toShort()))).all().get(30, TimeUnit.SECONDS)
        }
    }

    /**
     * Makes the broker unreachable for [block] by freezing its container (`docker pause`), then thaws it. Stands in
     * for "stop Redpanda" in L-05 AC6: unlike stop/start it keeps the mapped port and the advertised address, so the
     * relay's producer can reconnect to the same broker when it returns.
     */
    inline fun <T> whileDown(block: () -> T): T {
        pause()
        try {
            return block()
        } finally {
            resume()
        }
    }

    fun pause() {
        container.dockerClient.pauseContainerCmd(container.containerId).exec()
    }

    fun resume() {
        container.dockerClient.unpauseContainerCmd(container.containerId).exec()
    }

    /**
     * Reads [topic] from the beginning of every partition until [done] holds for everything read so far, then keeps
     * reading for [settle] more (to catch late duplicates), or until [timeout]. Returns every message read.
     */
    fun read(
        topic: String,
        timeout: Duration,
        settle: Duration = Duration.ofSeconds(1),
        done: (List<TopicMessage>) -> Boolean,
    ): List<TopicMessage> {
        val props =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            )
        KafkaConsumer(props, StringDeserializer(), ByteArrayDeserializer()).use { c ->
            val parts = c.partitionsFor(topic, Duration.ofSeconds(30)).map { TopicPartition(topic, it.partition()) }
            c.assign(parts)
            c.seekToBeginning(parts)
            val out = mutableListOf<TopicMessage>()
            val deadline = System.nanoTime() + timeout.toNanos()
            var doneAt: Long? = null
            while (true) {
                c.poll(Duration.ofMillis(200)).forEach { out += TopicMessage.of(it) }
                val now = System.nanoTime()
                if (doneAt == null && done(out)) doneAt = now
                if (doneAt != null && now - doneAt >= settle.toNanos()) break
                if (now > deadline) break
            }
            return out.sortedWith(compareBy({ it.partition }, { it.offset }))
        }
    }

    override fun close() {
        if (started.isInitialized()) container.stop()
    }
}

/** One record read back from the topic. Byte arrays are kept raw so copies can be compared byte for byte. */
class TopicMessage(
    val partition: Int,
    val offset: Long,
    val key: String?,
    val headers: List<Pair<String, ByteArray>>,
    val value: ByteArray,
) {
    /** The `eventId` header as UTF-8 text, or null if absent. */
    val eventId: String? get() = headers.lastOrNull { it.first == "eventId" }?.second?.toString(Charsets.UTF_8)

    val json: JsonObject by lazy { Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject }

    /** A value field as text (a JSON number or string gives its digits or characters), or null if absent. */
    fun field(name: String): String? = json[name]?.jsonPrimitive?.content

    /** SHA-256 over the key, every header and the value, length-prefixed: equal exactly when copies are identical. */
    val fingerprint: String by lazy {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun chunk(b: ByteArray?) {
                out.writeInt(b?.size ?: -1)
                if (b != null) out.write(b)
            }
            chunk(key?.toByteArray(Charsets.UTF_8))
            out.writeInt(headers.size)
            headers.forEach { (k, v) ->
                chunk(k.toByteArray(Charsets.UTF_8))
                chunk(v)
            }
            chunk(value)
        }
        MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String =
        "p$partition@$offset key=$key eventId=$eventId value=${value.toString(Charsets.UTF_8).take(300)}"

    companion object {
        fun of(r: ConsumerRecord<String, ByteArray>): TopicMessage =
            TopicMessage(r.partition(), r.offset(), r.key(), r.headers().map { it.key() to it.value() }, r.value())
    }
}

/** Activities named by story L-05. A1 is L-02's A1 (`TP-0001-2025-0002`, a BUY for TP-0001). */
object L05Activities {
    val a1: Activity = L02Activities.a1
    const val A1_KEY = L02Activities.KEY

    /** A CAD deposit of [amountMinor] for [taxpayer]'s non-registered account; `sourceSeq` is [seq]. */
    fun deposit(
        key: String,
        amountMinor: Long,
        date: LocalDate,
        seq: Long,
        taxpayer: String = Activities.TAXPAYER,
    ): Activity =
        Activities.activity(
            key,
            ActivityType.DEPOSIT,
            date,
            amountMinor = amountMinor,
            accountId = "$taxpayer-NR1",
            taxpayerId = taxpayer,
            sourceSeq = seq,
        )

    /** Deposits keyed `TP-0001-2025-<n>` for each n in [numbers], [amountMinor] each, on [date]; `sourceSeq` = n. */
    fun deposits(
        numbers: IntRange,
        amountMinor: Long,
        date: LocalDate,
    ): List<Activity> = numbers.map { n -> deposit("TP-0001-2025-%04d".format(n), amountMinor, date, n.toLong()) }

    /** AC6: the JSON body of deposit [key], 1000 CAD for TP-0001-NR1 dated 2025-04-01 (story L-05 AC6). */
    fun ac6Json(key: String): String =
        L02Json.body(
            "idempotencyKey" to key,
            "activityType" to "DEPOSIT",
            "effectiveDate" to "2025-04-01",
            "settlementDate" to "2025-04-01",
            "securityId" to null,
            "quantity" to null,
            "amount" to "1000.00",
            "commission" to "0.00",
        )

    /** AC7: deposits `TP-0001-2025-0201` to `-0250`, 100 CAD each (the story gives no date; QE uses 2025-05-01). */
    val ac7: List<Activity> = deposits(201..250, 10_000L, LocalDate.of(2025, 5, 1))

    /**
     * [count] cash-only activities (deposits, withdrawals, fees; every one valid on any ledger) for taxpayers
     * `TP-0001` to `TP-000n`, deterministic for [seed]. Keys are unique within one call.
     */
    fun generated(
        seed: Long,
        count: Int,
        taxpayers: Int,
    ): List<Activity> {
        val r = Random(seed)
        val types = listOf(ActivityType.DEPOSIT, ActivityType.WITHDRAWAL, ActivityType.FEE)
        return (1..count).map { i ->
            val tp = "TP-%04d".format(1 + r.nextInt(taxpayers))
            Activities.activity(
                "$tp-2025-G%05d".format(i),
                types[r.nextInt(types.size)],
                LocalDate.of(2025, 1, 1).plusDays(r.nextLong(365)),
                amountMinor = 1L + r.nextLong(1_000_000L),
                accountId = "$tp-NR1",
                taxpayerId = tp,
                sourceSeq = i.toLong(),
            )
        }
    }
}

/** A fresh migrated ledger, the shared broker and a topic of its own, with the L-05 API on top. */
class L05Env(val db: LedgerDb, val broker: RedpandaBroker, val topic: String) {
    val repo = LedgerRepository(db.app)
    val service = IngestionService(repo, MutableClock(L02Env.T1))
    val store = OutboxStore(db.app)

    fun producer(): Producer<String, ByteArray> = OutboxProducers.create(broker.bootstrap)

    /** A relay on this env's topic, running as the app role (SELECT and INSERT only). */
    fun relay(producer: Producer<String, ByteArray> = producer()): OutboxRelay = OutboxRelay(db.app, producer, topic)

    /** Submits [a], which must be new, and returns its entry id. */
    fun post(a: Activity): Long {
        val r = service.submit(a)
        check(r is SubmitResult.Accepted && !r.alreadyExisted) { "expected a new posting for ${a.idempotencyKey}: $r" }
        return r.entryId
    }

    fun events(): List<OutboxEvent> = store.events()

    fun eventIds(): Set<String> = events().map { it.eventId.toString() }.toSet()

    fun pending(): List<Long> = store.pendingEventIds()

    /** Reads this env's topic until every id in [ids] has been seen at least once (or [timeout]). */
    fun readUntilSeen(
        ids: Set<String>,
        timeout: Duration,
        settle: Duration = Duration.ofSeconds(2),
    ): List<TopicMessage> =
        broker.read(
            topic,
            timeout,
            settle,
        ) { msgs -> msgs.mapNotNull { it.eventId }.containsAll(ids) }

    /** Everything on this env's topic, after waiting [wait] for stragglers. */
    fun readAll(wait: Duration = Duration.ofSeconds(2)): List<TopicMessage> =
        broker.read(topic, wait, Duration.ZERO) { false }

    /** Runs [block] while a relay on a fresh production producer drains in the background; then stops both. */
    inline fun <T> draining(block: (OutboxRelay) -> T): T {
        val p = producer()
        val r = relay(p)
        r.start()
        try {
            return block(r)
        } finally {
            r.stop()
            closeQuietly(p)
        }
    }

    /** Polls until no outbox row is pending, or [timeout]. Returns the rows still pending. */
    fun awaitDelivered(timeout: Duration): List<Long> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val p = pending()
            if (p.isEmpty() || System.nanoTime() > deadline) return p
            Thread.sleep(100)
        }
    }

    /** Ledger entries stored for [key] (a crash or retry must never post a second one). */
    fun entriesFor(key: String): Long =
        db.query(db.owner, "SELECT count(*) FROM ledger.entry WHERE idempotency_key = '$key'")[0][0]!!.toLong()
}

/**
 * L-05 AC2's fault: a deferred constraint trigger that fails, at commit, every transaction that inserted a ledger
 * entry, i.e. after the ledger and outbox inserts and before the commit completes. Installed as the table owner.
 */
fun LedgerDb.failEveryCommitWithAnEntry() {
    listOf(
        "CREATE FUNCTION ledger.l05_fail_commit() RETURNS trigger LANGUAGE plpgsql AS " +
            "\$\$ BEGIN RAISE EXCEPTION 'L-05 AC2 injected failure before commit'; END \$\$",
        "CREATE CONSTRAINT TRIGGER l05_fail_commit AFTER INSERT ON ledger.entry " +
            "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.l05_fail_commit()",
    ).forEach { sql -> failureOf(owner, sql)?.let { throw it } }
}

fun LedgerDb.stopFailingCommits() {
    failureOf(owner, "DROP TRIGGER l05_fail_commit ON ledger.entry")?.let { throw it }
}

/**
 * The UPDATE, DELETE and TRUNCATE statements on `ledger.[table]` that SUCCEEDED as the app role or as the table owner
 * (each in a rolled-back transaction, so nothing changes either way). Empty means the table is append-only.
 */
fun LedgerDb.mutationsAllowedOn(table: String): List<String> {
    val col =
        query(
            owner,
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'ledger' " +
                "AND table_name = '$table' ORDER BY ordinal_position LIMIT 1",
        ).singleOrNull()?.get(0) ?: return listOf("<no table ledger.$table>")
    val statements =
        listOf("UPDATE ledger.$table SET $col = $col", "DELETE FROM ledger.$table", "TRUNCATE ledger.$table")
    return listOf("app" to app, "owner" to owner).flatMap { (who, ds) ->
        statements.filter { failureInRolledBackTx(ds, it) == null }.map { "$who: $it" }
    }
}

fun PostgresLedger.l05Env(broker: RedpandaBroker): L05Env = L05Env(freshDatabase(), broker, broker.freshTopic())

/** Closes [p] without waiting and without letting a failure (for example after a crash) escape. */
fun closeQuietly(p: Producer<String, ByteArray>) {
    runCatching { p.close(Duration.ZERO) }
}
