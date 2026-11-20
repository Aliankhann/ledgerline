package dev.ledgerline.ledger.outbox

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.errors.RecordBatchTooLargeException
import org.apache.kafka.common.errors.RecordTooLargeException
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
 * Per-taxpayer order (AC7, B-015): a taxpayer's next event is sent only after the broker acknowledged its previous
 * one, so no later event of a taxpayer can reach the topic before an earlier one. When a send fails, that taxpayer
 * stops for the rest of the drain and the next drain resends from the failed event. Different taxpayers are sent in
 * parallel: each drain runs in rounds, one event per taxpayer per round, and all acks of a round are awaited together.
 *
 * Poison events (OWNER-INPUT: C27, B-016, B-017): an event whose body cannot be decoded, whose message value exceeds
 * [maxEventBytes], or that the producer or broker refuses as too large is parked in `ledger.outbox_parked` with a
 * reason and logged. It is never retried, and it holds back the later events of its own taxpayer only.
 */
class OutboxRelay(
    dataSource: DataSource,
    private val producer: Producer<String, ByteArray>,
    private val topic: String = OutboxTopic.NAME,
    private val maxEventBytes: Int = OutboxLimits.DEFAULT_MAX_EVENT_BYTES,
) : AutoCloseable {
    private val store = OutboxStore(dataSource)
    private val drainLock = ReentrantLock()

    init {
        OutboxLimits.requireValid(maxEventBytes)
    }

    /**
     * Every event id up to here is delivered, parked or held back behind a parked event, so queries start above it.
     * Valid because event ids increase in commit order (V7) and delivery and parked rows are never removed. Starts at
     * 0 for each relay.
     */
    private var deliveredPrefix = 0L

    private var worker: Thread? = null
    private var stopSignal = CountDownLatch(0)

    /**
     * Sends up to [max] pending rows in ascending event id order, each taxpayer's rows one at a time, and records the
     * acknowledged ones and the parked ones. Broker errors never throw (that row counts as failed and it and the later
     * rows of its taxpayer stay pending); database errors do.
     */
    fun drainOnce(max: Int = 500): DrainResult =
        drainLock.withLock {
            require(max > 0) { "max must be positive: $max" }
            val batch = store.pendingBatch(deliveredPrefix, max)
            if (batch.isEmpty()) return DrainResult(0, 0)
            val drain = Drain()
            val queues = drain.prepare(batch)
            while (queues.isNotEmpty()) drain.round(queues)
            store.record(topic, drain.acked, drain.parked)
            drain.parked.forEach {
                log.log(
                    System.Logger.Level.ERROR,
                    "outbox event ${it.eventId} (taxpayer ${it.taxpayerId}) parked: ${it.reason}: ${it.detail}; " +
                        "later events of this taxpayer are held back (OWNER-INPUT: C27)",
                )
            }
            val recorded = HashSet<Long>(drain.acked.size).apply { drain.acked.forEach { add(it.first.eventId) } }
            val firstPending = batch.firstOrNull { it.eventId !in recorded }
            deliveredPrefix = firstPending?.let { it.eventId - 1 } ?: batch.last().eventId
            log.log(
                System.Logger.Level.INFO,
                "outbox drain: ${drain.acked.size} published, ${drain.failed} failed, ${drain.parked.size} parked " +
                    "of ${batch.size} pending to $topic",
            )
            DrainResult(published = drain.acked.size, failed = drain.failed, parked = drain.parked.size)
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

    private class Outgoing(val event: PendingEvent, val record: ProducerRecord<String, ByteArray>)

    /** The state of one [drainOnce]. */
    private inner class Drain {
        val acked = ArrayList<Pair<PendingEvent, RecordMetadata>>()
        val parked = ArrayList<ParkedEvent>()
        var failed = 0

        /**
         * Builds each row's record, in event id order, grouped by taxpayer. A row that cannot be published is parked,
         * and the later rows of its taxpayer are left out of this drain (later drains do not read them at all).
         */
        fun prepare(batch: List<PendingRow>): LinkedHashMap<String, ArrayDeque<Outgoing>> {
            val queues = LinkedHashMap<String, ArrayDeque<Outgoing>>()
            val blocked = HashSet<String>()
            for (row in batch) {
                when (row) {
                    is UnreadableRow -> {
                        park(row.eventId, row.taxpayerId, row.reason, row.detail)
                        row.taxpayerId?.let { blocked += it }
                    }
                    is PendingEvent -> {
                        if (row.taxpayerId in blocked) continue
                        val outgoing = outgoing(row)
                        if (outgoing == null) {
                            blocked += row.taxpayerId
                        } else {
                            queues.getOrPut(row.taxpayerId) { ArrayDeque() }.addLast(outgoing)
                        }
                    }
                }
            }
            return queues
        }

        /** Sends the head event of every taxpayer, then awaits those acks; a taxpayer whose send fails stops. */
        fun round(queues: LinkedHashMap<String, ArrayDeque<Outgoing>>) {
            val inFlight = queues.map { (taxpayer, queue) -> Triple(taxpayer, queue.first(), send(queue.first())) }
            for ((taxpayer, outgoing, ack) in inFlight) {
                when (val outcome = awaitAck(ack)) {
                    is Ack.Done -> {
                        acked += outgoing.event to outcome.meta
                        val queue = queues.getValue(taxpayer)
                        queue.removeFirst()
                        if (queue.isEmpty()) queues.remove(taxpayer)
                    }
                    is Ack.TooLarge -> {
                        park(outgoing.event.eventId, taxpayer, ParkReason.RECORD_TOO_LARGE, outcome.error.toString())
                        queues.remove(taxpayer)
                    }
                    is Ack.Failed -> {
                        log.log(
                            System.Logger.Level.WARNING,
                            "outbox send of event ${outgoing.event.eventId} failed; taxpayer $taxpayer waits for " +
                                "the next drain: ${outcome.error}",
                        )
                        failed += 1
                        queues.remove(taxpayer)
                    }
                }
            }
        }

        /** The record for [event], or null (and the event parked) if its body is unreadable or too large. */
        private fun outgoing(event: PendingEvent): Outgoing? {
            val value =
                try {
                    OutboxPayloads.messageValue(event.eventId, event.payload)
                } catch (e: Exception) {
                    park(event.eventId, event.taxpayerId, ParkReason.PAYLOAD_UNREADABLE, e.toString().take(DETAIL_MAX))
                    return null
                }
            if (value.size > maxEventBytes) {
                park(
                    event.eventId,
                    event.taxpayerId,
                    ParkReason.EVENT_TOO_LARGE,
                    "message value is ${value.size} bytes; the maximum is $maxEventBytes",
                )
                return null
            }
            val headers = listOf<Header>(RecordHeader(EVENT_ID_HEADER, event.eventId.toString().toByteArray()))
            return Outgoing(event, ProducerRecord(topic, null, event.taxpayerId, value, headers))
        }

        private fun park(
            eventId: Long,
            taxpayerId: String?,
            reason: ParkReason,
            detail: String,
        ) {
            parked += ParkedEvent(eventId, taxpayerId, reason, detail)
        }
    }

    private sealed interface Ack {
        class Done(val meta: RecordMetadata) : Ack

        class TooLarge(val error: Throwable) : Ack

        class Failed(val error: Throwable) : Ack
    }

    /** The producer's future, or the exception `send` threw (Errors, such as a simulated crash, propagate). */
    private fun send(outgoing: Outgoing): Result<Future<RecordMetadata>> =
        try {
            Result.success(producer.send(outgoing.record))
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun awaitAck(sent: Result<Future<RecordMetadata>>): Ack {
        val ack = sent.getOrElse { return classify(it) }
        return try {
            Ack.Done(ack.get(ACK_WAIT_MS, TimeUnit.MILLISECONDS))
        } catch (e: ExecutionException) {
            classify(e.cause ?: e)
        } catch (e: TimeoutException) {
            Ack.Failed(e)
        } catch (e: CancellationException) {
            Ack.Failed(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Ack.Failed(e)
        }
    }

    /** A size refusal can never succeed on retry, so it parks the event; anything else is retried next drain. */
    private fun classify(error: Throwable): Ack =
        if (error is RecordTooLargeException || error is RecordBatchTooLargeException) {
            Ack.TooLarge(error)
        } else {
            Ack.Failed(error)
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
        const val DETAIL_MAX = 500

        /** The producer completes every send within its delivery timeout; this is only a backstop. */
        const val ACK_WAIT_MS = OutboxProducers.DELIVERY_TIMEOUT_MS + 5_000L
        val log: System.Logger = System.getLogger(OutboxRelay::class.java.name)
    }
}
