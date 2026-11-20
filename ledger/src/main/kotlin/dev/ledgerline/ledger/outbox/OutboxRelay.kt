package dev.ledgerline.ledger.outbox

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.internals.RecordHeader
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

/**
 * Drains `ledger.outbox` to [topic] (OWNER-INPUT: C26, at-least-once). Each message: key = taxpayer id, header
 * `eventId` = the outbox row id, value = `eventId` plus the stored event body. A row is recorded in
 * `ledger.outbox_delivery` (OWNER-INPUT: C5) only after the broker acknowledged it, so a crash at any point leaves the
 * row pending and it is sent again, byte for byte the same. The relay does not close [producer].
 *
 * Per drain: one query for the batch, every send pipelined (the producer keeps per-partition order), one wait for the
 * acks, one transaction that appends all delivery rows. No lock or transaction per event.
 */
class OutboxRelay(
    dataSource: DataSource,
    private val producer: Producer<String, ByteArray>,
    private val topic: String = OutboxTopic.NAME,
) : AutoCloseable {
    private val store = OutboxStore(dataSource)
    private val drainLock = ReentrantLock()

    /**
     * Every event id up to here is delivered, so queries start above it. Valid because event ids increase in commit
     * order (V7) and delivery rows are never removed. Starts at 0 for each relay.
     */
    private var deliveredPrefix = 0L

    private var worker: Thread? = null
    private var stopSignal = CountDownLatch(0)

    /**
     * Sends up to [max] pending rows in ascending event id order, waits for the acks and records the acked ones.
     * Broker errors never throw (those rows count as failed and stay pending); database errors do. Once a row of a
     * taxpayer fails, later rows of that taxpayer in this batch are not recorded either, so a taxpayer's events are
     * never recorded past an earlier unacknowledged one.
     */
    fun drainOnce(max: Int = 500): DrainResult =
        drainLock.withLock {
            require(max > 0) { "max must be positive: $max" }
            val batch = store.pendingBatch(deliveredPrefix, max)
            if (batch.isEmpty()) return DrainResult(0, 0)
            val (sent, sendFailed) = send(batch)
            val acked = ArrayList<Pair<PendingEvent, RecordMetadata>>(sent.size)
            val heldBack = HashSet<String>()
            sent.forEach { (event, ack) ->
                val meta = if (event.taxpayerId in heldBack) null else awaitAck(ack)
                if (meta == null) heldBack += event.taxpayerId else acked += event to meta
            }
            store.recordDeliveries(topic, acked)
            val recorded = acked.mapTo(HashSet()) { it.first.eventId }
            val firstPending = batch.firstOrNull { it.eventId !in recorded }
            deliveredPrefix = firstPending?.let { it.eventId - 1 } ?: batch.last().eventId
            DrainResult(published = acked.size, failed = sent.size - acked.size + sendFailed)
        }

    /** Starts a background loop calling [drainOnce]; it survives broker and database outages. Returns at once. */
    fun start() {
        synchronized(this) {
            if (worker?.isAlive == true) return
            val signal = CountDownLatch(1)
            stopSignal = signal
            worker = Thread({ loop(signal) }, "outbox-relay").apply { isDaemon = true }.also { it.start() }
        }
    }

    /** Stops the loop and waits for it, at most 10 s. Does not close the producer. */
    fun stop() {
        val running =
            synchronized(this) {
                stopSignal.countDown()
                worker.also { worker = null }
            }
        running?.join(STOP_WAIT_MS)
    }

    override fun close() = stop()

    /** Sends in order; stops at the first send that fails synchronously (later rows wait for the next drain). */
    private fun send(batch: List<PendingEvent>): Pair<List<Pair<PendingEvent, Future<RecordMetadata>>>, Int> {
        val sent = ArrayList<Pair<PendingEvent, Future<RecordMetadata>>>(batch.size)
        for (event in batch) {
            val headers = listOf<Header>(RecordHeader(EVENT_ID_HEADER, event.eventId.toString().toByteArray()))
            val value = OutboxPayloads.messageValue(event.eventId, event.payload)
            val record = ProducerRecord<String, ByteArray>(topic, null, event.taxpayerId, value, headers)
            try {
                sent += event to producer.send(record)
            } catch (e: Exception) {
                log.log(System.Logger.Level.WARNING, "outbox send of event ${event.eventId} failed: $e")
                return sent to 1
            }
        }
        return sent to 0
    }

    private fun awaitAck(ack: Future<RecordMetadata>): RecordMetadata? =
        try {
            ack.get(ACK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            null
        } catch (e: TimeoutException) {
            null
        } catch (e: CancellationException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    private fun loop(signal: CountDownLatch) {
        while (signal.count > 0) {
            val pause =
                try {
                    val r = drainOnce(BATCH)
                    when {
                        r.failed > 0 -> RETRY_MS
                        r.published == BATCH -> 0L
                        else -> IDLE_MS
                    }
                } catch (e: Exception) {
                    log.log(System.Logger.Level.WARNING, "outbox drain failed; retrying: $e")
                    RETRY_MS
                }
            if (pause > 0 && signal.await(pause, TimeUnit.MILLISECONDS)) return
        }
    }

    private companion object {
        const val EVENT_ID_HEADER = "eventId"
        const val BATCH = 500
        const val IDLE_MS = 100L
        const val RETRY_MS = 1_000L
        const val STOP_WAIT_MS = 10_000L

        /** The producer completes every send within its delivery timeout; this is only a backstop. */
        const val ACK_WAIT_MS = OutboxProducers.DELIVERY_TIMEOUT_MS + 5_000L
        val log: System.Logger = System.getLogger(OutboxRelay::class.java.name)
    }
}
