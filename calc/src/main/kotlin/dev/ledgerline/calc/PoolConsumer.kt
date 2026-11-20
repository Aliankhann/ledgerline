package dev.ledgerline.calc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/**
 * Consumes the activity topic and applies each event to the live pools exactly once (story L-12).
 *
 * Delivery is at least once; the effect is once. Per record, ONE Postgres transaction and ONE commit holds the inbox
 * row (the `eventId` header), the pool change and the next offset. The offsets live in `calc.consumer_offset`, not in
 * Kafka: the consumer assigns every partition itself and seeks to the stored offset (earliest if none), so a crash
 * between the database commit and anything Kafka-side loses nothing and repeats nothing. A record whose event id is
 * already in the inbox only advances the offset.
 *
 * Writers are serialized by a transaction-scoped advisory lock taken first, so two consumers (a rebalance, or two
 * groups) cannot both apply an event or interleave a pool read-modify-write.
 */
class PoolConsumer(
    private val dataSource: DataSource,
    consumerConfig: Map<String, Any>,
    private val engine: AcbEngine,
    private val topic: String = "ledgerline.activities.v1",
) : AutoCloseable {
    private val group = consumerConfig["group.id"]?.toString() ?: "calc"
    private val kafka =
        KafkaConsumer(
            consumerConfig +
                mapOf(
                    "enable.auto.commit" to false,
                    "key.deserializer" to StringDeserializer::class.java.name,
                    "value.deserializer" to ByteArrayDeserializer::class.java.name,
                ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        )
    private var assigned = false

    @Volatile private var running = false
    private var loop: Thread? = null

    /** Handles up to [max] records and returns how many (duplicates included). A failure rolls back and rethrows. */
    @Synchronized
    fun pollOnce(max: Int = 500): Int {
        if (!assigned && !assign()) return 0
        var handled = 0
        try {
            val records = kafka.poll(POLL_TIMEOUT)
            for (partition in records.partitions()) {
                val list = records.records(partition)
                for (record in list) {
                    if (handled >= max) {
                        kafka.seek(partition, record.offset())
                        break
                    }
                    handle(record)
                    handled += 1
                }
            }
        } catch (e: Exception) {
            assigned = false // positions in memory are now suspect: re-seek from the database next time
            throw e
        }
        return handled
    }

    fun start() {
        if (running) return
        running = true
        loop =
            Thread({
                while (running) {
                    val n =
                        try {
                            pollOnce()
                        } catch (e: Exception) {
                            System.err.println("pool consumer: ${e.message}; retrying")
                            Thread.sleep(RETRY_MS)
                            0
                        }
                    if (n == 0) Thread.sleep(IDLE_MS)
                }
            }, "pool-consumer").also { it.start() }
    }

    fun stop() {
        running = false
        loop?.join(STOP_WAIT_MS)
        loop = null
    }

    override fun close() {
        stop()
        synchronized(this) { kafka.close(Duration.ofSeconds(2)) }
    }

    private fun assign(): Boolean {
        val partitions =
            kafka.partitionsFor(
                topic,
                Duration.ofSeconds(5),
            )?.map { TopicPartition(topic, it.partition()) }
        if (partitions.isNullOrEmpty()) return false
        kafka.assign(partitions)
        val stored = storedOffsets()
        partitions.forEach { tp ->
            stored[tp.partition()]?.let { kafka.seek(tp, it) } ?: kafka.seekToBeginning(listOf(tp))
        }
        assigned = true
        return true
    }

    private fun storedOffsets(): Map<Int, Long> =
        dataSource.connection.use { c ->
            c.prepareStatement(
                "SELECT partition_id, next_offset FROM calc.consumer_offset WHERE group_id = ? AND topic = ?",
            )
                .use { st ->
                    st.setString(1, group)
                    st.setString(2, topic)
                    st.executeQuery().use { rs ->
                        buildMap { while (rs.next()) put(rs.getInt(1), rs.getLong(2)) }
                    }
                }
        }

    private fun handle(record: ConsumerRecord<String, ByteArray>) {
        val eventId =
            record.headers().lastHeader("eventId")?.value()?.toString(Charsets.UTF_8)?.toLong()
                ?: error("record ${record.partition()}@${record.offset()} has no eventId header")
        val posted = EventDecoder.decode(record.value())
        val reversed = EventDecoder.reversedEntryId(record.value())
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock($LOCK_KEY)") }
                if (!inInbox(c, eventId)) {
                    posted?.let { applyAndStore(c, it) }
                    reversed?.let { ReversalRecompute(engine).apply(c, it, eventEntryId(record.value())) }
                    c.prepareStatement("INSERT INTO calc.inbox (event_id) VALUES (?) ON CONFLICT DO NOTHING").use {
                        it.setLong(1, eventId)
                        it.executeUpdate()
                    }
                }
                storeOffset(c, record.partition(), record.offset() + 1)
                c.commit()
            } catch (e: Exception) {
                runCatching { c.rollback() }
                throw e
            } finally {
                runCatching { c.autoCommit = true }
            }
        }
    }

    private fun eventEntryId(value: ByteArray): Long =
        Json.parseToJsonElement(value.toString(Charsets.UTF_8)).jsonObject.getValue("entryId").jsonPrimitive.long

    private fun inInbox(
        c: Connection,
        eventId: Long,
    ): Boolean =
        c.prepareStatement("SELECT 1 FROM calc.inbox WHERE event_id = ?").use {
            it.setLong(1, eventId)
            it.executeQuery().use { rs -> rs.next() }
        }

    private fun applyAndStore(
        c: Connection,
        posted: PostedActivity,
    ) {
        if (!posted.inPool()) return
        val taxpayer = posted.activity.taxpayerId
        val security = requireNotNull(posted.activity.securityId)
        val current =
            c.prepareStatement(
                "SELECT shares, total_acb FROM calc.pool WHERE taxpayer_id = ? AND security_id = ?",
            ).use {
                it.setString(1, taxpayer)
                it.setString(2, security)
                it.executeQuery().use { rs ->
                    val key = PoolKey(taxpayer, security)
                    if (rs.next()) AcbPool(key, rs.getLong(1), rs.getLong(2), emptyList()) else null
                }
            }
        val pools = listOfNotNull(current).associateBy { it.key }
        val (_, updated) = requireNotNull(applyToPool(engine, pools, posted))
        // Derived state: updated in place (the calc schema is not append-only; see V1__calc_state.sql).
        c.prepareStatement(
            "INSERT INTO calc.pool (taxpayer_id, security_id, shares, total_acb) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (taxpayer_id, security_id) DO UPDATE SET shares = EXCLUDED.shares, " +
                "total_acb = EXCLUDED.total_acb",
        ).use {
            it.setString(1, taxpayer)
            it.setString(2, security)
            it.setLong(3, updated.shares)
            it.setLong(4, updated.totalAcb)
            it.executeUpdate()
        }
    }

    private fun storeOffset(
        c: Connection,
        partition: Int,
        next: Long,
    ) {
        c.prepareStatement(
            "INSERT INTO calc.consumer_offset (group_id, topic, partition_id, next_offset) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (group_id, topic, partition_id) DO UPDATE " +
                "SET next_offset = GREATEST(calc.consumer_offset.next_offset, EXCLUDED.next_offset)",
        ).use {
            it.setString(1, group)
            it.setString(2, topic)
            it.setInt(3, partition)
            it.setLong(4, next)
            it.executeUpdate()
        }
    }

    private companion object {
        val POLL_TIMEOUT: Duration = Duration.ofMillis(300)
        const val LOCK_KEY = 4_200_012L
        const val IDLE_MS = 50L
        const val RETRY_MS = 500L
        const val STOP_WAIT_MS = 10_000L
    }
}
